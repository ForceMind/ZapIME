// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
            try (SyncEnrollmentVault.Pending first = SyncEnrollmentVault.prepare(context, scope)) {
                attempt = first.id;
                char[] token = first.credential();
                try {
                    SyncHttp.token(token);
                    require(Files.isRegularFile(file.toPath()));
                    require(!new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1)
                            .contains(new String(token)));
                    try (SyncEnrollmentVault.Pending same = SyncEnrollmentVault.prepare(context, scope)) {
                        char[] reopened = same.credential();
                        try {
                            require(same.id.equals(attempt) && same.deadline == first.deadline && Arrays.equals(token, reopened));
                        } finally {
                            Arrays.fill(reopened, '\0');
                        }
                        Signature verifier = Signature.getInstance("SHA256withECDSA");
                        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(same.publicKey())));
                        verifier.update(message);
                        require(verifier.verify(same.sign(message)) && keys.getKey(alias, null).getEncoded() == null);
                    }
                } finally {
                    Arrays.fill(token, '\0');
                }
            }
            reject(() -> SyncEnrollmentVault.prepare(context, otherSubject));
            reject(() -> SyncEnrollmentVault.prepare(context, otherService));
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
            try (SyncEnrollmentVault.Pending recreated = SyncEnrollmentVault.prepare(context, scope)) {
                require(!recreated.id.equals(attempt));
            }
            byte[] frozen = Files.readAllBytes(file.toPath());
            keys.deleteEntry(alias);
            reject(() -> SyncEnrollmentVault.prepare(context, scope));
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
