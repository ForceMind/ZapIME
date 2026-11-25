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
import java.security.MessageDigest;
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
    private static final UUID INTERRUPTED_RECORD = UUID.fromString("84c8e285-0d0c-4f5c-8258-ba048d906a29");
    private static final UUID INTERRUPTED_OPERATION = UUID.fromString("8cbfd5d6-dc74-4fbd-b600-08da374eb8f2");
    private static final UUID CONFLICT_OPERATION = UUID.fromString("3bc90113-5fe3-4b79-8efc-705ab851d55d");
    private static final UUID IMPORTED_RECORD = UUID.fromString("f8678ce1-c77a-4f88-957d-f07f98451953");
    private static final UUID IMPORTED_EDIT = UUID.fromString("d6594a60-53c9-4f99-867d-503517826b66");
    private static final String IMPORTED_TEXT = "synthetic A3 automatic shortcut";
    private static final String IMPORTED_EDIT_TEXT = "synthetic A3 locally edited import";
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

    static String seed(Context context, AccountLoginProtocol.Config config, byte[] seed, String mode) throws Exception {
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
                    if ("archive".equals(mode)) {
                        JSONObject command = new JSONObject().put("op", "personal_import").put("archive", input.getString("archive"));
                        require(!new JSONObject(NativeCore.request(core, command.toString())).has("error"));
                    }
                    require(SyncSecrets.saveIfAbsent(context, connection.account, material));
                    require(new JSONObject(NativeCore.prepareSync(root.getAbsolutePath(), account, material)).getBoolean("prepared"));
                    if (!"archive".equals(mode)) {
                        byte[] empty = "{\"changes\":[],\"next_cursor\":0,\"head\":0}".getBytes(StandardCharsets.US_ASCII);
                        JSONObject manual = new JSONObject(NativeCore.applySyncPage(root.getAbsolutePath(), account, material, 0, empty));
                        require(manual.length() == 5 && manual.getLong("cursor") == 0 && manual.getLong("head") == 0
                                && manual.getInt("applied") == 0 && manual.getInt("echoed") == 0 && manual.getInt("unchanged") == 0);
                    }
                    SyncConnectionStore.save(context, connection);
                    SyncEnrollmentVault.Scope scope = new SyncEnrollmentVault.Scope(config, connection.account,
                            input.getString("subject_fingerprint"));
                    SyncConsentStore.State signIn = SyncConsentStore.beginLogin(context, cancel);
                    require(SyncConsentStore.completeLogin(context, signIn.generation, scope, connection.device, cancel).expectedKey == null);
                    if ("queue".equals(mode)) {
                        for (int index = 1; index <= 16; index++) {
                            edit(core, queueRecord(index), queueOperation(index), 0, "burst" + index, "synthetic A3 queue item " + index);
                        }
                        edit(core, queueRecord(1), queueOperation(17), 1, "burst1", "synthetic A3 queue item 1", true);
                        edit(core, queueRecord(18), queueOperation(18), 0, "burst18", "synthetic A3 queue item 18");
                    } else if ("foreground".equals(mode)) {
                        edit(core, RECORD, OPERATION, 0, "a3sync", "synthetic A3 automatic shortcut");
                    }
                } finally {
                    NativeCore.close(core);
                }
            } finally {
                Arrays.fill(wire, (byte) 0);
            }
        } finally {
            Arrays.fill(material, (byte) 0);
        }
        if ("archive".equals(mode)) return verifyArchive(context, false, false);
        if ("queue".equals(mode)) verifyQueue(context, 0);
        else verify(context, false);
        return null;
    }

    static String editArchive(Context context) throws Exception {
        String imported = verifyArchive(context, true, false);
        long core = NativeCore.open(new File(context.getFilesDir(), "zapime").getAbsolutePath(), false);
        require(core != 0);
        try {
            edit(core, IMPORTED_RECORD, IMPORTED_EDIT, 1, "a3sync", IMPORTED_EDIT_TEXT);
            edit(core, RECORD, OPERATION, 0, "a3trusted", "synthetic A3 source verified shortcut");
        } finally {
            NativeCore.close(core);
        }
        verifyArchive(context, true, true);
        return imported;
    }

    static String verifyArchive(Context context, boolean pulled, boolean edited) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account != null && (consent.expectedKey != null) == pulled);
        checkCursor(context, pulled ? 1 : 0);
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor record = db.rawQuery("SELECT r.revision,r.body,COALESCE(v.server_revision,0),p.record_id FROM records r "
                        + "LEFT JOIN sync_versions v ON v.record_id=r.id LEFT JOIN sync_pulled_origins p ON p.record_id=r.id WHERE r.id=?",
                        new String[]{IMPORTED_RECORD.toString()});
                Cursor queue = db.rawQuery("SELECT o.id,o.pending,u.operation_id,s.operation_id,o.result,s.record_id,s.local_revision, "
                        + "s.profile_id=(SELECT value FROM metadata WHERE key='sync_profile') "
                        + "AND s.key_id=(SELECT value FROM metadata WHERE key='sync_key_id') "
                        + "AND s.material_sha256=(SELECT value FROM metadata WHERE key='sync_material_sha256') "
                        + "FROM sync_queue q JOIN operations o ON o.id=q.operation_id "
                        + "LEFT JOIN sync_uploads u ON u.operation_id=o.id LEFT JOIN sync_local_origins s ON s.operation_id=o.id ORDER BY q.sequence", null)) {
            require(record.moveToFirst() && record.getInt(0) == (edited ? 2 : 1)
                    && record.getInt(2) == (pulled ? 1 : 0) && record.isNull(3));
            String snapshot = record.getString(1);
            JSONObject row = new JSONObject(snapshot);
            JSONObject content = row.getJSONObject("content");
            require(!row.getBoolean("deleted") && "snippet".equals(content.getString("type")) && "a3sync".equals(content.getString("code"))
                    && (edited ? IMPORTED_EDIT_TEXT : IMPORTED_TEXT).equals(content.getString("text")));
            if (edited) {
                require(queue.moveToNext() && IMPORTED_EDIT.toString().equals(queue.getString(0))
                        && queue.getInt(1) == 1 && queue.isNull(2) && queue.isNull(3));
                require(queue.moveToNext() && OPERATION.toString().equals(queue.getString(0))
                        && queue.getInt(1) == 1 && queue.isNull(2) && !queue.isNull(3) && queue.getInt(7) == 1);
                JSONObject trusted = new JSONObject(queue.getString(4));
                require(RECORD.toString().equals(trusted.getString("id")) && trusted.getString("id").equals(queue.getString(5))
                        && trusted.getInt("revision") == queue.getInt(6));
            }
            require(!queue.moveToNext());
            return fingerprint(snapshot.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static UUID queueRecord(int index) {
        return new UUID(0x96abb4f18f2c4bb3L, Long.MIN_VALUE | index);
    }

    private static UUID queueOperation(int index) {
        return new UUID(0x324b1dfaba2b4ed3L, Long.MIN_VALUE | index);
    }

    static void verifyQueue(Context context, int acknowledged) throws Exception {
        require(acknowledged == 0 || acknowledged == 16 || acknowledged == 18);
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account != null && (consent.expectedKey != null) == (acknowledged > 0));
        checkCursor(context, acknowledged);
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor rows = db.rawQuery("SELECT o.id,o.pending,u.ack_revision,u.wire FROM sync_queue q "
                        + "JOIN operations o ON o.id=q.operation_id LEFT JOIN sync_uploads u ON u.operation_id=o.id "
                        + "ORDER BY q.sequence", null);
                Cursor first = db.rawQuery("SELECT r.revision,r.body,COALESCE(v.server_revision,0) FROM records r "
                        + "LEFT JOIN sync_versions v ON v.record_id=r.id WHERE r.id=?", new String[]{queueRecord(1).toString()})) {
            for (int index = 1; index <= 18; index++) {
                require(rows.moveToNext() && queueOperation(index).toString().equals(rows.getString(0)));
                if (index <= acknowledged) {
                    require(rows.getInt(1) == 0 && rows.getInt(2) == index && !rows.isNull(3));
                } else {
                    require(rows.getInt(1) == 1 && rows.isNull(2) && rows.isNull(3));
                }
            }
            require(!rows.moveToNext() && first.moveToFirst() && first.getInt(0) == 2);
            require(new JSONObject(first.getString(1)).getBoolean("deleted"));
            require(first.getInt(2) == (acknowledged == 18 ? 17 : acknowledged == 16 ? 1 : 0));
        }
    }

    static void verify(Context context, boolean acknowledged) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account != null && (consent.expectedKey != null) == acknowledged);
        checkReceipt(context, RECORD, OPERATION, acknowledged ? 1 : 0);
    }

    static void verifyAutomaticLogin(Context context, boolean ready) throws Exception {
        SyncConsentStore.State state = SyncConsentStore.load(context);
        require(state.account != null && state.device != null && state.expectedKey != null);
        SyncEnrollmentVault.Scope scope = state.verifiedDeployment(AccountLoginDeployment.config());
        SyncHttp.Cancellation cancel = new SyncHttp.Cancellation();
        try (SyncConnection saved = SyncConnectionStore.load(context);
                SyncEnrollmentVault.Pending installed = SyncEnrollmentVault.loadAutomatic(context, scope)) {
            require(saved != null && installed != null && state.matches(scope, saved.device) && "member".equals(saved.role));
            char[] token = installed.credential();
            try {
                require(saved.matchesEnrollment(scope.config.syncServer, scope.account, installed.id, token));
            } finally {
                Arrays.fill(token, '\0');
            }
            SyncPreparation.State local = SyncPreparation.inspect(context);
            require(local.initialized && local.prepared == ready && local.materialPresent == ready);
            if (ready) {
                require(state.expectedKey.same(SyncKeyRegistration.preparedLocal(context, saved, cancel)));
                checkCursor(context, 0);
            } else {
                require(local.account == null && local.keyId == null);
            }
            byte[] candidate = SyncSecrets.loadInitialKey(context, scope.account);
            try {
                require(candidate == null);
            } finally {
                if (candidate != null) Arrays.fill(candidate, (byte) 0);
            }
        }
    }

    static void restoreAutomaticLogin(Context context, AccountLoginProtocol.Config config, byte[] fixture) throws Exception {
        verifyAutomaticLogin(context, false);
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        JSONObject input = new JSONObject(new String(fixture, StandardCharsets.UTF_8));
        byte[] code = input.getString("code").getBytes(StandardCharsets.US_ASCII);
        byte[] encrypted = Base64.getDecoder().decode(input.getString("package"));
        try {
            require(code.length == 43);
            byte original = code[0];
            code[0] = original == 'A' ? (byte) 'B' : (byte) 'A';
            try {
                SyncPreparation.restoreForConsent(context, consent.account, code, encrypted, consent.generation, config, () -> false);
                throw new AssertionError("accepted invalid recovery code");
            } catch (IOException rejected) {
                require("sync_recovery_invalid".equals(rejected.getMessage()));
            }
            code[0] = original;
            verifyAutomaticLogin(context, false);
            SyncPreparation.restoreForConsent(context, consent.account, code, encrypted, consent.generation, config, () -> false);
            verifyAutomaticLogin(context, true);
            queueAutomaticRecovery(context);
        } finally {
            Arrays.fill(code, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    static void queueAutomaticRecovery(Context context) throws Exception {
        long handle = NativeCore.open(new File(context.getFilesDir(), "zapime").getAbsolutePath(), false);
        require(handle != 0);
        try {
            edit(handle, RECORD, OPERATION, 0, "a3sync", "synthetic A3 automatic shortcut");
        } finally {
            NativeCore.close(handle);
        }
        checkReceipt(context, RECORD, OPERATION, 0);
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
        verifyInterrupted(context, true);
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
        verifyInterrupted(context, true);
        checkConflict(context, true);
    }

    static void queueInterrupted(Context context) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account != null && consent.expectedKey == null);
        checkFirstThreeReceipts(context);
        checkCursor(context, 3);
        long core = NativeCore.open(new File(context.getFilesDir(), "zapime").getAbsolutePath(), false);
        require(core != 0);
        try {
            edit(core, INTERRUPTED_RECORD, INTERRUPTED_OPERATION, 0, "a3stop", "synthetic A3 interrupted upload shortcut");
        } finally {
            NativeCore.close(core);
        }
        checkReceipt(context, INTERRUPTED_RECORD, INTERRUPTED_OPERATION, 0);
    }

    static String verifyInterrupted(Context context, boolean acknowledged) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account != null && (consent.expectedKey != null) == acknowledged);
        checkFirstThreeReceipts(context);
        checkReceipt(context, INTERRUPTED_RECORD, INTERRUPTED_OPERATION, acknowledged ? 4 : 0);
        checkCursor(context, acknowledged ? 4 : 3);
        return frozenInterruptedDigest(context);
    }

    private static String frozenInterruptedDigest(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor row = db.rawQuery("SELECT wire FROM sync_uploads WHERE operation_id=?",
                        new String[]{INTERRUPTED_OPERATION.toString()})) {
            require(row.moveToFirst() && !row.isNull(0));
            byte[] wire = row.getString(0).getBytes(StandardCharsets.UTF_8);
            try {
                return fingerprint(wire);
            } finally {
                Arrays.fill(wire, (byte) 0);
            }
        }
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
        edit(core, record, operation, expected, code, text, false);
    }

    private static void edit(long core, UUID record, UUID operation, long expected, String code, String text, boolean deleted) throws Exception {
        JSONObject content = new JSONObject().put("type", "snippet").put("code", code)
                .put("text", text).put("tags", new JSONArray());
        JSONObject command = new JSONObject().put("op", "personal_edit").put("id", record.toString())
                .put("operation_id", operation.toString()).put("expected_revision", expected).put("content", content).put("deleted", deleted);
        require(!new JSONObject(NativeCore.request(core, command.toString())).has("error"));
    }

    static String signedOut(Context context) throws Exception {
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(consent.account == null && consent.device == null && consent.expectedKey == null);
        return checkPreservedWorkspace(context, null);
    }

    static String switchedAccount(Context context, Bundle result) throws Exception {
        UUID target = UUID.fromString("98a01050-3185-4eed-bac4-127426c17372");
        SyncConsentStore.State consent = SyncConsentStore.load(context);
        require(target.equals(consent.account) && consent.device == null && consent.expectedKey == null
                && target.equals(consent.verifiedDeployment(AccountLoginDeployment.config()).account));
        byte[] material = SyncSecrets.load(context, target);
        byte[] device = null;
        try {
            device = SyncSecrets.loadConnection(context, target);
            require(material == null && device == null);
        } finally {
            if (material != null) Arrays.fill(material, (byte) 0);
            if (device != null) Arrays.fill(device, (byte) 0);
        }
        result.putString("prior_connection_sha256", checkPreservedWorkspace(context, target));
        try {
            SyncDatabase.check(context, target);
            throw new AssertionError("foreground_switch_accepted_old_database");
        } catch (IOException expected) {
            require("sync_connection_profile".equals(expected.getMessage()));
        }
        return frozenInterruptedDigest(context);
    }

    private static String checkPreservedWorkspace(Context context, UUID differentAccount) throws Exception {
        String connectionFingerprint;
        try (SyncConnection connection = SyncConnectionStore.load(context)) {
            require(connection != null && !connection.account.equals(differentAccount));
            byte[] encoded = connection.encode();
            try {
                connectionFingerprint = fingerprint(encoded);
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
            byte[] material = SyncSecrets.load(context, connection.account);
            require(material != null);
            try {
                String root = new File(context.getFilesDir(), "zapime").getAbsolutePath();
                JSONObject workspace = new JSONObject(NativeCore.syncWorkspaceStatus(root, connection.account.toString(), material));
                require(workspace.getBoolean("prepared") && connection.account.toString().equals(workspace.getString("account_id")));
            } finally {
                Arrays.fill(material, (byte) 0);
            }
        }
        checkFirstThreeReceipts(context);
        checkReceipt(context, INTERRUPTED_RECORD, INTERRUPTED_OPERATION, 4);
        checkCursor(context, 4);
        checkConflict(context, true);
        return connectionFingerprint;
    }

    private static String fingerprint(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static void checkFirstThreeReceipts(Context context) throws Exception {
        checkReceipt(context, RECORD, OPERATION, 1);
        checkReceipt(context, RECOVERY_RECORD, RECOVERY_OPERATION, 2);
        checkReceipt(context, NETWORK_RECORD, NETWORK_OPERATION, 3);
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
                require(failed.moveToFirst() && failed.getInt(0) == 4 && "\"local_conflict\"".equals(failed.getString(1))
                        && new JSONObject(failed.getString(2)).getInt("head") == 5 && !failed.moveToNext());
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
