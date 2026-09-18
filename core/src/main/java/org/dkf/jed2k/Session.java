package org.dkf.jed2k;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.bitlet.weupnp.PortMappingEntry;
import org.dkf.jed2k.alert.*;
import org.dkf.jed2k.disk.AsyncOperationResult;
import org.dkf.jed2k.disk.FileHandler;
import org.dkf.jed2k.disk.TransferCallable;
import org.dkf.jed2k.exception.BaseErrorCode;
import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.kad.DhtTracker;
import org.dkf.jed2k.kad.KadSearchEntryDistinct;
import org.dkf.jed2k.kad.Listener;
import org.dkf.jed2k.pool.BufferPool;
import org.dkf.jed2k.pool.Pool;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.SearchEntry;
import org.dkf.jed2k.protocol.kad.KadId;
import org.dkf.jed2k.protocol.kad.KadSearchEntry;
import org.dkf.jed2k.protocol.server.search.SearchRequest;
import org.dkf.jed2k.protocol.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;

public class Session extends Thread {
    private static Logger log = LoggerFactory.getLogger(Session.class);
    Selector selector = null;
    protected ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<Runnable>();
    ServerConnection serverConection = null;
    private ServerSocketChannel ssc = null;

    Map<Hash, Transfer> transfers = new HashMap<Hash, Transfer>();
    ArrayList<PeerConnection> connections = new ArrayList<PeerConnection>(); // incoming connections
    UDPConnection udpConnection = null; // for UDP source requests
    List<InetSocketAddress> knownServers = Collections.synchronizedList(new ArrayList<InetSocketAddress>()); // servers from OP_SERVERLIST
    Settings settings = null;
    long lastTick = Time.currentTime();
    HashMap<Integer, Hash> callbacks = new HashMap<Integer, Hash>();
    private ByteBuffer skipDataBuffer = null;
    private byte[] zBuffer = null;
    long zBufferLastAllocatedTime = 0;
    private BufferPool bufferPool = null;
    private ExecutorService diskIOService = Executors.newSingleThreadExecutor();
    private ExecutorService upnpService = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService serverQueryService = Executors.newScheduledThreadPool(1); // periodic server queries for source refresh
    private AtomicBoolean finished = new AtomicBoolean(false);
    private boolean aborted = false;
    private boolean serverQueryScheduled = false;
    private long lastServerQueryTime = 0;
    private Statistics accumulator = new Statistics();
    private GatewayDiscover discover = new GatewayDiscover();
    private GatewayDevice device = null;
    /**
     * last UPnP port-mapping outcome for UI diagnostics:
     * "off", "ok", "no device" or "error"
     */
    private volatile String upnpStatus = "off";
    private boolean upnpRenewalScheduled = false;

    private final ServerConnectionPolicy serverConnectionPolicy = new ServerConnectionPolicy(5, 5);

    // fallback to another known server when current server retry budget is exhausted
    private long fallbackNextServerTime = -1;
    private int fallbackCursor = 0;

    /**
     * async disk io futures
     */
    LinkedList<Future<AsyncOperationResult> > aioFutures = new LinkedList<Future<AsyncOperationResult>>();

    /**
     * async originators
     */
    LinkedList<Transfer> aioOrigins = new LinkedList<>();

    /**
     * external DHT tracker object
     */
    private WeakReference<DhtTracker> dhtTracker = new WeakReference<DhtTracker>(null);

    /**
     * sources search result callback
     */
    private static class DhtSourcesCallback implements Listener {
        final Session session;
        final WeakReference<Transfer> weakTransfer;

        public DhtSourcesCallback(final Session session, final Transfer t) {
            this.session = session;
            this.weakTransfer = new WeakReference<Transfer>(t);
        }

        @Override
        public void process(final List<KadSearchEntry> data) {
            session.commands.add(new Runnable() {
                @Override
                public void run() {
                    Transfer transfer = weakTransfer.get();
                    if (transfer == null) {
                        log.debug("[session] transfer not exists for searched result, just skip it");
                        return;
                    }

                    for(final KadSearchEntry kse: data) {
                        KadId target = kse.getKid();
                        assert target != null; // actually impossible

                        if (transfer == null || transfer.isFinished()) {
                            log.debug("[session] transfer for {} not exists or finished", target);
                            continue;
                        }

                        int ip = 0;
                        int sourceType = 0;
                        int sourcePort = 0;
                        int sourceUPort = 0;
                        int lowId = 0;
                        int serverIp = 0;
                        int serverPort = 0;
                        KadId id = null;
                        int cryptOptions = 0;

                        try {
                            for (final Tag t : kse.getInfo()) {
                                switch (t.getId()) {
                                    case Tag.TAG_SOURCETYPE:
                                        sourceType = t.intValue();
                                        break;
                                    case Tag.TAG_SOURCEIP:
                                        ip = Utils.ntohl(t.intValue());
                                        break;
                                    case Tag.TAG_SOURCEPORT:
                                        sourcePort = t.intValue();
                                        break;
                                    case Tag.TAG_CLIENTLOWID:
                                        lowId = t.intValue();
                                        break;
                                    case Tag.TAG_SOURCEUPORT:
                                        sourceUPort = t.intValue();
                                        break;
                                    case Tag.TAG_SERVERIP:
                                        serverIp = t.intValue();
                                        break;
                                    case Tag.TAG_SERVERPORT:
                                        serverPort = t.intValue();
                                        break;
                                    case Tag.TAG_BUDDYHASH:
                                        try {
                                            id = new KadId(Hash.fromString(t.stringValue()));
                                        } catch(JED2KException e) {
                                            log.warn("[session] unable to extract buddy getHash {}", e);
                                        }
                                        break;
                                    case Tag.TAG_ENCRYPTION:
                                        cryptOptions = t.intValue();
                                        break;
                                    default:
                                        log.debug("[session] unhandled KAD search tag {}", t);
                                        break;
                                }
                            }
                        } catch(JED2KException e) {
                            log.error("[session] processing kad search sources result failed {}", e);
                        }

                        assert transfer != null;
                        // process here only non-firewalled sources
                        if (ip != 0 && sourcePort != 0 && (sourceType == 1 || sourceType == 4)) {
                            try {
                                transfer.addPeer(new Endpoint(ip, sourcePort), PeerInfo.DHT);
                            } catch(JED2KException e) {
                                log.error("[session] unable to add peer {}:{} to transfer {} with error {}", ip, sourcePort, transfer, e);
                            }
                        }
                    }
                }
            });
        }
    }

    private static class DhtDebugCallback implements Listener {

        @Override
        public void process(List<KadSearchEntry> data) {
            log.info("[session] DHT debug callback results size {}", data.size());
            for(final KadSearchEntry e: data) {
                log.info("entry: {}", e);
            }
        }
    }

    private static class DhtKeywordsCallback implements Listener {
        final Session session;
        private final long minSize;
        private final long maxSize;
        private final int sources;
        private final int completeSources;

        public DhtKeywordsCallback(final Session session, long minSize, long maxSize, int sources, int completeSources) {
            this.session = session;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.sources = sources;
            this.completeSources = completeSources;
        }

        @Override
        public void process(List<KadSearchEntry> data) {
            List<SearchEntry> filtered = new LinkedList<>();
            List<SearchEntry> res = KadSearchEntryDistinct.distinct(data);
            for(final SearchEntry e: res) {
                if (minSize > 0 && e.getFileSize() < minSize) continue;
                if (maxSize > 0 && e.getFileSize() > maxSize) continue;
                if (sources > 0 && e.getSources() < sources) continue;
                if (completeSources > 0 && e.getCompleteSources() < completeSources) continue;
                filtered.add(e);
            }

            session.pushAlert(new SearchResultAlert(filtered, false, SearchResultAlert.SOURCE_KAD));
        }
    }

    private static class MultiWordDhtCallback implements Listener {
        private static final int MAX_ENTRIES = 20000;

        private final Session session;
        private final int totalWords;
        private final long minSize;
        private final long maxSize;
        private final int sources;
        private final int completeSources;
        private final AtomicInteger completedWords = new AtomicInteger(0);
        private final ConcurrentHashMap<Hash, Integer> hashCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Hash, KadSearchEntry> hashEntries = new ConcurrentHashMap<>();

        MultiWordDhtCallback(Session session, int totalWords, long minSize, long maxSize, int sources, int completeSources) {
            this.session = session;
            this.totalWords = totalWords;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.sources = sources;
            this.completeSources = completeSources;
        }

        @Override
        public void process(List<KadSearchEntry> data) {
            Set<Hash> thisWordHashes = new HashSet<>();
            for (KadSearchEntry e : data) {
                Hash h = e.getHash();
                if (h == null) continue;
                thisWordHashes.add(h);
                // keep the entry with more sources
                KadSearchEntry prev = hashEntries.get(h);
                if (prev == null) {
                    hashEntries.put(h, e);
                } else if (e.getSources() > prev.getSources()) {
                    hashEntries.put(h, e);
                }
                // bound map to avoid unbounded growth on big dht responses
                if (hashEntries.size() > MultiWordDhtCallback.MAX_ENTRIES) {
                    hashEntries.clear();
                    hashCounts.clear();
                }
            }
            for (Hash h : thisWordHashes) {
                hashCounts.merge(h, 1, Integer::sum);
            }

            completeOne();
        }

        /** word search could not be started (e.g DHT_REQUEST_ALREADY_RUNNING) - count it as empty word */
        void onWordSkipped() {
            completeOne();
        }

        private void completeOne() {
            if (completedWords.incrementAndGet() == totalWords) {
                List<SearchEntry> filtered = new LinkedList<>();
                for (Map.Entry<Hash, Integer> entry : hashCounts.entrySet()) {
                    if (entry.getValue() >= totalWords) {
                        KadSearchEntry e = hashEntries.get(entry.getKey());
                        if (e == null) continue;
                        if (minSize > 0 && e.getFileSize() < minSize) continue;
                        if (maxSize > 0 && e.getFileSize() > maxSize) continue;
                        if (sources > 0 && e.getSources() < sources) continue;
                        if (completeSources > 0 && e.getCompleteSources() < completeSources) continue;
                        filtered.add(e);
                    }
                }
                session.pushAlert(new SearchResultAlert(filtered, false, SearchResultAlert.SOURCE_KAD));
            }
        }
    }

    // from last established server connection
    int clientId    = 0;
    int tcpFlags    = 0;
    int auxPort     = 0;

    private BlockingQueue<Alert> alerts = new LinkedBlockingQueue<Alert>();

    public Session(final Settings st) {
        // TODO - validate settings before usage
        settings = st;
        bufferPool = new BufferPool(st.bufferPoolSize);
    }

    void closeListenSocket() {
        try {
            if (ssc != null) {
                ssc.close();
            }
        } catch(IOException e) {
            log.error("unable to close listen socket {}", e);
        } finally {
            ssc = null;
        }
    }

    /**
     * start listening server socket
     */
    private void listen() {
        closeListenSocket();

        try {
            if (settings.listenPort > 0) {
                assert selector != null;
                log.info("start listening on port {}", settings.listenPort);
                ssc = ServerSocketChannel.open();
                ssc.socket().bind(new InetSocketAddress(settings.listenPort));
                ssc.configureBlocking(false);
                ssc.register(selector, SelectionKey.OP_ACCEPT);
                pushAlert(new ListenAlert("", settings.listenPort));
            } else {
                log.info("no listen mode, listen port is {}", settings.listenPort);
            }
        }
        catch(IOException e) {
            log.error("[listen] failed {}", e);
            closeListenSocket();
            pushAlert(new ListenAlert(e.getMessage(), settings.listenPort));
        }
        catch(IllegalArgumentException e) {
            log.error("[listen] illegal argument exception {}", e);
            closeListenSocket();
            pushAlert(new ListenAlert(e.getMessage(), settings.listenPort));
        }
        catch(Exception e) {
            log.error("[listen] unexpected exception {}", e);
            closeListenSocket();
            pushAlert(new ListenAlert(e.getMessage(), settings.listenPort));
        }

        // start periodic server queries for source refresh - schedule unconditionally,
        // guard empty known servers / transfers inside the task (schedule is idempotent).
        // LowID clients can never reach other LowIDs, so they refresh sources
        // twice as often to compensate the smaller reachable source pool
        if (serverQueryService != null && !serverQueryScheduled) {
            serverQueryScheduled = true;
            serverQueryService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    if (knownServers.isEmpty() || transfers.isEmpty()) return;
                    long intervalMs = Utils.isLowId(clientId) ? 2*60*1000L : 5*60*1000L;
                    long now = System.currentTimeMillis();
                    if (now - lastServerQueryTime >= intervalMs) {
                        lastServerQueryTime = now;
                        queryAllKnownServersForSources();
                    }
                }
            }, 60, 60, TimeUnit.SECONDS);
            log.info("[session] started periodic server queries (2 min on LowID, 5 min otherwise)");
        }

        // initialize UDP connection for source requests
        if (udpConnection == null) {
            try {
                udpConnection = new UDPConnection(this);
                log.info("UDP connection initialized for source requests");
            } catch(Exception e) {
                log.error("[listen] failed to init UDP connection {}", e);
            }
        }
    }

    /**
     * synchronized session internal processing method
     * @param ec
     * @throws IOException
     */
    private synchronized void on_tick(BaseErrorCode ec, int channelCount) {

        if (channelCount != 0) {
            // process channels
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                if (key.isValid()) {
                    try {
                        if(key.isAcceptable()) {
                            // a connection was accepted by a ServerSocketChannel.
                            //log.trace("Key is acceptable");
                            incomingConnection();
                        } else if (key.isConnectable()) {
                            // a connection was established with a remote server/peer.
                            //log.trace("Key is connectable");
                            ((Connection)key.attachment()).onConnectable();
                        } else if (key.isReadable()) {
                            // a channel is ready for reading
                            //log.trace("Key is readable");
                            Object attachment = key.attachment();
                            if (attachment instanceof UDPConnection) {
                                ((UDPConnection) attachment).onReadable();
                            } else if (attachment != null) {
                                ((Connection) attachment).onReadable();
                            }
                        } else if (key.isWritable()) {
                            // a channel is ready for writing
                            //log.trace("Key is writeable");
                            Object attachment = key.attachment();
                            if (attachment instanceof UDPConnection) {
                                ((UDPConnection) attachment).onWriteable();
                            } else if (attachment != null) {
                                ((Connection) attachment).onWriteable();
                            }
                        }
                    } catch(Throwable t) {
                        // never let a single channel failure kill the whole session
                        log.error("[session] error while processing selection key", t);
                    }
                }

                keyIterator.remove();
            }
        }

        /**
         * handle user's command and process internal tasks in
         * transfers, peers and other structures every 1 second
         */
        long tickIntervalMs = Time.currentTime() - lastTick;
        if (tickIntervalMs >= 1000) {
            lastTick = Time.currentTime();
            secondTick(Time.currentTime(), tickIntervalMs);
        }
    }

    public void secondTick(long currentSessionTime, long tickIntervalMS) {

        Iterator<Map.Entry<Hash, Transfer>> itr = transfers.entrySet().iterator();

        while(itr.hasNext()) {
            itr.next().getValue().secondTick(accumulator, tickIntervalMS);
        }

        // second tick on server connection or re-connect to server
        if (serverConection != null) {
            serverConection.secondTick(tickIntervalMS);
        } else if (settings.reconnectoToServer) {
            Pair<String, InetSocketAddress> serverConnectionCandidate = serverConnectionPolicy.getConnectCandidate(currentSessionTime);

            // somehow second(address) is null in rare cases
            if (serverConnectionCandidate != null && serverConnectionCandidate.left != null && serverConnectionCandidate.right != null) {
                try {
                    serverConection = ServerConnection.makeConnection(serverConnectionCandidate.getLeft()
                            , serverConnectionCandidate.getRight()
                            , Session.this);
                    serverConection.connect();
                } catch(JED2KException e) {
                    // emit alert - connect to server failed
                    log.error("server connection failed {}", e);
                }
            } else if (serverConnectionPolicy.hasCandidate()
                    && !serverConnectionPolicy.hasIterations()
                    && serverQueryService != null
                    && !knownServers.isEmpty()) {
                // retry budget for the failed server is exhausted - periodically try another known server
                if (fallbackNextServerTime == -1 || fallbackNextServerTime < currentSessionTime) {
                    InetSocketAddress fallback = pickKnownServerFallback();
                    fallbackNextServerTime = currentSessionTime + Time.minutes(2);
                    if (fallback != null) {
                        try {
                            serverConection = ServerConnection.makeConnection(fallback.getHostString()
                                    , fallback
                                    , Session.this);
                            serverConection.connect();
                            log.info("[session] fallback connect to known server {}", fallback);
                        } catch(JED2KException e) {
                            log.error("server fallback connection failed {}", e);
                        }
                    } else {
                        // no other server available right now - re-check later
                        fallbackNextServerTime = currentSessionTime + Time.minutes(5);
                    }
                }
            }
        }

        processDiskTasks();

        // TODO - run second tick on peer connections
        // execute user's commands
        Runnable r = commands.poll();
        while(r != null) {
            r.run();
            r = commands.poll();
        }

        accumulator.secondTick(tickIntervalMS);
        connectNewPeers();
        //log.trace(bufferPool.toString());
    }

    @Override
    public void run() {
        try {
            log.debug("Session started");
            selector = Selector.open();
            listen();

            while(!aborted && !interrupted()) {
                try {
                    int channelCount = selector.select(1000);
                    Time.updateCachedTime();
                    on_tick(ErrorCode.NO_ERROR, channelCount);
                } catch(Throwable t) {
                    // be resilient - single error must not kill the session thread
                    log.error("[run] session tick error", t);
                }
            }
        }
        catch(IOException e) {
            log.error("[run] session interrupted with error {}", e);
        }
        finally {
            log.info("Session is closing");
            commands.clear();

            try {
                if (selector != null) selector.close();
            }
            catch(IOException e) {
                log.error("[run] close selector failed {}", e);
            }

            // close listen socket
            if (ssc != null) {
                try {
                    ssc.close();
                } catch(IOException e) {
                    log.error("listen socket close error {}", e);
                }
            }

            // stop server connection
            if (serverConection != null) serverConection.close(ErrorCode.SESSION_STOPPING);

            // traverse on local copy of transfers to avoid ConcurrentModificationException
            List<Transfer> transfersCopy = new LinkedList<>();
            transfersCopy.addAll(transfers.values());

            // abort all transfers
            for(final Transfer t: transfersCopy) {
                t.abort(false, true);   // hard abort tasks to exit as soon as possible. buffers leak possible
            }

            transfers.clear();
            transfersCopy.clear();

            // 5 seconds for close all transfers
            for(int i = 0; i < 50; ++i) {
                log.info("process disk tasks {} iteration", i);
                processDiskTasks();
                if (aioFutures.isEmpty()) break;

                try {
                    Thread.sleep(100);
                } catch(Exception e) {
                    log.error("sleep error {}", e);
                }
            }

            if (!aioFutures.isEmpty()) {
                log.warn("not all futures completed");
                for(final Transfer t: transfers.values()) {
                    log.warn("transfer {} is not finished", t.getHash());
                }
            }

            ArrayList<PeerConnection> localConnections = (ArrayList<PeerConnection>) connections.clone();
            for(final PeerConnection c: localConnections) {
                c.close(ErrorCode.SESSION_STOPPING);
            }

            localConnections.clear();
            connections.clear();

            // stop service
            diskIOService.shutdown();
            upnpService.shutdown();
            serverQueryService.shutdown();
            stopUPnPImpl("TCP");
            stopUPnPImpl("UDP");
            log.info("Session finished");
            finished.set(true);
        }
    }

    /**
     * create new peer connection for incoming connection
     */
    void incomingConnection() {
        try {
            SocketChannel sc = ssc.accept();
            PeerConnection p = PeerConnection.make(sc, this);
            connections.add(p);
        }
        catch(IOException e) {
            log.error("Socket accept failed {}", e);
        }
        catch (JED2KException e) {
            log.error("Peer connection creation failed {}", e);
        }
    }

    void closeConnection(PeerConnection p) {
        connections.remove(p);
    }

    void openConnection(Endpoint point) throws JED2KException {
        if (findPeerConnection(point) == null) {
            PeerConnection p = PeerConnection.make(Session.this, point, null, null);
            if (p != null) {
                connections.add(p);
                p.connect();
            } else {
                log.error("unable to open connection {}", point);
            }
        }
    }

    public void connectoTo(final String identifier
            , final InetSocketAddress address) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                serverConnectionPolicy.removeConnectCandidates();
                fallbackNextServerTime = -1;
                fallbackCursor = 0;

                if (serverConection != null) {
                    serverConection.close(ErrorCode.NO_ERROR);
                }

                try {
                    serverConection = ServerConnection.makeConnection(identifier
                            , address
                            , Session.this);
                    serverConection.connect();
                } catch(JED2KException e) {
                    // emit alert - connect to server failed
                    log.error("server connection failed {}", e);
                }
            }
        });
    }

    public void connectoTo(final String identifier, final String host, final int port) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                try {
                    serverConnectionPolicy.removeConnectCandidates();
                    fallbackNextServerTime = -1;
                    fallbackCursor = 0;

                    final InetSocketAddress address = new InetSocketAddress(host, port);

                    if (serverConection != null) {
                        serverConection.close(ErrorCode.NO_ERROR);
                    }

                    try {
                        serverConection = ServerConnection.makeConnection(identifier, address , Session.this);
                        serverConection.connect();
                        pushAlert(new ServerConnectionAlert(identifier));
                    } catch(JED2KException e) {
                        // emit alert - connect to server failed
                        log.error("server connection failed {}", e);
                    }
                }
                catch(Exception e) {
                    log.error("Illegal input parameters {} or {}", host, port);
                }
            }
        });
    }

    /**
     * disconnect from last connected server if connection exists
     */
    public void disconnectFrom() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                serverConnectionPolicy.removeConnectCandidates();
                if (serverConection != null) {
                    serverConection.close(ErrorCode.NO_ERROR);
                }
            }
        });
    }

    synchronized public String getConnectedServerId() {
        if (serverConection != null && serverConection.isHandshakeCompleted()) return serverConection.getIdentifier();
        return "";
    }

    synchronized public boolean isConnectedToServer() {
        return serverConection != null && serverConection.isHandshakeCompleted();
    }

    synchronized public boolean hasServerConnection() {
        return serverConection != null;
    }

    synchronized public String getConnectedServerName() {
        if (serverConection != null && serverConection.isHandshakeCompleted()) {
            String id = serverConection.getIdentifier();
            return id != null ? id : "";
        }
        return "";
    }


    protected void onServerConnectionClosed(ServerConnection sc, BaseErrorCode ec) {
        if (settings.reconnectoToServer && ec.getCode() != ErrorCode.NO_ERROR.getCode()) {
            serverConnectionPolicy.setServerConnectionFailed(serverConection.getIdentifier()
                    , serverConection.getAddress()
                    , Time.currentTime());
        }

        serverConection = null;
    }

    public void search(final SearchRequest value) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                if (serverConection != null) {
                    serverConection.search(value);
                } else {
                    log.warn("[session] no server connection, search dropped");
                    Session.this.pushAlert(new SearchResultAlert(new LinkedList<>(), false));
                }
            }
        });
    }

    public void searchDhtKeyword(final String keyword, final long minSize, final long maxSize, final int sources, final int completeSources) {
        final Session s = this;
        commands.add(new Runnable() {
            @Override
            public void run() {
                DhtTracker tracker = dhtTracker.get();
                if (tracker == null || tracker.isAborted()) {
                    log.warn("[session] DHT tracker not active, keyword search dropped");
                    s.pushAlert(new SearchResultAlert(new LinkedList<>(), false, SearchResultAlert.SOURCE_KAD));
                    return;
                }
                try {
                    List<String> words = splitKeywords(keyword);
                    if (words.isEmpty()) {
                        log.warn("[session] no search keywords extracted from '{}'", keyword);
                        s.pushAlert(new SearchResultAlert(new LinkedList<>(), false, SearchResultAlert.SOURCE_KAD));
                        return;
                    }
                    if (words.size() == 1) {
                        tracker.searchKeywords(words.get(0), new DhtKeywordsCallback(s, minSize, maxSize, sources, completeSources));
                    } else {
                        // deduplicate words so totalWords counts distinct keywords only
                        List<String> distinctWords = new ArrayList<>(new LinkedHashSet<>(words));
                        MultiWordDhtCallback callback = new MultiWordDhtCallback(s, distinctWords.size(), minSize, maxSize, sources, completeSources);
                        for (String word : distinctWords) {
                            try {
                                tracker.searchKeywords(word, callback);
                            } catch (JED2KException ex) {
                                log.warn("[session] DHT keyword '{}' search failed: {}", word, ex.getMessage());
                                callback.onWordSkipped();
                            }
                        }
                    }
                } catch(JED2KException e) {
                    log.error("[session] unable to start search keyword {} in DHT {}", keyword, e);
                    s.pushAlert(new SearchResultAlert(new LinkedList<>(), false, SearchResultAlert.SOURCE_KAD));
                }
            }
        });
    }

    /**
     * Split search phrase into keywords: punctuation becomes whitespace,
     * only words of length >= 3 are kept (short words are too generic for KAD).
     */
    static List<String> splitKeywords(final String phrase) {
        String normalized = phrase.toLowerCase(Locale.US).replaceAll("[^a-zа-яёіїєґ0-9]+", " ");
        String[] tokens = normalized.split(" ");
        List<String> words = new ArrayList<>();
        for (String token : tokens) {
            String word = token.trim();
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }


    public void searchMore() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                if (serverConection != null) {
                    serverConection.searchMore();
                } else {
                    log.warn("[session] no server connection, search more dropped");
                    Session.this.pushAlert(new SearchResultAlert(new LinkedList<>(), false));
                }
            }
        });
    }

    // TODO - remove only
    public void connectToPeer(final Endpoint point) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                    try {
                        PeerConnection pc = PeerConnection.make(Session.this, point, null, null);
                        connections.add(pc);
                        pc.connect(point.toInetSocketAddress());
                    } catch(JED2KException e) {
                        log.error("new peer connection failed {}", e);
                    }
            }
        });
    }

    private PeerConnection findPeerConnection(Endpoint endpoint) {
        for(PeerConnection p: connections) {
            if (p.hasEndpoint() && endpoint.compareTo(p.getEndpoint()) == 0) return p;
        }

        return null;
    }

    /**
     *
     * @param s contains configuration parameters for session
     */
    public void configureSession(final Settings s) {
    	commands.add(new Runnable() {
			@Override
			public void run() {
				settings = s;
				listen();
			}
    	});
    }

    public void pushAlert(Alert alert) {
        assert(alert != null);
        try {
            alerts.put(alert);
        }
        catch (InterruptedException e) {
            log.error("push alert interrupted {}", e);
            //throw new JED2KException(ErrorCode.INTERRUPTED);
        }
    }

    public Alert  popAlert() {
        return alerts.poll();
    }

    public long getCurrentTime() {
        return lastTick;
    }

    /**
     * create new transfer in session or return previous
     * method synchronized with session second tick method
     * @param h getHash of file(transfer)
     * @param size of file
     * @return TransferHandle with valid transfer of without
     */
    public final synchronized TransferHandle addTransfer(Hash h, long size, File file) throws JED2KException {
        Transfer t = transfers.get(h);

        if (t == null) {
            t = new Transfer(this, new AddTransferParams(h, Time.currentTimeMillis(), size, file, false));
            transfers.put(h, t);
        }

        return new TransferHandle(this, t);
    }

    /**
     * the same as previous instead of external file handler
     * @param h
     * @param size
     * @param handler
     * @return
     * @throws JED2KException
     */
    public final synchronized TransferHandle addTransfer(Hash h, long size, FileHandler handler) throws JED2KException {
        Transfer t = transfers.get(h);

        if (t == null) {
            t = new Transfer(this, new AddTransferParams(h, Time.currentTimeMillis(), size, handler, false));
            transfers.put(h, t);
        }

        return new TransferHandle(this, t);
    }

    /**
     * create new transfer in session or return previously created transfer
     * using add transfer parameters structure with or without resume data block
     * @param atp transfer parameters with or without resume data
     * @return transfer handle
     * @throws JED2KException
     */
    public final synchronized  TransferHandle addTransfer(final AddTransferParams atp) throws JED2KException {
        Transfer t = transfers.get(atp.getHash());

        if (t == null) {
            t = new Transfer(this, atp);
            transfers.put(atp.getHash(), t);
        }

        return new TransferHandle(this, t);
    }

    public final synchronized TransferHandle findTransfer(final Hash h) {
        return new TransferHandle(this, transfers.get(h));
    }

    /**
     * Find transfer directly by hash - used for source exchange
     */
    public final synchronized Transfer findTransferDirect(final Hash h) {
        return transfers.get(h);
    }

    public void removeTransfer(final Hash h, final boolean deleteFile) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                    Transfer t = transfers.get(h);
                    if (t != null) {
                        t.abort(deleteFile, false); // abort transfer, but do not cancel disk tasks to avoid buffers leaking
                        transfers.remove(t.getHash());
                        pushAlert(new TransferRemovedAlert(h));
                    }
            }
        });
    }

    public final synchronized List<TransferHandle> getTransfers() {
        LinkedList<TransferHandle> handles = new LinkedList<TransferHandle>();
        for(final Transfer t: transfers.values()) {
            /*if (!t.isAborted())*/ handles.add(new TransferHandle(this, t));
        }

        return handles;
    }

    void sendSourcesRequest(final Hash h, final long size) {
        // send via TCP to connected server (existing)
        if (serverConection != null) serverConection.sendFileSourcesRequest(h, size);
        // send via UDP to connected server
        if (udpConnection != null && serverConection != null) {
            try {
                udpConnection.sendSourcesRequest(h, size, serverConection.getAddress());
            } catch(Exception e) {
                log.debug("UDP source request to connected server failed: {}", e.getMessage());
            }
        }
        // send via UDP to ALL known servers for faster discovery
        sendMultiServerSourcesRequest(h, size);
    }

    void sendDhtSourcesRequest(final Hash h, final long size, final Transfer t) {
        if (dhtTracker != null) {
            try {
                DhtTracker dht = dhtTracker.get();
                if (dht != null) dht.searchSources(h, size, new DhtSourcesCallback(this, t));
            } catch(JED2KException e) {
                log.error("[session] dht search sources error {}", e);
            }
        }
    }

    void addKnownServers(List<Endpoint> servers) {
        for (Endpoint ep : servers) {
            int ip = ep.getIP();
            int port = ep.getPort() & 0xFFFF;
            InetSocketAddress server = new InetSocketAddress(Utils.int2Address(ip), port);
            boolean found = false;
            for (InetSocketAddress existing : knownServers) {
                if (existing.getPort() == server.getPort() && existing.getAddress() != null && existing.getAddress().equals(server.getAddress())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                knownServers.add(server);
                log.debug("[session] added known server: {}:[{}]", server.getAddress(), server.getPort());
            }
        }
    }

    /**
     * Send source requests to all known servers for faster discovery
     */
    void sendMultiServerSourcesRequest(final Hash h, final long size) {
        if (knownServers.isEmpty()) return;
        if (udpConnection == null) return;
        for (InetSocketAddress server : knownServers) {
            try {
                udpConnection.sendSourcesRequest(h, size, server);
            } catch(Exception e) {
                log.debug("[session] UDP source request to {} failed: {}", server, e.getMessage());
            }
        }
    }

    /**
     * executes each second
     * traverse transfers list and try to connect new peers if limits not exceeded and transfer want more peers
     */
    void connectNewPeers() {
        int stepsSinceLastConnect = 0;
        int maxConnectionsPerSecond = settings.maxConnectionsPerSecond;
        int numTransfers = transfers.size();
        boolean enumerateCandidates = true;

        if (numTransfers > 0 && connections.size() < settings.sessionConnectionsLimit) {
            //log.finest("connectNewPeers with transfers count " + numTransfers);
            while (enumerateCandidates) {
                for (Map.Entry<Hash, Transfer> entry : transfers.entrySet()) {
                    Transfer t = entry.getValue();

                    if (t.wantMorePeers()) {
                        try {
                            if (t.tryConnectPeer(Time.currentTime())) {
                                --maxConnectionsPerSecond;
                                stepsSinceLastConnect = 0;
                            }
                        } catch (JED2KException e) {
                            log.error("exception on connect new peer {}", e);
                        }
                    }

                    ++stepsSinceLastConnect;

                    // if we have gone two whole loops without
                    // handing out a single connection, break
                    if (stepsSinceLastConnect > numTransfers*2) {
                        enumerateCandidates = false;
                        break;
                    }

                    // if we should not make any more connections
                    // attempts this tick, abort
                    if (maxConnectionsPerSecond == 0) {
                        enumerateCandidates = false;
                        break;
                    }
                }

                // must not happen :) but still
                if (transfers.isEmpty()) break;
            }
        }
    }

    /**
     * allocate new fixed size byte buffer from session's buffer pool
     * @return byte buffer from common session buffer pool
     */
    public ByteBuffer allocatePoolBuffer() throws JED2KException {
        return bufferPool.allocate();
    }

    /**
     * sometimes we need to skip some data received from peer
     * skip data buffer is one shared data buffer for all connections
     * @return byte buffer
     */
    ByteBuffer allocateSkipDataBufer() {
        if (skipDataBuffer == null) {
            skipDataBuffer = ByteBuffer.allocate(Constants.BLOCK_SIZE_INT);
        }

        return skipDataBuffer.duplicate();
    }

    Pool<ByteBuffer> getBufferPool() {
        return bufferPool;
    }

    /**
     * provide one per session temporary buffer for inflate z data
     * @return common z buffer for decompress compressed data
     */
    byte[] allocateTemporaryInflateBuffer() {
        zBufferLastAllocatedTime = Time.currentTime();
        if (zBuffer == null) zBuffer = new byte[Constants.BLOCK_SIZE_INT];
        return zBuffer;
    }

    /**
     * execute async disk operation
     * @param task special task
     * @return future
     */
    public void submitDiskTask(TransferCallable<AsyncOperationResult> task) {
        assert task.getTransfer() != null;
        aioFutures.add(diskIOService.submit(task));
        aioOrigins.add(task.getTransfer());
        assert aioFutures.size() == aioOrigins.size();
    }

    public void removeDiskTask(final Transfer t) {
        assert aioFutures.size() == aioOrigins.size();

        Iterator<Transfer> trItr = aioOrigins.iterator();
        Iterator<Future<AsyncOperationResult>> fItr = aioFutures.iterator();
        while(trItr.hasNext()) {
            Future<AsyncOperationResult> future = fItr.next();
            Transfer tran = trItr.next();

            if (tran == t) {
                future.cancel(false);
                trItr.remove();
                fItr.remove();
            }
        }
    }

    private void processDiskTasks() {
        assert aioOrigins.size() == aioFutures.size();

        while(!aioFutures.isEmpty()) {
            Future<AsyncOperationResult> res = aioFutures.peek();
            if (!res.isDone()) break;
            res = aioFutures.poll();
            Transfer t = aioOrigins.poll();

            try {
                res.get().onCompleted();
            } catch (InterruptedException e) {
                log.warn("second tick aio InterruptedException {}", e);
            } catch (ExecutionException e) {
                log.warn("second tick aio ExecutionException {}", e);
            } catch(Exception e) {
                log.error("general error on processing async operation result {}", e);
            }
        }

        assert aioFutures.size() == aioOrigins.size();
    }

    @Override
    public String toString() {
        return "Session";
    }

    public Hash getUserAgent() { return settings.userAgent; }
    public int getClientId() { return clientId; }
    public boolean isLowId() { return Utils.isLowId(clientId); }
    public String getUpnpStatus() { return upnpStatus; }
    public int getListenPort() { return settings.listenPort; }
    public String getClientName() { return settings.clientName; }
    public String getModName() { return settings.modName; }
    public int getAppVersion() { return settings.version; }
    public int getCompressionVersion() { return settings.compressionVersion; }
    public int getModMajorVersion() { return settings.modMajor; }
    public int getModMinorVersion() { return settings.modMinor; }
    public int getModBuildVersion() { return settings.modBuild; }

    /**
     * thread safe
     * @return status of session
     */
    public final boolean isFinished() {
        return finished.get();
    }

    /**
     * stop main session cycle
     * guarantees all previous commands were completed
     */
    public void abort() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                aborted = true;
            }
        });
    }

    /**
     * save resume data on all transfers needs to save resume data
     */
    public void saveResumeData() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                for(final Transfer t: transfers.values()) {
                    if (t.isNeedSaveResumeData()) {
                        try {
                            AddTransferParams atp = new AddTransferParams(t.getHash(), t.getCreateTime(), t.size(), t.getFile(), t.isPaused() && !t.isAutoPaused());
                            atp.resumeData.setData(t.resumeData());
                            pushAlert(new TransferResumeDataAlert(t.getHash(), atp));
                        } catch(JED2KException e) {
                            log.error("prepare resume data for {} failed {}", t.getHash(), e);
                        }
                    }
                }
            }
        });
    }

    synchronized  public Pair<Long, Long> getDownloadUploadRate() {
        long dr = accumulator.downloadRate();
        long ur = accumulator.uploadRate();
        return Pair.make(dr, ur);
    }

    public void startUPnP() throws JED2KException {
        try {
            if (upnpService.isShutdown()) return;

            upnpService.submit(new Runnable() {

                @Override
                public void run() {
                    assert discover != null;
                    BaseErrorCode ec = ErrorCode.NO_ERROR;
                    // TODO - fix unsynchronized access to settings
                    int port = settings.listenPort;
                    try {
                        discover.discover();
                        device = discover.getValidGateway();
                        if (device != null) {
                            final String[] protocols = {"TCP", "UDP"};
                            for (final String protocol : protocols) {
                                PortMappingEntry portMapping = new PortMappingEntry();
                                if (!device.getSpecificPortMappingEntry(port, protocol, portMapping)) {
                                    InetAddress localAddress = device.getLocalAddress();
                                    if (device.addPortMapping(port, port, localAddress.getHostAddress(), protocol, "JED2K")) {
                                        // ok, mapping added
                                        log.info("[session] port mapped {} for {}", port, protocol);
                                    } else {
                                        log.info("[session] port {} mapping error for {}", port, protocol);
                                        ec = ErrorCode.PORT_MAPPING_ERROR;
                                    }
                                } else {
                                    log.debug("[session] port {} already mapped for {}", port, protocol);
                                    ec = ErrorCode.PORT_MAPPING_ALREADY_MAPPED;
                                }
                            }
                        } else {
                            log.debug("[session] can not find gateway device");
                            ec = ErrorCode.PORT_MAPPING_NO_DEVICE;
                        }
                    } catch (IOException e) {
                        log.error("error", e);
                        ec = ErrorCode.PORT_MAPPING_IO_ERROR;
                    } catch (SAXException e) {
                        log.error("error", e);
                        ec = ErrorCode.PORT_MAPPING_SAX_ERROR;
                    } catch (ParserConfigurationException e) {
                        log.error("error", e);
                        ec = ErrorCode.PORT_MAPPING_CONFIG_ERROR;
                    } catch (Exception e) {
                        log.error("error", e);
                        ec = ErrorCode.PORT_MAPPING_EXCEPTION;
                    }

                    pushAlert(new PortMapAlert(port, port, ec));
                    if (ec == ErrorCode.NO_ERROR || ec == ErrorCode.PORT_MAPPING_ALREADY_MAPPED) {
                        upnpStatus = "ok";
                    } else if (ec == ErrorCode.PORT_MAPPING_NO_DEVICE) {
                        upnpStatus = "no device";
                    } else {
                        upnpStatus = "error";
                    }
                }
            });
        } catch(RejectedExecutionException e) {
            throw new JED2KException(ErrorCode.PORT_MAPPING_COMMAND_REJECTED);
        }

        // router port-mapping leases expire - renew the mapping periodically
        // while the session lives (dies with serverQueryService on stop)
        if (serverQueryService != null && !upnpRenewalScheduled) {
            upnpRenewalScheduled = true;
            serverQueryService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    if (!settings.autoUPnP || upnpService.isShutdown()
                            || serverQueryService.isShutdown()) return;
                    try {
                        startUPnP();
                        log.debug("[session] scheduled UPnP mapping renewal");
                    } catch(JED2KException e) {
                        log.warn("[session] UPnP renewal failed {}", e);
                    }
                }
            }, 30, 30, TimeUnit.MINUTES);
        }
    }

    public void stopUPnP() throws JED2KException {
        try {
            if (upnpService.isShutdown()) return;
            upnpService.submit(new Runnable() {
                @Override
                public void run() {
                    stopUPnPImpl("TCP");
                    stopUPnPImpl("UDP");
                    device = null;
                    upnpStatus = "off";
                }
            });
        } catch(RejectedExecutionException e) {
            throw new JED2KException(ErrorCode.PORT_MAPPING_COMMAND_REJECTED);
        }
    }

    private void stopUPnPImpl(final String protocol) {
        if (device != null) {
            try {
                if (device.deletePortMapping(settings.listenPort, protocol)) {
                    log.info("port mapping removed {}", settings.listenPort);
                } else {
                    log.error("port mapping removing failed");
                }
            } catch (IOException e) {
                log.error("error", e);
                log.error("[session] unmap port I/O error {}", e);
            } catch (SAXException e) {
                log.error("error", e);
                log.error("[session] unmap port SAX error {}", e);
            }
            catch(Exception e) {
                log.error("error", e);
                log.error("[session] unmap port error {}", e);
            }
        }
    }

    /**
     * debug only method to run search source from external process
     * @param h getHash of file
     * @param size size of file
     */
    public synchronized void dhtDebugSearch(final Hash h, long size) {
        try {
            DhtTracker tracker = dhtTracker.get();
            if (tracker != null) {
                tracker.searchSources(h, size, new DhtDebugCallback());
            } else {
                log.warn("[session] DHT is not running, but search sources requested");
            }
        } catch(JED2KException e) {
            log.error("[session] unable to start debug search {}", e);
        }
    }

    /**
     * add or remove DHT tracker from session
     * @param tracker external DHT tracker object or null
     */
    public synchronized void setDhtTracker(final DhtTracker tracker) {
        dhtTracker = new WeakReference<DhtTracker>(tracker);
    }

    public synchronized DhtTracker getDhtTracker() {
        return dhtTracker.get();
    }

    /**
     * Periodically query all known servers for sources.
     * Keeps the server source list fresh for new downloads.
     */
    void queryAllKnownServersForSources() {
        if (knownServers.isEmpty() || udpConnection == null) return;
        // snapshot: this runs on the scheduler thread while the session
        // thread mutates transfers - never iterate the live map here
        for (Map.Entry<Hash, Transfer> entry : new ArrayList<>(transfers.entrySet())) {
            final Hash h = entry.getKey();
            final long size = entry.getValue().size();
            sendMultiServerSourcesRequest(h, size);
        }
    }

    /**
     * resume a transfer that was auto-paused by a transient disk i/o error,
     * after a short delay so we do not hammer the disk repeatedly
     */
    void scheduleTransferResume(final Hash h, long delaySeconds) {
        if (serverQueryService.isShutdown()) return;
        try {
            serverQueryService.schedule(new Runnable() {
                @Override
                public void run() {
                    commands.add(new Runnable() {
                        @Override
                        public void run() {
                            Transfer t = transfers.get(h);
                            if (t != null && t.isPaused() && t.isAutoPaused()) {
                                t.resume();
                                log.info("[session] auto-resumed transfer {} after transient disk error", h);
                            }
                        }
                    });
                }
            }, delaySeconds, TimeUnit.SECONDS);
        } catch(java.util.concurrent.RejectedExecutionException e) {
            log.warn("[session] unable to schedule auto-resume, executor stopped");
        }
    }

    /**
     * pick next known server (excluding the failed one) for reconnect fallback
     */
    private InetSocketAddress pickKnownServerFallback() {
        synchronized (knownServers) {
            if (knownServers.isEmpty()) return null;
            InetSocketAddress failed = serverConnectionPolicy.getAddress();
            for (int i = 0; i < knownServers.size(); ++i) {
                int idx = (fallbackCursor + i) % knownServers.size();
                InetSocketAddress candidate = knownServers.get(idx);
                if (failed != null && failed.equals(candidate)) continue;
                fallbackCursor = (idx + 1) % knownServers.size();
                return candidate;
            }
            return null;
        }
    }

}
