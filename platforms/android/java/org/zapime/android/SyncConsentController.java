// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Off-worker UI receives status only. All identity and readiness checks run on the sync worker. */
final class SyncConsentController {
    enum Status {
        SIGNED_OUT, WAITING_FOR_DEVICE, OFF, PAUSED, WAITING_FOR_KEY, WORKSPACE_REQUIRED,
        KEY_CONFLICT, KEY_UNREGISTERED, REVOKED, READY
    }
    private static final AtomicReference<UUID> STOPPED = new AtomicReference<>();

    static final class Assessment {
        final SyncConsentStore.State state;
        final Status status;

        private Assessment(SyncConsentStore.State state, Status status) {
            this.state = state;
            this.status = status;
        }
    }

    static Assessment inspect(Context context, AccountLoginProtocol.Config config, SyncHttp.Cancellation cancel)
            throws IOException {
        SyncConsentStore.State state = SyncConsentStore.load(context);
        if (state.account == null) return new Assessment(state, Status.SIGNED_OUT);
        state.verifiedDeployment(config);
        if (state.device == null) return new Assessment(state, Status.WAITING_FOR_DEVICE);
        if (state.expectedKey == null) return new Assessment(state, Status.OFF);
        if (isStopped(state.generation)) return new Assessment(state, Status.PAUSED);
        return check(context, config, state, cancel, false);
    }

    static void stop(UUID generation) {
        STOPPED.set(generation);
    }

    static boolean isStopped(UUID generation) {
        return generation.equals(STOPPED.get());
    }

    static Assessment enable(Context context, AccountLoginProtocol.Config config, SyncConsentStore.State confirmed,
            SyncHttp.Cancellation cancel) throws IOException {
        if (confirmed.account == null || confirmed.device == null || confirmed.expectedKey != null) {
            throw new IOException("sync_consent_identity");
        }
        return check(context, config, confirmed, cancel, true);
    }

    private static Assessment check(Context context, AccountLoginProtocol.Config config, SyncConsentStore.State state,
            SyncHttp.Cancellation cancel, boolean confirm) throws IOException {
        current(context, state, cancel);
        SyncEnrollmentVault.Scope scope = state.verifiedDeployment(config);
        try (SyncConnection saved = SyncConnectionStore.load(context)) {
            if (saved == null || !scope.config.syncServer.equals(saved.server) || !state.matches(scope, saved.device)
                    || !state.account.equals(saved.account)) {
                return disable(context, state, Status.WAITING_FOR_DEVICE);
            }
            verifyDevice(saved, cancel);
            current(context, state, cancel);
            SyncKeyRegistration.Result remote = SyncKeyRegistration.inspectRemote(context, saved, cancel);
            if (!"registered".equals(remote.state)) {
                Status status = "empty".equals(remote.state) ? Status.KEY_UNREGISTERED : Status.KEY_CONFLICT;
                return disable(context, state, status);
            }
            if (!confirm && !state.expectedKey.same(remote.descriptor)) {
                return disable(context, state, Status.KEY_CONFLICT);
            }
            SyncPreparation.State local = SyncPreparation.inspect(context);
            Status status;
            if (!local.materialPresent) {
                if (local.keyId != null && !local.keyId.equals(remote.keyId)) {
                    return disable(context, state, Status.KEY_CONFLICT);
                }
                status = Status.WAITING_FOR_KEY;
            } else {
                if (!local.prepared) return disable(context, state, Status.WORKSPACE_REQUIRED);
                if (!remote.descriptor.same(SyncKeyRegistration.preparedLocal(context, saved, cancel))) {
                    return disable(context, state, Status.KEY_CONFLICT);
                }
                status = Status.READY;
            }
            current(context, state, cancel);
            if (confirm) {
                state = SyncConsentStore.enable(context, state.generation, scope, saved.device, remote, cancel);
            }
            return new Assessment(state, status);
        } catch (SyncHttp.Failure failure) {
            if (failure.code.equals("unauthorized") || failure.code.equals("forbidden") || failure.code.equals("identity")) {
                return disable(context, state, Status.REVOKED);
            }
            throw failure;
        }
    }

    private static void verifyDevice(SyncConnection saved, SyncHttp.Cancellation cancel) throws IOException {
        byte[] response = saved.inspect(cancel);
        try (SyncConnection verified = saved.verify(response)) {
            if (!saved.matchesSnapshot(verified) || !Instant.now().isBefore(OffsetDateTime.parse(verified.expires).toInstant())) {
                throw new SyncHttp.Failure("identity");
            }
        } finally {
            Arrays.fill(response, (byte) 0);
        }
    }

    private static void current(Context context, SyncConsentStore.State state, SyncHttp.Cancellation cancel) throws IOException {
        cancel.check();
        if (isStopped(state.generation) || !state.generation.equals(SyncConsentStore.load(context).generation)) {
            throw new IOException("sync_consent_changed");
        }
    }

    private static Assessment disable(Context context, SyncConsentStore.State state, Status status) throws IOException {
        if (state.expectedKey != null) state = SyncConsentStore.disable(context, state.generation);
        return new Assessment(state, status);
    }

    private SyncConsentController() {}
}
