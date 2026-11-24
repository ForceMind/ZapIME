// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Runs with the disposable A3 fixture app on the instrumentation worker. */
public final class EnrollmentVaultProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            checkVault();
            result.putString("enrollment_vault", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failed) {
            result.putString("enrollment_vault", "FAIL");
            result.putString("failure_type", failed.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkVault() throws Exception {
        Context context = getTargetContext();
        UUID account = UUID.randomUUID();
        String alias = "org.zapime.android.sync.enrollment-sign.v1." + account;
        AccountLoginProtocol.Config config = AccountLoginDeployment.config();
        SyncEnrollmentVault.Scope scope = new SyncEnrollmentVault.Scope(config, account,
                "0101010101010101010101010101010101010101010101010101010101010101");
        SyncEnrollmentVault.Scope otherSubject = new SyncEnrollmentVault.Scope(config, account,
                "0202020202020202020202020202020202020202020202020202020202020202");
        AccountLoginProtocol.Config otherConfig = new AccountLoginProtocol.Config(
                config.issuer.toString(), config.authorization.toString(), config.token.toString(),
                config.callback.toString(), "https://other.example.test/resource", "https://other.example.test", config.clientId);
        SyncEnrollmentVault.Scope otherService = new SyncEnrollmentVault.Scope(otherConfig, account,
                "0101010101010101010101010101010101010101010101010101010101010101");
        byte[] material = {1, 2, 3}, connection = {4, 5, 6}, message = {21, 22, 23};
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        require(!keys.containsAlias(alias) && SyncEnrollmentVault.load(context, scope) == null);
        try {
            SyncSecrets.save(context, account, material);
            SyncSecrets.saveConnection(context, account, connection);
            File file = new File(context.getNoBackupFilesDir(), "sync-enrollment-" + account + ".bin");
            UUID attempt;
            try (SyncEnrollmentVault.Pending first = SyncEnrollmentVault.prepare(context, scope, "合成 Android 设备")) {
                attempt = first.id;
                char[] token = first.credential();
                try {
                    SyncHttp.token(token);
                    require(Files.isRegularFile(file.toPath()));
                    require(!new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1)
                            .contains(new String(token)));
                    try (SyncEnrollmentVault.Pending same = SyncEnrollmentVault.prepare(context, scope, "变更设备名")) {
                        char[] reopened = same.credential();
                        try {
                            require(same.id.equals(attempt) && same.deadline == first.deadline &&
                                    same.name.equals(first.name) && Arrays.equals(token, reopened));
                        } finally {
                            Arrays.fill(reopened, '\0');
                        }
                        Signature verifier = Signature.getInstance("SHA256withECDSA");
                        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(same.publicKey())));
                        verifier.update(message);
                        require(verifier.verify(same.sign(message)) && keys.getKey(alias, null).getEncoded() == null);
                        checkRequests(same, token);
                    }
                } finally {
                    Arrays.fill(token, '\0');
                }
            }
            reject(() -> SyncEnrollmentVault.prepare(context, otherSubject, "Android"));
            reject(() -> SyncEnrollmentVault.prepare(context, otherService, "Android"));
            reject(() -> {
                SyncEnrollmentVault.forget(context, scope, UUID.randomUUID());
                return null;
            });
            AtomicBoolean guard = new AtomicBoolean();
            runOnMainSync(() -> {
                try (SyncEnrollmentVault.Pending ignored = SyncEnrollmentVault.load(context, scope)) {
                    guard.set(false);
                } catch (IOException rejected) {
                    guard.set("sync_enrollment_requires_worker".equals(rejected.getMessage()));
                }
            });
            require(guard.get());
            SyncEnrollmentVault.forget(context, scope, attempt);
            require(!keys.containsAlias(alias) && !file.exists() && SyncEnrollmentVault.load(context, scope) == null);
            reject(() -> SyncEnrollmentVault.prepare(context, scope, " "));
            try (SyncEnrollmentVault.Pending recreated = SyncEnrollmentVault.prepare(context, scope, "Android")) {
                require(!recreated.id.equals(attempt));
            }
            byte[] frozen = Files.readAllBytes(file.toPath());
            keys.deleteEntry(alias);
            reject(() -> SyncEnrollmentVault.prepare(context, scope, "Android"));
            require(!keys.containsAlias(alias) && Arrays.equals(frozen, Files.readAllBytes(file.toPath())));
            byte[] keptMaterial = SyncSecrets.load(context, account);
            byte[] keptConnection = SyncSecrets.loadConnection(context, account);
            try {
                require(Arrays.equals(material, keptMaterial) && Arrays.equals(connection, keptConnection));
            } finally {
                if (keptMaterial != null) Arrays.fill(keptMaterial, (byte) 0);
                if (keptConnection != null) Arrays.fill(keptConnection, (byte) 0);
            }
        } finally {
            SyncSecrets.forgetEnrollment(context, account);
            SyncSecrets.forgetConnection(context, account);
            SyncSecrets.forget(context, account);
            keys.deleteEntry(alias);
        }
    }

    private static void checkRequests(SyncEnrollmentVault.Pending pending, char[] token) throws Exception {
        SyncEnrollmentRequest request = new SyncEnrollmentRequest(pending);
        String access = "synthetic.fixture.token";
        byte[] start = request.wire(access, false);
        byte[] claim = request.wire(access, true);
        try {
            String startText = new String(start, StandardCharsets.UTF_8);
            JSONObject begin = new JSONObject(startText);
            JSONObject take = new JSONObject(new String(claim, StandardCharsets.UTF_8));
            AccountLoginProtocol.Config config = pending.scope.config;
            String deviceId = pending.id.toString(), accountId = pending.scope.account.toString();
            byte[] publicDer = pending.publicKey();
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] candidateHash = sha256.digest(new String(token).getBytes(StandardCharsets.US_ASCII));
            byte[] accessHash = sha256.digest(access.getBytes(StandardCharsets.US_ASCII));
            require(begin.length() == 7 && begin.getString("enrollment_id").equals(deviceId)
                    && begin.getString("account_id").equals(accountId) && begin.getString("name").equals(pending.name)
                    && begin.getLong("deadline") == pending.deadline && !startText.contains(new String(token))
                    && Arrays.equals(Base64.getUrlDecoder().decode(begin.getString("public_key")), publicDer)
                    && Arrays.equals(Base64.getUrlDecoder().decode(begin.getString("token_sha256")), candidateHash));
            require(take.length() == 3 && take.getString("enrollment_id").equals(deviceId)
                    && take.getString("account_id").equals(accountId));
            byte[] fingerprint = sha256.digest(framed("zapime-enrollment-v1", config.issuer, config.resource,
                    accountId, deviceId, pending.name, pending.deadline, publicDer, candidateHash));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicDer)));
            verifier.update(framed("zapime-enrollment-begin-v1", fingerprint, accessHash));
            require(verifier.verify(Base64.getUrlDecoder().decode(begin.getString("signature"))));
            verifier.update(framed("zapime-enrollment-claim-v1", fingerprint, accessHash));
            require(verifier.verify(Base64.getUrlDecoder().decode(take.getString("signature"))));
            JSONObject session = new JSONObject().put("protocol", 1).put("account_id", accountId)
                    .put("device_id", deviceId).put("role", "member").put("expires_at", Instant.now().plusSeconds(300).toString());
            try (SyncConnection verified = SyncConnection.verifyEnrolled(config.syncServer, pending.scope.account,
                    pending.id, token, session.toString().getBytes(StandardCharsets.UTF_8))) {
                require(verified.device.equals(pending.id) && "member".equals(verified.role));
            }
            for (String field : new String[]{"account_id", "device_id", "role", "expires_at"}) {
                JSONObject wrong = new JSONObject(session.toString()).put(field, field.equals("role") ? "owner" :
                        field.equals("expires_at") ? Instant.now().minusSeconds(30).toString() : UUID.randomUUID().toString());
                try (SyncConnection unexpected = SyncConnection.verifyEnrolled(config.syncServer, pending.scope.account,
                        pending.id, token, wrong.toString().getBytes(StandardCharsets.UTF_8))) {
                    throw new AssertionError("invalid_enrollment_session");
                } catch (SyncHttp.Failure expected) {
                    require(expected.code.equals("identity"));
                }
            }
            SyncHttp.Cancellation stopped = new SyncHttp.Cancellation();
            stopped.cancel();
            try {
                request.submit(access, stopped);
                throw new AssertionError("cancelled_enrollment");
            } catch (SyncHttp.Failure expected) {
                require(expected.code.equals("cancelled"));
            }
        } finally {
            Arrays.fill(start, (byte) 0);
            Arrays.fill(claim, (byte) 0);
        }
    }

    private static byte[] framed(Object... values) {
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        for (Object value : values) {
            byte[] bytes = value instanceof byte[] ? (byte[]) value : value.toString().getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length).put(bytes);
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("enrollment_vault");
    }

    private static void reject(Checked task) throws Exception {
        try (SyncEnrollmentVault.Pending ignored = task.run()) {
            throw new AssertionError("enrollment_vault_accepted");
        } catch (IOException expected) {
            // The fixture's wrapped state must remain intact on a rejected request.
        }
    }

    private interface Checked {
        SyncEnrollmentVault.Pending run() throws Exception;
    }
}
