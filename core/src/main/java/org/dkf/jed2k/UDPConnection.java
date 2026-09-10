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
            channel.close();
        } catch (IOException e) {
            log.error("[udp] channel close exception {}", e.getMessage());
        }
    }

    public void onReadable() throws JED2KException {
        bufferIncoming.clear();
        try {
            channel.receive(bufferIncoming);
        } catch (IOException e) {
            log.warn("[udp] receive error: {}", e.getMessage());
            return;
        }

        bufferIncoming.flip();
        if (bufferIncoming.remaining() < 1) return;
        
        stat.receiveBytes(bufferIncoming.remaining(), 0);
        PacketHeader header = new PacketHeader();
        header.get(bufferIncoming);
        
        // manually parse FoundFileSources from UDP response
        if (bufferIncoming.remaining() >= MD4.HASH_SIZE + 1) {
            try {
                Hash fileHash = new Hash();
                fileHash.get(bufferIncoming);
                int count = bufferIncoming.get() & 0xFF;
                
                Transfer t = session.findTransferDirect(fileHash);
                if (t != null) {
                    log.debug("[udp] received {} sources for {}", count, fileHash);
                    for (int i = 0; i < count && bufferIncoming.remaining() >= 6; i++) {
                        int ip = bufferIncoming.getInt();
                        int port = bufferIncoming.getShort() & 0xFFFF;
                        if (!Utils.isLowId(ip)) {
                            try {
                                t.addPeer(new Endpoint(ip, port), PeerInfo.SERVER);
                            } catch(JED2KException e) {
                                // skip unreachable
                            }
                        }
                    }
                }
            } catch(Exception e) {
                log.debug("[udp] failed to parse response: {}", e.getMessage());
            }
        }
    }

    public void onWriteable() {
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

        close();
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
