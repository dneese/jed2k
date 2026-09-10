package org.dkf.jed2k.protocol.client;

import org.dkf.jed2k.Utils;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.hash.MD4;
import org.dkf.jed2k.protocol.Dispatchable;
import org.dkf.jed2k.protocol.Dispatcher;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.Serializable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * OP_ANSWERSOURCES2 (0x84): client answers with sources for a requested file (v2 with source flags).
 * Format: <HASH 16><count 2>(<ID 4><PORT 2><FLAGS 1>)[count]
 */
public class AnswerSources2 implements Serializable, Dispatchable {
    public Hash fileHash = new Hash();
    public List<Endpoint> sources = new ArrayList<>();
    public List<Integer> sourceFlags = new ArrayList<>();

    @Override
    public ByteBuffer get(ByteBuffer src) throws JED2KException {
        src.order(ByteOrder.BIG_ENDIAN);
        fileHash.get(src);
        int count = src.getShort() & 0xFFFF;
        sources.clear();
        sourceFlags.clear();
        for (int i = 0; i < count && src.remaining() >= 7; i++) {
            int ip = src.getInt();
            int port = src.getShort() & 0xFFFF;
            int flags = src.get() & 0xFF;
            if (!Utils.isLowId(ip)) {
                sources.add(new Endpoint(ip, port));
                sourceFlags.add(flags);
            }
        }
        return src;
    }

    @Override
    public ByteBuffer put(ByteBuffer dst) throws JED2KException {
        dst.order(ByteOrder.BIG_ENDIAN);
        fileHash.put(dst);
        dst.putShort((short) sources.size());
        for (int i = 0; i < sources.size(); i++) {
            Endpoint ep = sources.get(i);
            dst.putInt(ep.getIP());
            dst.putShort((short) ep.getPort());
            dst.put((byte) (sourceFlags.size() > i ? sourceFlags.get(i) : 0));
        }
        return dst;
    }

    @Override
    public int bytesCount() {
        return MD4.HASH_SIZE + 2 + sources.size() * 7;
    }

    @Override
    public void dispatch(Dispatcher dispatcher) throws JED2KException {
        dispatcher.onAnswerSources2(this);
    }
}
