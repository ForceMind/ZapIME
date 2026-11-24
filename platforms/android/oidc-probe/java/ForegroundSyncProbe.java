// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Prepares synthetic state and verifies durable results. The UI starts the sync. */
final class ForegroundSyncProbe {
    private static final UUID OPERATION = UUID.fromString("7107125f-32d0-40a0-bd83-a77d8b166f90");
    private static final UUID RECORD = UUID.fromString("000d046e-1a0e-42a0-9ce8-fa42a626d0ce");
    private static final UUID RECOVERY_OPERATION = UUID.fromString("cec2bc8b-b259-41a8-bb70-7e92a5d96042");
    private static final UUID RECOVERY_RECORD = UUID.fromString("a4208deb-74bb-4b53-984a-07faa0832711");
    private static final UUID NETWORK_RECORD = UUID.fromString("22b328d3-b4b7-4802-8de1-d83ee8d78a32");
    private static final UUID NETWORK_OPERATION = UUID.fromString("1ccfb101-a40d-4c11-aff1-17dfc8a7891e");
    private static final UUID CONFLICT_OPERATION = UUID.fromString("3bc90113-5fe3-4b79-8efc-705ab851d55d");
    private static final String LOCAL_EDIT = "synthetic A3 conflicting device edit";

    static void readDefaultNetwork(Context context, Bundle result) throws Exception {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        require(manager != null);
        CountDownLatch available = new CountDownLatch(1);
        AtomicReference<Network> callbackNetwork = new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                callbackNetwork.set(network);
                available.countDown();
            }
        };
        manager.registerDefaultNetworkCallback(callback);
        try {
            available.await(8, TimeUnit.SECONDS);
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            Set<String> defaults = new LinkedHashSet<>();
            Set<String> networks = new LinkedHashSet<>();
            collectTransports(capabilities, defaults);
            for (Network network : manager.getAllNetworks()) collectTransports(manager.getNetworkCapabilities(network), networks);
            Network observed = callbackNetwork.get();
            result.putString("network_callback", observed == null ? "no_event"
                    : observed.equals(active) ? "matches_default" : "changed_during_read");
            result.putString("network_default", active == null ? "none"
                    : defaults.isEmpty() ? "unknown" : String.join(",", defaults));
            result.putString("network_available", networks.isEmpty() ? "none" : String.join(",", networks));
            result.putString("network_validated", capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "true" : "false");
        } finally {
            manager.unregisterNetworkCallback(callback);
        }
    }

    private static void collectTransports(NetworkCapabilities capabilities, Set<String> transports) {
        if (capabilities == null) return;
        boolean known = false;
        int[] kinds = {NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN};
        String[] names = {"wifi", "cellular", "ethernet", "vpn"};
        for (int index = 0; index < kinds.length; index++) {
            if (capabilities.hasTransport(kinds[index])) {
                transports.add(names[index]);
                known = true;
            }
        }
        if (!known) transports.add("other");
    }

    static void seed(Context context, AccountLoginProtocol.Config config, byte[] seed) throws Exception {
        File root = new File(context.getFilesDir(), "zapime");
        require(!root.exists() && SyncConnectionStore.load(context) == null && SyncConsentStore.load(context).account == null);
        JSONObject input = new JSONObject(new String(seed, StandardCharsets.UTF_8));
        byte[] material = Base64.getDecoder().decode(input.getString("material"));
        try (SyncConnection candidate = SyncConnection.imported(config.syncServer.toString(),
                input.getJSONObject("device").toString())) {
            String account = candidate.account.toString();
            SyncHttp.Cancellation cancel = new SyncHttp.Cancellation();
            byte[] wire = candidate.inspect(cancel);
            try (SyncConnection connection = candidate.verify(wire)) {
                require("owner".equals(connection.role));
                ResourceInstaller.prepare(context);
                long core = NativeCore.open(root.getAbsolutePath(), false);
                require(core != 0);
                try {
                    require(SyncSecrets.saveIfAbsent(context, connection.account, material));
                    require(new JSONObject(NativeCore.prepareSync(root.getAbsolutePath(), account, material)).getBoolean("prepared"));
                    SyncConnectionStore.save(context, connection);
                    SyncEnrollmentVault.Scope scope = new SyncEnrollmentVault.Scope(config, connection.account,
                            input.getString("subject_fingerprint"));
                    SyncConsentStore.State signIn = SyncConsentStore.beginLogin(context, cancel);
                    require(SyncConsentStore.completeLogin(context, signIn.generation, scope, connection.device, cancel).expectedKey == null);
                    edit(core, RECORD, OPERATION, 0, "a3sync", "synthetic A3 automatic shortcut");
                } finally {
                    NativeCore.close(core);
                }
            } finally {
                Arrays.fill(wire, (byte) 0);
            }
        } finally {
            Arrays.fill(material, (byte) 0);
        }
        verify(context, false);
    }

    static void verify(Context context, boolean acknowledged) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account != null && (consent.expectedKey != null) == acknowledged);
        checkReceipt(context, RECORD, OPERATION, acknowledged ? 1 : 0);
    }

    static void queueRecovery(Context context) throws Exception {
        verify(context, true);
        long core = NativeCore.open(new File(context.getFilesDir(), "zapime").getAbsolutePath(), false);
        require(core != 0);
        try {
            edit(core, RECOVERY_RECORD, RECOVERY_OPERATION, 0, "a3back", "synthetic A3 recovered shortcut");
        } finally {
            NativeCore.close(core);
        }
        verifyRecovery(context, false);
    }

    static void verifyRecovery(Context context, boolean acknowledged) throws Exception {
        verify(context, true);
        checkRecovery(context, acknowledged);
    }

    static void queueConflict(Context context) throws Exception {
        verifyNetworkRecovery(context, true);
        long core = NativeCore.open(new File(context.getFilesDir(), "zapime").getAbsolutePath(), false);
        require(core != 0);
        try {
            edit(core, RECOVERY_RECORD, CONFLICT_OPERATION, 1, "a3back", LOCAL_EDIT);
        } finally {
            NativeCore.close(core);
        }
        checkConflict(context, false);
    }

    static void verifyConflict(Context context) throws Exception {
        verifyNetworkRecovery(context, true);
        checkConflict(context, true);
    }

    static void queueNetworkRecovery(Context context) throws Exception {
        verifyRecovery(context, true);
        long core = NativeCore.open(new File(context.getFilesDir(), "zapime").getAbsolutePath(), false);
        require(core != 0);
        try {
            edit(core, NETWORK_RECORD, NETWORK_OPERATION, 0, "a3wifi", "synthetic A3 default network shortcut");
        } finally {
            NativeCore.close(core);
        }
        verifyNetworkRecovery(context, false);
    }

    static void verifyNetworkRecovery(Context context, boolean acknowledged) throws Exception {
        verify(context, true);
        checkReceipt(context, RECOVERY_RECORD, RECOVERY_OPERATION, 2);
        checkReceipt(context, NETWORK_RECORD, NETWORK_OPERATION, acknowledged ? 3 : 0);
        checkCursor(context, acknowledged ? 3 : 2);
    }

    private static void edit(long core, UUID record, UUID operation, long expected, String code, String text) throws Exception {
        JSONObject content = new JSONObject().put("type", "snippet").put("code", code)
                .put("text", text).put("tags", new JSONArray());
        JSONObject command = new JSONObject().put("op", "personal_edit").put("id", record.toString())
                .put("operation_id", operation.toString()).put("expected_revision", expected).put("content", content);
        require(!new JSONObject(NativeCore.request(core, command.toString())).has("error"));
    }

    static void signedOut(Context context) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account == null && consent.device == null && consent.expectedKey == null);
        try (SyncConnection connection = SyncConnectionStore.load(context)) {
            require(connection != null);
            byte[] material = SyncSecrets.load(context, connection.account);
            require(material != null);
            Arrays.fill(material, (byte) 0);
        }
        checkReceipt(context, RECORD, OPERATION, 1);
        checkReceipt(context, RECOVERY_RECORD, RECOVERY_OPERATION, 2);
        checkReceipt(context, NETWORK_RECORD, NETWORK_OPERATION, 3);
        checkCursor(context, 3);
        checkConflict(context, true);
    }

    private static void checkConflict(Context context, boolean retained) throws Exception {
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor local = db.rawQuery("SELECT r.revision,r.body,o.pending,u.operation_id FROM records r "
                        + "JOIN operations o ON o.id=? LEFT JOIN sync_uploads u ON u.operation_id=o.id WHERE r.id=?",
                        new String[]{CONFLICT_OPERATION.toString(), RECOVERY_RECORD.toString()});
                Cursor failed = db.rawQuery("SELECT after_cursor,reason,wire FROM sync_retained_pulls", null)) {
            require(local.moveToFirst() && local.getInt(0) == 2 && local.getInt(2) == 1 && local.isNull(3));
            require(LOCAL_EDIT.equals(new JSONObject(local.getString(1)).getJSONObject("content").getString("text")));
            if (retained) {
                require(failed.moveToFirst() && failed.getInt(0) == 3 && "\"local_conflict\"".equals(failed.getString(1))
                        && new JSONObject(failed.getString(2)).getInt("head") == 4 && !failed.moveToNext());
            } else {
                require(!failed.moveToFirst());
            }
        }
    }

    private static void checkRecovery(Context context, boolean acknowledged) throws Exception {
        checkReceipt(context, RECOVERY_RECORD, RECOVERY_OPERATION, acknowledged ? 2 : 0);
        checkCursor(context, acknowledged ? 2 : 1);
    }

    private static void checkCursor(Context context, int revision) throws Exception {
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor row = db.rawQuery("SELECT value FROM metadata WHERE key='sync_cursor'", null)) {
            require(row.moveToFirst() && row.getInt(0) == revision);
        }
    }

    private static void checkReceipt(Context context, UUID record, UUID operation, int revision) throws Exception {
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor row = db.rawQuery("SELECT o.pending,u.ack_revision,v.server_revision "
                        + "FROM operations o LEFT JOIN sync_uploads u ON u.operation_id=o.id "
                        + "LEFT JOIN sync_versions v ON v.record_id=? WHERE o.id=?",
                        new String[]{record.toString(), operation.toString()})) {
            require(row.moveToFirst() && row.getInt(0) == (revision == 0 ? 1 : 0));
            require(revision == 0 ? row.isNull(1) && row.isNull(2) : row.getInt(1) == revision && row.getInt(2) == revision);
        }
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("foreground_fixture_assertion");
    }

    private ForegroundSyncProbe() {}
}
