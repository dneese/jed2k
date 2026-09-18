package org.dkf.jed2k;

import org.dkf.jed2k.protocol.Hash;

public class Settings {
    public Hash userAgent = new Hash(Hash.EMULE);
    public String modName = "jed2k";
    public String clientName = "jed2k";
    public int listenPort = 4661;
    public int udpPort = 4662;
    public int version = 0x3c;
    public int modMajor = 0;
    public int modMinor = 0;
    public int modBuild = 0;
    public int maxFailCount = 20;
    public int maxPeerListSize = 100;
    public int minPeerReconnectTime = 5;
    public int peerConnectionTimeout = 30;
    public int sessionConnectionsLimit = 50;
    public int bufferPoolSize = 250;            // dataSize of buffer pool in blocks of 180K
    public int maxConnectionsPerSecond = 25;
    public int compressionVersion = 0;          // use 1 for activate compression
    public int serverSearchTimeout = 15;        // seconds
    public boolean reconnectoToServer = true;  // reconnect to server if connection was closed due to error

    /**
     * send ping message to server every serverPingTimeout seconds
     */
    public long serverPingTimeout = 0;

    /**
     * auto-enable UPnP port mapping at session start
     */
    public boolean autoUPnP = true;

    /**
     * STUN server for external IP detection (fallback when UPnP fails)
     */
    public String stunServer = "stun.l.google.com:19302";

    @Override
    public String toString() {
        return "Settings{" +
                "userAgent=" + userAgent +
                ", modName='" + modName + '\'' +
                ", clientName='" + clientName + '\'' +
                ", listenPort=" + listenPort +
                ", udpPort=" + udpPort +
                ", version=" + version +
                ", modMajor=" + modMajor +
                ", modMinor=" + modMinor +
                ", modBuild=" + modBuild +
                ", maxFailCount=" + maxFailCount +
                ", maxPeerListSize=" + maxPeerListSize +
                ", minPeerReconnectTime=" + minPeerReconnectTime +
                ", peerConnectionTimeout=" + peerConnectionTimeout +
                ", sessionConnectionsLimit=" + sessionConnectionsLimit +
                ", bufferPoolSize=" + bufferPoolSize +
                ", maxConnectionsPerSecond=" + maxConnectionsPerSecond +
                ", compressionVersion=" + compressionVersion +
                ", serverSearchTimeout=" + serverSearchTimeout +
                ", serverPingTimeout=" + serverPingTimeout +
                ", reconnectToServer=" + (reconnectoToServer?"yes":"no") +
                '}';
    }
}
