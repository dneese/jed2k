package org.dkf.jed2k;


import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.hash.MD4;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.PacketCombiner;
import org.dkf.jed2k.protocol.PacketHeader;
import org.dkf.jed2k.protocol.Serializable;
import org.dkf.jed2k.protocol.server.FoundFileSources;
import org.dkf.jed2k.protocol.server.GetFileSources;
import static org.dkf.jed2k.Utils.isLowId;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.LinkedList;

/**
 *
 * @author apavlov
 *
 */
public class UDPConnection {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(UDPConnection.class);
    private ByteBuffer bufferIncoming;
    private ByteBuffer bufferOutgoing;
    private LinkedList<Pair<Serializable, Endpoint>> outgoingOrder =
            new LinkedList<Pair<Serializable, Endpoint> >();

    private SelectionKey key = null;
    private Statistics stat = new Statistics();
    final Session session;
    DatagramChannel channel;
    private final PacketCombiner packetCombainer = new org.dkf.jed2k.protocol.server.PacketCombiner();  // temp code

    public UDPConnection(final Session session) {
        this.session = session;
        try {
            bufferIncoming = ByteBuffer.allocate(4096);
            bufferOutgoing = ByteBuffer.allocate(4096);
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            key = channel.register(session.selector, SelectionKey.OP_READ, this);
        } catch(ClosedChannelException e) {
            log.error("[udp] closed channel exception {}", e.getMessage());
        } catch(IOException e) {
            log.error("[udp] i/o exception {}", e.getMessage());
        }
    }

    public void close() {
        try {
            if (key != null) key.cancel();
            if (channel != null) channel.close();
        } catch (IOException e) {
            log.error("[udp] channel close exception {}", e.getMessage());
        }
    }

    public void onReadable() throws JED2KException {
        if (channel == null || !channel.isOpen()) return;
        while (true) {
            bufferIncoming.clear();
            SocketAddress sender;
            try {
                sender = channel.receive(bufferIncoming);
            } catch (IOException e) {
                log.warn("[udp] receive error: {}", e.getMessage());
                return;
            }

            if (sender == null || bufferIncoming.position() == 0) break;

            try {
                bufferIncoming.flip();

                stat.receiveBytes(bufferIncoming.remaining(), 0);
                PacketHeader header = new PacketHeader();
                header.get(bufferIncoming);

                // manually parse FoundFileSources from UDP response
                if (bufferIncoming.remaining() >= MD4.HASH_SIZE + 1) {
                    Hash fileHash = new Hash();
                    fileHash.get(bufferIncoming);
                    int count = bufferIncoming.get() & 0xFF;

                    Transfer t = session.findTransferDirect(fileHash);
                    if (t != null) {
                        log.debug("[udp] received {} sources for {}", count, fileHash);
                        int parsed = 0;
                        for (int i = 0; i < count && bufferIncoming.remaining() >= 6 && parsed < 64; i++) {
                            int ip = bufferIncoming.getInt();
                            int port = bufferIncoming.getShort() & 0xFFFF;
                            parsed++;
                            if (!Utils.isLowId(ip)) {
                                try {
                                    t.addPeer(new Endpoint(ip, port), PeerInfo.SERVER);
                                } catch(JED2KException e) {
                                    // skip unreachable
                                }
                            }
                        }
                    }
                }
            } catch(Throwable e) {
                // never let a malformed datagram disturb the udp socket or the session
                log.debug("[udp] failed to parse response: {}", e.getMessage());
            }
        }
    }

    public void onWriteable() {
        if (channel == null || !channel.isOpen() || key == null) return;
        try {
            bufferOutgoing.clear();
            Pair<Serializable, Endpoint> point = outgoingOrder.poll();

            if (point != null) {
                if (!packetCombainer.pack(point.left, bufferOutgoing)) throw new JED2KException(ErrorCode.FAIL);
                bufferOutgoing.flip();
                stat.sendBytes(bufferOutgoing.remaining(), 0);
                channel.write(bufferOutgoing);
            }
            else {
                key.interestOps(SelectionKey.OP_READ);
            }

            return;
        }
        catch(JED2KException e) {
            log.warn("[udp writeable] jed2k error {}", e);
            assert(false);
        } catch (IOException e) {
            log.warn("[udp writeable] i/o error {}", e);
        }

        // never kill the whole UDP socket on a transient send failure -
        // it would silently disable all UDP source discovery forever.
        // drop write interest and stay alive for reads and future sends
        try {
            if (key != null && key.isValid()) key.interestOps(SelectionKey.OP_READ);
        } catch(Exception e) {
            log.warn("[udp writeable] failed to reset interest ops {}", e.getMessage());
        }
    }

    /**
     * Send UDP source request to server
     */
    public void sendSourcesRequest(Hash fileHash, long size, InetSocketAddress serverAddr) {
        if (channel == null || !channel.isOpen()) return;
        try {
            long hi = size >>> 32;
            long lo = size & 0xFFFFFFFF;
            GetFileSources request = new GetFileSources(fileHash, (int)hi, (int)lo);
            ByteBuffer buf = ByteBuffer.allocate(1024);
            if (packetCombainer.pack(request, buf)) {
                buf.flip();
                channel.send(buf, serverAddr);
                stat.sendBytes(buf.remaining(), 0);
                log.debug("[udp] sent sources request for {} to {}", fileHash, serverAddr);
            }
        } catch(IOException e) {
            log.warn("[udp] failed to send sources request: {}", e.getMessage());
        } catch(JED2KException e) {
            log.warn("[udp] failed to pack sources request: {}", e.getMessage());
        }
    }

    public Statistics getStatistics() {
        return stat;
    }
}
