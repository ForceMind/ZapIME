// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Only the explicitly selected device's registration, claim and read-only reconciliation. */
final class SyncEnrollmentRequest {
    private final SyncEnrollmentVault.Pending pending;
    private final byte[] publicKey, candidateHash, fingerprint;

    SyncEnrollmentRequest(SyncEnrollmentVault.Pending pending) throws IOException {
        this.pending = pending;
        publicKey = pending.publicKey();
        char[] credential = pending.credential();
        byte[] ascii = new byte[credential.length];
        try {
            for (int i = 0; i < credential.length; i++) ascii[i] = (byte) credential[i];
            candidateHash = sha256(ascii);
        } finally {
            Arrays.fill(credential, '\0');
            Arrays.fill(ascii, (byte) 0);
        }
        AccountLoginProtocol.Config config = pending.scope.config;
        fingerprint = sha256(frame(text("zapime-enrollment-v1"), text(config.issuer.toString()),
                text(config.resource.toString()), text(pending.scope.account.toString()), text(pending.id.toString()),
                text(pending.name), text(Long.toString(pending.deadline)), publicKey, candidateHash));
    }

    String submit(String accessToken, SyncHttp.Cancellation cancel) throws IOException {
        cancel.check();
        byte[] request = wire(accessToken, false);
        byte[] reply = null;
        try {
            reply = SyncHttp.enrollment(pending.scope.config, accessToken, request, false, cancel);
            Map<String, String> receipt = SyncConnection.object(reply,
                    SyncConnection.fields("enrollment_id", "fingerprint", "deadline"), SyncConnection.fields("deadline"));
            String expectedFingerprint = hex(fingerprint);
            if (!pending.id.toString().equals(receipt.get("enrollment_id"))
                    || !Long.toString(pending.deadline).equals(receipt.get("deadline"))
                    || !expectedFingerprint.equals(receipt.get("fingerprint"))) {
                throw new SyncHttp.Failure("enrollment_receipt");
            }
            cancel.check();
            return expectedFingerprint;
        } finally {
            Arrays.fill(request, (byte) 0);
            if (reply != null) Arrays.fill(reply, (byte) 0);
        }
    }

    SyncConnection claim(String accessToken, SyncHttp.Cancellation cancel) throws IOException {
        cancel.check();
        byte[] request = wire(accessToken, true);
        byte[] reply = null;
        try {
            reply = SyncHttp.enrollment(pending.scope.config, accessToken, request, true, cancel);
            try (SyncConnection claimed = verify(reply)) {
                SyncConnection checked = recover(cancel);
                if (!checked.matchesSnapshot(claimed)) {
                    checked.close();
                    throw new SyncHttp.Failure("identity");
                }
                return checked;
            }
        } finally {
            Arrays.fill(request, (byte) 0);
            if (reply != null) Arrays.fill(reply, (byte) 0);
        }
    }

    SyncConnection authorize(String accessToken, SyncHttp.Cancellation cancel) throws IOException {
        cancel.check();
        byte[] request = wire(accessToken, false, true);
        byte[] reply = null;
        try {
            try {
                reply = SyncHttp.automaticEnrollment(pending.scope.config, accessToken, request, cancel);
            } catch (SyncHttp.Failure failed) {
                if (!("network".equals(failed.code) || "timeout".equals(failed.code))) throw failed;
                return recover(cancel);
            }
            try (SyncConnection granted = verify(reply)) {
                SyncConnection checked = recover(cancel);
                if (!checked.matchesSnapshot(granted)) {
                    checked.close();
                    throw new SyncHttp.Failure("identity");
                }
                return checked;
            }
        } finally {
            Arrays.fill(request, (byte) 0);
            if (reply != null) Arrays.fill(reply, (byte) 0);
        }
    }

    /** Call independently after an uncertain claim. This endpoint cannot create a device. */
    SyncConnection recover(SyncHttp.Cancellation cancel) throws IOException {
        cancel.check();
        char[] token = pending.credential();
        byte[] reply = null;
        try {
            reply = SyncHttp.session(pending.scope.config.syncServer, token, cancel);
            return SyncConnection.verifyEnrolled(pending.scope.config.syncServer, pending.scope.account,
                    pending.id, token, reply);
        } finally {
            Arrays.fill(token, '\0');
            if (reply != null) Arrays.fill(reply, (byte) 0);
        }
    }

    void storeVerified(Context context, SyncConnection actual, SyncHttp.Cancellation cancel) throws IOException {
        cancel.check();
        char[] token = pending.credential();
        try {
            if (!actual.matchesEnrollment(pending.scope.config.syncServer, pending.scope.account, pending.id, token)) {
                throw new SyncHttp.Failure("identity");
            }
            SyncConnectionStore.promoteEnrollment(context, actual, cancel);
            cancel.check();
            SyncEnrollmentVault.forget(context, pending.scope, pending.id);
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private SyncConnection verify(byte[] reply) throws IOException {
        char[] token = pending.credential();
        try {
            return SyncConnection.verifyEnrolled(pending.scope.config.syncServer, pending.scope.account,
                    pending.id, token, reply);
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    byte[] wire(String accessToken, boolean claim) throws IOException {
        return wire(accessToken, claim, false);
    }

    private byte[] wire(String accessToken, boolean claim, boolean automatic) throws IOException {
        SyncHttp.validateAccessToken(accessToken);
        if (!automatic && Instant.now().getEpochSecond() >= pending.deadline) throw new SyncHttp.Failure("enrollment_expired");
        byte[] accessBytes = accessToken.getBytes(StandardCharsets.US_ASCII);
        byte[] accessHash;
        try {
            accessHash = sha256(accessBytes);
        } finally {
            Arrays.fill(accessBytes, (byte) 0);
        }
        String purpose = automatic ? "zapime-auto-device-sign-v1" : claim ? "zapime-enrollment-claim-v1" : "zapime-enrollment-begin-v1";
        byte[] signature = null;
        try {
            byte[] signedFingerprint = fingerprint;
            if (automatic) {
                AccountLoginProtocol.Config config = pending.scope.config;
                signedFingerprint = sha256(frame(text("zapime-auto-device-v1"), text(config.issuer.toString()), text(config.resource.toString()),
                        text(pending.scope.account.toString()), text(pending.id.toString()), text(pending.scope.subjectFingerprint),
                        text(pending.name), publicKey, candidateHash));
            }
            signature = pending.sign(frame(text(purpose), signedFingerprint, accessHash));
            JSONObject object = new JSONObject().put(automatic ? "device_id" : "enrollment_id", pending.id.toString())
                    .put("account_id", pending.scope.account.toString()).put("signature", base64(signature));
            if (!claim) {
                object.put("name", pending.name)
                        .put("public_key", base64(publicKey)).put("token_sha256", base64(candidateHash));
                if (!automatic) object.put("deadline", pending.deadline);
            }
            return text(object.toString());
        } catch (JSONException failure) {
            throw new IOException("sync_enrollment_request");
        } finally {
            Arrays.fill(accessHash, (byte) 0);
            if (signature != null) Arrays.fill(signature, (byte) 0);
        }
    }

    private static byte[] frame(byte[]... fields) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(result)) {
            for (byte[] field : fields) {
                out.writeInt(field.length);
                out.write(field);
            }
        }
        return result.toByteArray();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] encoded = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            encoded[i * 2] = alphabet[(value[i] & 255) >> 4];
            encoded[i * 2 + 1] = alphabet[value[i] & 15];
        }
        return new String(encoded);
    }
}
