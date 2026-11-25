// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** Durable, device-local sign-in and explicit sync consent. Holds public identities only. */
final class SyncConsentStore {
    private static final int VERSION = 1, DATA_SCOPE = 1, MAX_BYTES = 512;
    private static final UUID SLOT = UUID.fromString("54ebda61-ba6c-437e-9462-e5fed5ce159a");
    private static final UUID NONE = new UUID(0, 0);

    static final class State {
        final UUID generation, account, device;
        final SyncKeyRegistration.Descriptor expectedKey;
        private final String subjectFingerprint;
        private final byte[] binding;

        private State(UUID generation, UUID account, UUID device, String subjectFingerprint, byte[] binding,
                SyncKeyRegistration.Descriptor expectedKey) {
            this.generation = generation;
            this.account = account;
            this.device = device;
            this.subjectFingerprint = subjectFingerprint;
            this.binding = binding;
            this.expectedKey = expectedKey;
        }

        private static State signedOut(UUID generation) {
            return new State(generation, null, null, null, null, null);
        }

        boolean matches(SyncEnrollmentVault.Scope verified, UUID currentDevice) {
            return account != null && verified != null && account.equals(verified.account) && device != null
                    && device.equals(currentDevice) && MessageDigest.isEqual(binding, verified.binding());
        }

        SyncEnrollmentVault.Scope verifiedDeployment(AccountLoginProtocol.Config config) throws IOException {
            if (account == null) return null;
            SyncEnrollmentVault.Scope scope = new SyncEnrollmentVault.Scope(config, account, subjectFingerprint);
            if (!MessageDigest.isEqual(binding, scope.binding())) throw new IOException("sync_consent_deployment_changed");
            return scope;
        }

        // Callers must derive the supplied descriptor from the actual vault and bound workspace.
        boolean permits(SyncEnrollmentVault.Scope verified, UUID currentDevice, SyncKeyRegistration.Descriptor prepared) {
            return expectedKey != null && matches(verified, currentDevice) && expectedKey.same(prepared);
        }
    }

    static State load(Context context) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            return loadLocked(context);
        }
    }

    /** Starting any new login discards the previous permission even if the browser is later cancelled. */
    static State beginLogin(Context context, SyncHttp.Cancellation cancel) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            cancel.check();
            loadLocked(context);
            cancel.check();
            return persist(context, State.signedOut(UUID.randomUUID()));
        }
    }

    static State completeLogin(Context context, UUID attempt, SyncEnrollmentVault.Scope verified, UUID device,
            SyncHttp.Cancellation cancel) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            cancel.check();
            State current = requireGeneration(context, attempt);
            if (current.account != null || verified == null || NONE.equals(device)) throw new IOException("sync_consent_identity");
            return persist(context, new State(UUID.randomUUID(), verified.account, device, verified.subjectFingerprint,
                    verified.binding(), null));
        }
    }

    static void requireLoginAttempt(Context context, UUID attempt, SyncHttp.Cancellation cancel) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            cancel.check();
            if (requireGeneration(context, attempt).account != null) throw new IOException("sync_consent_changed");
        }
    }

    /** Consent locks the registered identity, including when local material still needs recovery. */
    static State enable(Context context, UUID generation, SyncEnrollmentVault.Scope verified, UUID device,
            SyncKeyRegistration.Result remote, SyncHttp.Cancellation cancel) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            cancel.check();
            State current = requireGeneration(context, generation);
            if (!current.matches(verified, device) || remote == null || !current.account.equals(remote.account)
                    || !"registered".equals(remote.state) || remote.descriptor == null) {
                throw new IOException("sync_consent_identity");
            }
            cancel.check();
            return persist(context, new State(UUID.randomUUID(), current.account, current.device,
                    current.subjectFingerprint, current.binding, remote.descriptor));
        }
    }

    static State disable(Context context, UUID generation) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            State current = requireGeneration(context, generation);
            return persist(context, new State(UUID.randomUUID(), current.account, current.device,
                    current.subjectFingerprint, current.binding, null));
        }
    }

    static State signOut(Context context, UUID generation, SyncHttp.Cancellation cancel) throws IOException {
        worker();
        synchronized (SyncConsentStore.class) {
            cancel.check();
            State current = requireGeneration(context, generation);
            if (current.account == null) throw new IOException("sync_consent_identity");
            cancel.check();
            return persist(context, State.signedOut(UUID.randomUUID()));
        }
    }

    private static State requireGeneration(Context context, UUID generation) throws IOException {
        State current = loadLocked(context);
        if (!current.generation.equals(generation)) throw new IOException("sync_consent_changed");
        return current;
    }

    private static State persist(Context context, State state) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(wire)) {
            out.writeByte(VERSION);
            uuid(out, state.generation);
            out.writeByte(state.account == null ? 0 : state.expectedKey == null ? 1 : 2);
            if (state.account != null) {
                uuid(out, state.account);
                uuid(out, state.device == null ? NONE : state.device);
                out.writeUTF(state.subjectFingerprint);
                out.write(state.binding);
                if (state.expectedKey != null) {
                    byte[] descriptor = state.expectedKey.wire();
                    out.writeInt(DATA_SCOPE);
                    out.writeShort(descriptor.length);
                    out.write(descriptor);
                }
            }
        }
        byte[] bytes = wire.toByteArray();
        try {
            // SyncSecrets verifies the committed plaintext before returning.
            SyncSecrets.saveSyncConsent(context, SLOT, bytes);
            return state;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static State loadLocked(Context context) throws IOException {
        byte[] wire = SyncSecrets.loadSyncConsent(context, SLOT);
        if (wire == null) return State.signedOut(NONE);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire))) {
            if (wire.length > MAX_BYTES || in.readUnsignedByte() != VERSION) throw invalid();
            UUID generation = uuid(in);
            if (NONE.equals(generation)) throw invalid();
            int mode = in.readUnsignedByte();
            State state;
            if (mode == 0) {
                state = State.signedOut(generation);
            } else if (mode == 1 || mode == 2) {
                UUID account = uuid(in), device = uuid(in);
                String subject = in.readUTF();
                if (NONE.equals(account) || !subject.matches("[0-9a-f]{64}")) throw invalid();
                byte[] binding = new byte[32];
                in.readFully(binding);
                SyncKeyRegistration.Descriptor key = null;
                if (mode == 2) {
                    if (NONE.equals(device) || in.readInt() != DATA_SCOPE) throw invalid();
                    int length = in.readUnsignedShort();
                    if (length == 0 || length > in.available()) throw invalid();
                    byte[] descriptor = new byte[length];
                    in.readFully(descriptor);
                    key = SyncKeyRegistration.parseDescriptor(descriptor);
                }
                state = new State(generation, account, NONE.equals(device) ? null : device, subject, binding, key);
            } else {
                throw invalid();
            }
            if (in.read() != -1) throw invalid();
            return state;
        } catch (IOException | RuntimeException malformed) {
            throw invalid();
        } finally {
            Arrays.fill(wire, (byte) 0);
        }
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static IOException invalid() {
        return new IOException("sync_consent_format");
    }

    private static void worker() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("sync_consent_requires_worker");
    }

    private SyncConsentStore() {}
}
