// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Synthetic, offline permission tests in the disposable A3 APK. */
final class ConsentStoreProbe {
    private static final String SLOT = "54ebda61-ba6c-437e-9462-e5fed5ce159a";
    private static final String ALIAS = "org.zapime.android.sync.consent.v1." + SLOT;
    private static final String SUBJECT = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String OTHER_SUBJECT = "2222222222222222222222222222222222222222222222222222222222222222";

    static void run(Context context) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        File file = new File(context.getNoBackupFilesDir(), "sync-consent-" + SLOT + ".bin");
        require(!keys.containsAlias(ALIAS) && !file.exists() && !new File(file + ".bak").exists()
                && !new File(file + ".new").exists());
        SyncConsentStore.State initial = SyncConsentStore.load(context);
        require(initial.account == null && initial.expectedKey == null);
        AccountLoginProtocol.Config config = AccountLoginDeployment.config();
        AccountLoginProtocol.Config otherServer = new AccountLoginProtocol.Config(config.issuer.toString(),
                config.authorization.toString(), config.token.toString(), config.callback.toString(),
                "https://other.example.test/device", "https://other.example.test", config.clientId);
        UUID account = UUID.randomUUID(), device = UUID.randomUUID(), key = UUID.randomUUID();
        SyncEnrollmentVault.Scope target = new SyncEnrollmentVault.Scope(config, account, SUBJECT);
        SyncEnrollmentVault.Scope otherSubject = new SyncEnrollmentVault.Scope(config, account, OTHER_SUBJECT);
        SyncKeyRegistration.Result registered = remote(account, key, SUBJECT, "registered");
        SyncKeyRegistration.Result legacy = remote(account, key, SUBJECT, "legacy");
        require(registered.descriptor != null && !registered.matchesLocal);
        SyncHttp.Cancellation cancel = new SyncHttp.Cancellation();
        try {
            SyncConsentStore.State abandoned = SyncConsentStore.beginLogin(context, cancel);
            SyncConsentStore.State attempt = SyncConsentStore.beginLogin(context, cancel);
            reject(() -> SyncConsentStore.completeLogin(context, abandoned.generation, target, device, cancel));
            SyncConsentStore.State pending = SyncConsentStore.completeLogin(context, attempt.generation, target, null, cancel);
            reject(() -> SyncConsentStore.enable(context, pending.generation, target, device, registered, cancel));
            SyncConsentStore.State newAttempt = SyncConsentStore.beginLogin(context, cancel);
            SyncConsentStore.State signedIn = SyncConsentStore.completeLogin(context, newAttempt.generation, target, device, cancel);
            require(signedIn.expectedKey == null && !signedIn.permits(target, device, registered.descriptor));
            reject(() -> SyncConsentStore.enable(context, signedIn.generation, otherSubject, device, registered, cancel));
            reject(() -> SyncConsentStore.enable(context, signedIn.generation, target, device, legacy, cancel));
            reject(() -> SyncConsentStore.enable(context, signedIn.generation, target, UUID.randomUUID(), registered, cancel));
            SyncKeyRegistration.Result wrongAccount = remote(UUID.randomUUID(), key, SUBJECT, "registered");
            reject(() -> SyncConsentStore.enable(context, signedIn.generation, target, device, wrongAccount, cancel));
            SyncConsentStore.State confirmed = SyncConsentStore.enable(context, signedIn.generation, target, device, registered, cancel);
            SyncConsentStore.State loaded = SyncConsentStore.load(context);
            require(loaded.generation.equals(confirmed.generation) && loaded.expectedKey.same(registered.descriptor));
            require(!loaded.permits(target, device, null));
            require(loaded.permits(loaded.verifiedDeployment(config), device, registered.descriptor));
            require(!loaded.permits(otherSubject, device, registered.descriptor));
            require(!loaded.permits(target, device, remote(account, key, OTHER_SUBJECT, "registered").descriptor));
            reject(() -> loaded.verifiedDeployment(otherServer));

            checkBusyShutdown(context, config, loaded, cancel);
            SyncConsentStore.State off = SyncConsentStore.disable(context, loaded.generation);
            require(!off.permits(target, device, registered.descriptor));
            reject(() -> SyncConsentStore.enable(context, loaded.generation, target, device, registered, cancel));
            SyncHttp.Cancellation stopped = new SyncHttp.Cancellation();
            stopped.cancel();
            reject(() -> SyncConsentStore.enable(context, off.generation, target, device, registered, stopped));
            require(SyncConsentStore.load(context).generation.equals(off.generation));
            SyncConsentStore.State enabledAgain = SyncConsentStore.enable(context, off.generation, target, device, registered, cancel);
            reject(() -> SyncConsentStore.disable(context, off.generation));
            reject(() -> SyncConsentStore.signOut(context, off.generation, cancel));
            reject(() -> SyncConsentStore.signOut(context, enabledAgain.generation, stopped));
            require(SyncConsentStore.load(context).generation.equals(enabledAgain.generation));
            SyncConsentStore.State signedOut = SyncConsentStore.signOut(context, enabledAgain.generation, cancel);
            require(signedOut.account == null && signedOut.device == null && signedOut.expectedKey == null
                    && SyncConsentStore.load(context).generation.equals(signedOut.generation));
            reject(() -> SyncConsentStore.completeLogin(context, newAttempt.generation, target, device, cancel));
            reject(() -> SyncConsentStore.enable(context, enabledAgain.generation, target, device, registered, cancel));
            SyncConsentStore.State nextAttempt = SyncConsentStore.beginLogin(context, cancel);
            SyncConsentStore.State returned = SyncConsentStore.completeLogin(context, nextAttempt.generation, target, device, cancel);
            require(returned.expectedKey == null);
            SyncConsentStore.State beforeInterruption = SyncConsentStore.enable(context, returned.generation, target, device, registered, cancel);

            // An open AtomicFile write models interruption before commit on this API28 fixture.
            try (FileOutputStream interrupted = new AtomicFile(file).startWrite()) {
                interrupted.write(new byte[]{1, 2, 3});
                interrupted.getFD().sync();
            }
            require(SyncConsentStore.load(context).generation.equals(beforeInterruption.generation));
            byte[] saved = Files.readAllBytes(file.toPath());
            byte[] corrupted = saved.clone();
            corrupted[corrupted.length - 1] ^= 1;
            Files.write(file.toPath(), corrupted);
            reject(() -> SyncConsentStore.load(context));
            reject(() -> SyncConsentStore.beginLogin(context, cancel));
            require(Arrays.equals(corrupted, Files.readAllBytes(file.toPath())));
            Files.write(file.toPath(), saved);
            keys.deleteEntry(ALIAS);
            reject(() -> SyncConsentStore.load(context));
            reject(() -> SyncConsentStore.disable(context, beforeInterruption.generation));
            require(!keys.containsAlias(ALIAS) && Arrays.equals(saved, Files.readAllBytes(file.toPath())));
        } finally {
            new AtomicFile(file).delete();
            Files.deleteIfExists(new File(file + ".new").toPath());
            keys.deleteEntry(ALIAS);
            require(!file.exists() && !new File(file + ".bak").exists() && !keys.containsAlias(ALIAS));
        }
    }

    private static void checkBusyShutdown(Context context, AccountLoginProtocol.Config config, SyncConsentStore.State shown,
            SyncHttp.Cancellation cancel) throws Exception {
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        tasks.add(SyncWorker.EXECUTOR.submit(() -> {
            occupied.countDown();
            return release.await(5, TimeUnit.SECONDS);
        }));
        try {
            require(occupied.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 8; i++) tasks.add(SyncWorker.EXECUTOR.submit(() -> {}));
            SyncConsentController.stop(shown.generation);
            try {
                SyncWorker.EXECUTOR.submit(() -> SyncConsentStore.disable(context, shown.generation));
                throw new AssertionError("full_sync_queue_accepted");
            } catch (RejectedExecutionException expected) {
                require(SyncConsentController.inspect(context, config, cancel).status == SyncConsentController.Status.PAUSED);
                require(SyncConsentStore.load(context).generation.equals(shown.generation));
            }
        } finally {
            release.countDown();
            for (Future<?> task : tasks) task.get(3, TimeUnit.SECONDS);
        }
    }

    private static SyncKeyRegistration.Result remote(UUID account, UUID key, String fingerprint, String state) throws IOException {
        String tail = "null,\"registered_by\":null,\"registered_at\":null";
        if ("registered".equals(state)) {
            tail = "{\"version\":1,\"key_id\":\"" + key + "\",\"material_sha256\":\"" + fingerprint + "\"},"
                    + "\"registered_by\":\"" + UUID.randomUUID() + "\",\"registered_at\":\"2026-11-24T12:30:00Z\"";
        }
        String response = "{\"protocol\":1,\"account_id\":\"" + account + "\",\"state\":\"" + state + "\",\"descriptor\":" + tail + "}";
        return SyncKeyRegistration.parseRemote(response.getBytes(StandardCharsets.UTF_8), account);
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("sync_consent_fixture");
    }

    private static void reject(Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("unexpected_sync_consent");
        } catch (IOException expected) {
            // No rejected transition should persist a different generation.
        }
    }

    private interface Checked {
        Object run() throws Exception;
    }

    private ConsentStoreProbe() {}
}
