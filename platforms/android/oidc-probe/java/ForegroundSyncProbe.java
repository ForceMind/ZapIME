// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Prepares synthetic state and verifies durable results. The UI starts the sync. */
final class ForegroundSyncProbe {
    private static final UUID OPERATION = UUID.fromString("7107125f-32d0-40a0-bd83-a77d8b166f90");
    private static final UUID RECORD = UUID.fromString("000d046e-1a0e-42a0-9ce8-fa42a626d0ce");
    private static final UUID RECOVERY_OPERATION = UUID.fromString("cec2bc8b-b259-41a8-bb70-7e92a5d96042");
    private static final UUID RECOVERY_RECORD = UUID.fromString("a4208deb-74bb-4b53-984a-07faa0832711");

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
                    edit(core, RECORD, OPERATION, "a3sync", "synthetic A3 automatic shortcut");
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
            edit(core, RECOVERY_RECORD, RECOVERY_OPERATION, "a3back", "synthetic A3 recovered shortcut");
        } finally {
            NativeCore.close(core);
        }
        verifyRecovery(context, false);
    }

    static void verifyRecovery(Context context, boolean acknowledged) throws Exception {
        verify(context, true);
        checkRecovery(context, acknowledged);
    }

    private static void edit(long core, UUID record, UUID operation, String code, String text) throws Exception {
        JSONObject content = new JSONObject().put("type", "snippet").put("code", code)
                .put("text", text).put("tags", new JSONArray());
        JSONObject command = new JSONObject().put("op", "personal_edit").put("id", record.toString())
                .put("operation_id", operation.toString()).put("expected_revision", 0).put("content", content);
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
        checkRecovery(context, true);
    }

    private static void checkRecovery(Context context, boolean acknowledged) throws Exception {
        checkReceipt(context, RECOVERY_RECORD, RECOVERY_OPERATION, acknowledged ? 2 : 0);
        File file = new File(context.getFilesDir(), "zapime/data/personal.sqlite3");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor row = db.rawQuery("SELECT value FROM metadata WHERE key='sync_cursor'", null)) {
            require(row.moveToFirst() && row.getInt(0) == (acknowledged ? 2 : 1));
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
