package org.dkf.jed2k.protocol.client;

import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.hash.MD4;
import org.dkf.jed2k.protocol.Dispatchable;
import org.dkf.jed2k.protocol.Dispatcher;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.Serializable;

import java.nio.ByteBuffer;

/**
 * OP_REQUESTSOURCES2 (0x83): client requests sources for a file (v2 with source flags).
 * Format: <HASH 16>
 */
public class RequestSources2 implements Serializable, Dispatchable {
    public Hash fileHash = new Hash();

    public RequestSources2() {}

    public RequestSources2(Hash h) {
        this.fileHash = h;
    }

    @Override
    public ByteBuffer get(ByteBuffer src) throws JED2KException {
        fileHash.get(src);
        return src;
    }

    @Override
    public ByteBuffer put(ByteBuffer dst) throws JED2KException {
        return fileHash.put(dst);
    }

    @Override
    public int bytesCount() {
        return MD4.HASH_SIZE;
    }

    @Override
    public void dispatch(Dispatcher dispatcher) throws JED2KException {
        dispatcher.onRequestSources2(this);
    }
}
