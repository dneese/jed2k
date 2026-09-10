/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2016, FrostWire(R). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dkf.jmule;

import android.os.Environment;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.server.ServerMet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author gubatron
 * @author aldenml
 */
final class ConfigurationDefaults {

    private final Map<String, Object> defaultValues;
    private final Map<String, Object> resetValues;

    ConfigurationDefaults() {
        defaultValues = new HashMap<>();
        resetValues = new HashMap<>();
        load();
    }

    Map<String, Object> getDefaultValues() {
        return Collections.unmodifiableMap(defaultValues);
    }

    Map<String, Object> getResetValues() {
        return Collections.unmodifiableMap(resetValues);
    }

    private void load() {
        defaultValues.put(Constants.PREF_KEY_STORAGE_PATH, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        defaultValues.put(Constants.PREF_KEY_CORE_UUID, uuidToByteArray(UUID.randomUUID()));
        defaultValues.put(Constants.PREF_KEY_CORE_LAST_SEEN_VERSION, "");//won't know until I see it.

        defaultValues.put(Constants.PREF_KEY_GUI_VIBRATE_ON_FINISHED_DOWNLOAD, true);
        defaultValues.put(Constants.PREF_KEY_GUI_LAST_MEDIA_TYPE_FILTER, Constants.FILE_TYPE_AUDIO);
        defaultValues.put(Constants.PREF_KEY_GUI_TOS_ACCEPTED, false);
        defaultValues.put(Constants.PREF_KEY_GUI_ALREADY_RATED_US_IN_MARKET, false);
        defaultValues.put(Constants.PREF_KEY_GUI_FINISHED_DOWNLOADS_BETWEEN_RATINGS_REMINDER, 10);
        defaultValues.put(Constants.PREF_KEY_GUI_INITIAL_SETTINGS_COMPLETE, false);
        defaultValues.put(Constants.PREF_KEY_GUI_ENABLE_PERMANENT_STATUS_NOTIFICATION, true);
        defaultValues.put(Constants.PREF_KEY_GUI_SHOW_TRANSFERS_ON_DOWNLOAD_START, true);
        defaultValues.put(Constants.PREF_KEY_GUI_SHOW_NEW_TRANSFER_DIALOG, true);
        defaultValues.put(Constants.PREF_KEY_GUI_SAFE_MODE, true);
        defaultValues.put(Constants.PREF_KEY_GUI_ALERTED_SAFE_MODE, false);
        defaultValues.put(Constants.PREF_KEY_GUI_SHARE_MEDIA_DOWNLOADS, false);

        defaultValues.put(Constants.PREF_KEY_SEARCH_COUNT_DOWNLOAD_FOR_TORRENT_DEEP_SCAN, 20);
        defaultValues.put(Constants.PREF_KEY_SEARCH_COUNT_ROUNDS_FOR_TORRENT_DEEP_SCAN, 10);
        defaultValues.put(Constants.PREF_KEY_SEARCH_INTERVAL_MS_FOR_TORRENT_DEEP_SCAN, 2000);
        defaultValues.put(Constants.PREF_KEY_SEARCH_MIN_SEEDS_FOR_TORRENT_DEEP_SCAN, 20); // this number must be bigger than PREF_KEY_SEARCH_MIN_SEEDS_FOR_TORRENT_RESULT to become relevant
        defaultValues.put(Constants.PREF_KEY_SEARCH_MIN_SEEDS_FOR_TORRENT_RESULT, 20);
        defaultValues.put(Constants.PREF_KEY_SEARCH_MAX_TORRENT_FILES_TO_INDEX, 100); // no ultra big torrents here
        defaultValues.put(Constants.PREF_KEY_SEARCH_FULLTEXT_SEARCH_RESULTS_LIMIT, 256);

        defaultValues.put(Constants.PREF_KEY_NETWORK_USE_MOBILE_DATA, true);
        defaultValues.put(Constants.PREF_KEY_NETWORK_USE_WIFI_ONLY, false);
        defaultValues.put(Constants.PREF_KEY_NETWORK_BITTORRENT_ON_VPN_ONLY, false);
        defaultValues.put(Constants.PREF_KEY_NETWORK_MAX_CONCURRENT_UPLOADS, 3);


        defaultValues.put(Constants.PREF_KEY_STORAGE_PATH, Environment.getExternalStorageDirectory().getAbsolutePath()); // /mnt/sdcard

        resetValue(Constants.PREF_KEY_SEARCH_COUNT_DOWNLOAD_FOR_TORRENT_DEEP_SCAN);
        resetValue(Constants.PREF_KEY_SEARCH_COUNT_ROUNDS_FOR_TORRENT_DEEP_SCAN);
        resetValue(Constants.PREF_KEY_SEARCH_INTERVAL_MS_FOR_TORRENT_DEEP_SCAN);
        resetValue(Constants.PREF_KEY_SEARCH_MIN_SEEDS_FOR_TORRENT_DEEP_SCAN);
        resetValue(Constants.PREF_KEY_SEARCH_MIN_SEEDS_FOR_TORRENT_RESULT);
        resetValue(Constants.PREF_KEY_SEARCH_MAX_TORRENT_FILES_TO_INDEX);
        resetValue(Constants.PREF_KEY_SEARCH_FULLTEXT_SEARCH_RESULTS_LIMIT);


        defaultValues.put(Constants.PREF_KEY_NICKNAME, "Nickname");
        defaultValues.put(Constants.PREF_KEY_LISTEN_PORT, 4661l);
        defaultValues.put(Constants.PREF_KEY_TRANSFER_MAX_TOTAL_CONNECTIONS, 200l);
        defaultValues.put(Constants.PREF_KEY_CONN_SERVER_ON_START, false);
        defaultValues.put(Constants.PREF_KEY_RECONNECT_TO_SERVER, true);
        defaultValues.put(Constants.PREF_KEY_PING_SERVER, true);
        defaultValues.put(Constants.PREF_KEY_SHOW_SERVER_MSG, true);
        defaultValues.put(Constants.PREF_KEY_AUTO_START_SERVICE, true);
        defaultValues.put(Constants.PREF_KEY_FORWARD_PORTS, true);

        defaultValues.put(Constants.PREF_KEY_CONNECT_DHT, true);

        // servers section - active eD2K servers, verified 09.2026
        ServerMet sm = new ServerMet();
        try {
            sm.addServer(ServerMet.ServerMetEntry.create("176.123.5.89", 4725, "eMule Sunrise", "Not perfect, but real"));
            sm.addServer(ServerMet.ServerMetEntry.create("77.42.68.79", 4232, "Nordic Server", "FIN Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("91.208.162.87", 4232, "!! Sharing-Devils No.4 !!", "https://forum.sharing-devils.to"));
            sm.addServer(ServerMet.ServerMetEntry.create("85.17.116.222", 6082, "ed2k-rust", "main server"));
            sm.addServer(ServerMet.ServerMetEntry.create("91.208.162.182", 4232, "MO-Server", "!NFO-Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("45.82.80.155", 5687, "eMule Security", "www.emule-security.org"));
            sm.addServer(ServerMet.ServerMetEntry.create("85.121.5.137", 4232, "!! Sharing-Devils No.2 !!", "https://forum.sharing-devils.to"));
            sm.addServer(ServerMet.ServerMetEntry.create("212.95.35.240", 4232, "eMule Cosmic", "We are not alone"));
            sm.addServer(ServerMet.ServerMetEntry.create("213.141.198.207", 4232, "Mazinga Server", "Mazinga High-performance eDonkey server"));
            sm.addServer(ServerMet.ServerMetEntry.create("213.252.245.239", 43333, "Astra-3", "Astra-3 Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("185.25.48.89", 18357, "Akteon Server", "Akteon Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("213.252.245.239", 33333, "Astra-5", "Astra-5 Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("185.237.185.226", 31031, "Gaal", "Gaal Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("57.131.35.107", 4232, "MO-ad-free", "IT-Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("141.227.165.99", 4232, "MO-ad-free", "AT-Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("193.187.90.12", 4661, "Drunken Donkey", "Everything or Nothing"));
            sm.addServer(ServerMet.ServerMetEntry.create("95.217.134.86", 22888, "Astra-2", "Astra-2 Server"));
            sm.addServer(ServerMet.ServerMetEntry.create("92.38.163.210", 35037, "Astra-6", "Astra-6 Server"));
            defaultValues.put(Constants.PREF_KEY_SERVERS_LIST, sm);
        } catch(JED2KException e) {
            // wtf?
        }

        defaultValues.put(Constants.PREF_KEY_USER_AGENT, Hash.random(true).toString());
    }

    private void resetValue(String key) {
        resetValues.put(key, defaultValues.get(key));
    }

    private static byte[] uuidToByteArray(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] buffer = new byte[16];

        for (int i = 0; i < 8; i++) {
            buffer[i] = (byte) (msb >>> 8 * (7 - i));
        }
        for (int i = 8; i < 16; i++) {
            buffer[i] = (byte) (lsb >>> 8 * (7 - i));
        }

        return buffer;
    }
}
