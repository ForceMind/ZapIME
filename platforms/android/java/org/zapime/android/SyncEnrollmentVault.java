// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Keeps one encrypted pending credential per account until the device has been verified. */
final class SyncEnrollmentVault {
    private static final int VERSION = 2, DIGEST = 32, FIXED_LENGTH = 1 + DIGEST + 16 + 8 + 32 + DIGEST;
    private static final int MAX_NAME_BYTES = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Scope {
        final UUID account;
        final AccountLoginProtocol.Config config;
        private final byte[] binding;
        final String subjectFingerprint;

        Scope(AccountLoginProtocol.Config config, UUID account, String subjectFingerprint) throws SyncHttp.Failure {
            if (config == null || account == null || account.equals(new UUID(0, 0))
                    || subjectFingerprint == null || !subjectFingerprint.matches("[0-9a-f]{64}")) {
                throw new SyncHttp.Failure("login_response");
            }
            this.account = account;
            this.config = config;
            this.subjectFingerprint = subjectFingerprint;
            binding = hash(String.join("\n", "ZapIME/enrollment-scope/v1", config.syncServer.toString(),
                    config.issuer.toString(), config.resource.toString(), config.clientId, account.toString(),
                    subjectFingerprint).getBytes(StandardCharsets.UTF_8));
        }
        byte[] binding() {
            return binding.clone();
        }
    }

    static final class Pending implements AutoCloseable {
        final Scope scope;
        final UUID id;
        final long deadline;
        final String name;
        private final byte[] random, publicKey;
        private final PrivateKey signingKey;
        private boolean closed;

        private Pending(Scope scope, UUID id, long deadline, String name, byte[] random, SigningKey signingKey) {
            this.scope = scope;
            this.id = id;
            this.deadline = deadline;
            this.name = name;
            this.random = random;
            this.publicKey = signingKey.encoded;
            this.signingKey = signingKey.privateKey;
        }

        byte[] publicKey() {
            return publicKey.clone();
        }

        char[] credential() throws IOException {
            check();
            byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(random);
            try {
                char[] value = new char[5 + encoded.length];
                "zap1_".getChars(0, 5, value, 0);
                for (int i = 0; i < encoded.length; i++) value[5 + i] = (char) (encoded[i] & 255);
                return value;
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }

        byte[] sign(byte[] message) throws IOException {
            check();
            if (message == null || message.length == 0 || message.length > 512) throw new IOException("sync_enrollment_proof");
            try {
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initSign(signingKey);
                signature.update(message);
                return signature.sign();
            } catch (GeneralSecurityException failure) {
                throw new IOException("sync_enrollment_sign");
            }
        }

        private void check() throws IOException {
            worker();
            if (closed) throw new IOException("sync_enrollment_closed");
        }

        @Override public void close() {
            closed = true;
            Arrays.fill(random, (byte) 0);
        }
    }

    static Pending prepare(Context context, Scope scope, String name) throws IOException {
        worker();
        synchronized (SyncEnrollmentVault.class) {
            Pending previous = loadLocked(context, scope);
            if (previous != null) return previous;
            byte[] encodedName = encodeName(name);
            byte[] wire = new byte[FIXED_LENGTH + 1 + encodedName.length], random = new byte[32];
            try {
                SigningKey key = signingKey(scope.account, true);
                UUID request = UUID.randomUUID();
                long deadline = Instant.now().plusSeconds(9 * 60).getEpochSecond();
                RANDOM.nextBytes(random);
                ByteBuffer.wrap(wire).put((byte) VERSION).put(scope.binding)
                        .putLong(request.getMostSignificantBits()).putLong(request.getLeastSignificantBits())
                        .putLong(deadline).put(random).put(hash(key.encoded)).put((byte) encodedName.length).put(encodedName);
                SyncSecrets.createEnrollment(context, scope.account, wire);
                Pending committed = loadLocked(context, scope);
                if (committed == null) throw new IOException("sync_enrollment_commit");
                return committed;
            } finally {
                Arrays.fill(wire, (byte) 0);
                Arrays.fill(random, (byte) 0);
            }
        }
    }

    static Pending load(Context context, Scope scope) throws IOException {
        worker();
        synchronized (SyncEnrollmentVault.class) {
            return loadLocked(context, scope);
        }
    }

    private static Pending loadLocked(Context context, Scope scope) throws IOException {
        byte[] wire = SyncSecrets.loadEnrollment(context, scope.account);
        if (wire == null) return null;
        try {
            if (wire.length < FIXED_LENGTH || wire.length > FIXED_LENGTH + 1 + MAX_NAME_BYTES
                    || (wire[0] != VERSION && (wire[0] != 1 || wire.length != FIXED_LENGTH))) {
                throw new IOException("sync_enrollment_format");
            }
            byte[] actualScope = Arrays.copyOfRange(wire, 1, 1 + DIGEST);
            if (!MessageDigest.isEqual(actualScope, scope.binding)) throw new IOException("sync_enrollment_scope");
            ByteBuffer fields = ByteBuffer.wrap(wire);
            fields.position(1 + DIGEST);
            UUID id = new UUID(fields.getLong(), fields.getLong());
            long deadline = fields.getLong();
            if (deadline <= 0 || id.version() != 4 || id.variant() != 2) throw new IOException("sync_enrollment_format");
            SigningKey key = signingKey(scope.account, false);
            if (!MessageDigest.isEqual(Arrays.copyOfRange(wire, FIXED_LENGTH - DIGEST, FIXED_LENGTH), hash(key.encoded))) {
                throw new IOException("sync_enrollment_key_changed");
            }
            String name = wire[0] == 1 ? "Android" : readName(wire);
            byte[] random = new byte[32];
            fields.get(random);
            // An expired pending record may still identify a device whose claim response was lost.
            return new Pending(scope, id, deadline, name, random, key);
        } finally {
            Arrays.fill(wire, (byte) 0);
        }
    }

    private static String readName(byte[] wire) throws IOException {
        if (wire.length == FIXED_LENGTH || (wire[FIXED_LENGTH] & 255) != wire.length - FIXED_LENGTH - 1) {
            throw new IOException("sync_enrollment_format");
        }
        String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(wire, FIXED_LENGTH + 1, wire.length - FIXED_LENGTH - 1)).toString();
        encodeName(name);
        return name;
    }

    private static byte[] encodeName(String name) throws IOException {
        if (name == null || name.length() > MAX_NAME_BYTES || name.codePoints().allMatch(
                c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || name.codePoints().anyMatch(c -> c < 32 || c == 127)) {
            throw new IOException("sync_enrollment_name");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(name));
            if (encoded.remaining() > MAX_NAME_BYTES) throw new IOException("sync_enrollment_name");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IOException("sync_enrollment_name");
        }
    }

    static void forget(Context context, Scope scope, UUID id) throws IOException {
        worker();
        synchronized (SyncEnrollmentVault.class) {
            try (Pending saved = loadLocked(context, scope)) {
                if (saved == null || !saved.id.equals(id)) throw new IOException("sync_enrollment_scope");
                SyncSecrets.forgetEnrollment(context, scope.account);
                try {
                    keyStore().deleteEntry(signingAlias(scope.account));
                } catch (GeneralSecurityException failure) {
                    throw new IOException("sync_enrollment_key");
                }
            }
        }
    }

    private static String signingAlias(UUID account) {
        return "org.zapime.android.sync.enrollment-sign.v1." + account;
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static SigningKey signingKey(UUID account, boolean create) throws IOException {
        try {
            String alias = signingAlias(account);
            KeyStore store = keyStore();
            if (!store.containsAlias(alias) && create) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build());
                generator.generateKeyPair();
                store = keyStore();
            }
            Key privateKey = store.getKey(alias, null);
            Certificate certificate = store.getCertificate(alias);
            if (!(privateKey instanceof PrivateKey) || privateKey.getEncoded() != null || certificate == null
                    || !(certificate.getPublicKey() instanceof ECPublicKey)) throw new IOException("sync_enrollment_key");
            ECPublicKey publicKey = (ECPublicKey) certificate.getPublicKey();
            byte[] encoded = publicKey.getEncoded();
            if (publicKey.getParams().getOrder().bitLength() != 256 || encoded == null || encoded.length > 128) {
                throw new IOException("sync_enrollment_key");
            }
            return new SigningKey((PrivateKey) privateKey, encoded);
        } catch (GeneralSecurityException failure) {
            throw new IOException("sync_enrollment_key");
        }
    }

    private static final class SigningKey {
        final PrivateKey privateKey;
        final byte[] encoded;

        SigningKey(PrivateKey privateKey, byte[] encoded) {
            this.privateKey = privateKey;
            this.encoded = encoded;
        }
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void worker() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("sync_enrollment_requires_worker");
    }

    private SyncEnrollmentVault() {}
}
