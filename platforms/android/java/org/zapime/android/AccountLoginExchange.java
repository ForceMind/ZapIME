// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verifies the login and, for the confirmed sync path, prepares the device. */
final class AccountLoginExchange {
    static final class Identity {
        final UUID device;
        final SyncEnrollmentVault.Scope enrollmentScope;
        final String deviceState;

        Identity(SyncEnrollmentVault.Scope enrollmentScope, UUID device, String deviceState) {
            this.enrollmentScope = enrollmentScope;
            this.device = device;
            this.deviceState = deviceState;
        }
    }

    static Identity verify(Context context, AccountLoginProtocol.Exchange exchange, UUID attempt,
            boolean requestSync, UUID expectedAccount, SyncHttp.Cancellation cancel)
            throws IOException {
        byte[] tokenReply = SyncHttp.exchangeLogin(exchange, cancel);
        byte[] lookup;
        String token;
        try {
            token = accessToken(tokenReply);
            lookup = SyncHttp.accountLogin(exchange.config, token, cancel);
        } finally {
            Arrays.fill(tokenReply, (byte) 0);
        }
        SyncEnrollmentVault.Scope scope;
        try {
            Map<String, String> fields = SyncConnection.object(lookup,
                    SyncConnection.fields("protocol", "account_id", "subject_fingerprint"), SyncConnection.fields("protocol"));
            if (!"1".equals(fields.get("protocol"))) throw new SyncHttp.Failure("login_response");
            scope = new SyncEnrollmentVault.Scope(exchange.config, SyncConnection.id(fields.get("account_id")),
                    fields.get("subject_fingerprint"));
        } finally {
            Arrays.fill(lookup, (byte) 0);
        }
        SyncConsentStore.requireLoginAttempt(context, attempt, cancel);
        if (expectedAccount != null && !expectedAccount.equals(scope.account)) {
            return new Identity(scope, null, "当前登录账号已改变。请核对账号后再开启同步。");
        }
        try (SyncConnection saved = SyncConnectionStore.load(context)) {
            if (saved == null) {
                if (!requestSync) return new Identity(scope, null, null);
                SyncDatabase.check(context, scope.account);
                try (SyncEnrollmentVault.Pending pending = SyncEnrollmentVault.prepareAutomatic(context, scope)) {
                    return authorize(context, attempt, pending, token, cancel);
                }
            }
            if (!scope.account.equals(saved.account) || !exchange.config.syncServer.equals(saved.server)) {
                return new Identity(scope, null, "已保存的设备连接属于其他账号或服务器，请先核对");
            }
            try {
                byte[] response = saved.inspect(cancel);
                try (SyncConnection verified = saved.verify(response)) {
                    cancel.check();
                    return new Identity(scope, verified.device, null);
                } finally {
                    Arrays.fill(response, (byte) 0);
                }
            } catch (SyncHttp.Failure failed) {
                if (!requestSync || !"unauthorized".equals(failed.code)) throw failed;
                SyncDatabase.check(context, scope.account);
                try (SyncEnrollmentVault.Pending pending = SyncEnrollmentVault.loadAutomatic(context, scope)) {
                    if (pending == null) throw failed;
                    char[] credential = pending.credential();
                    try {
                        if (!saved.matchesCredential(scope.config.syncServer, scope.account, pending.id, credential)) throw failed;
                    } finally {
                        Arrays.fill(credential, '\0');
                    }
                    return authorize(context, attempt, pending, token, cancel);
                }
            }
        } catch (SyncHttp.Failure failure) {
            SyncConsentStore.requireLoginAttempt(context, attempt, cancel);
            if (failure.code.equals("unauthorized") || failure.code.equals("forbidden")) {
                return new Identity(scope, null, "设备授权已失效");
            }
            if (failure.code.equals("unsupported")) return new Identity(scope, null, "此服务器尚未启用自动设备授权");
            return new Identity(scope, null, "暂时无法核对当前设备授权");
        } catch (IOException unavailable) {
            SyncConsentStore.requireLoginAttempt(context, attempt, cancel);
            if ("sync_connection_profile".equals(unavailable.getMessage())) {
                return new Identity(scope, null, "本机资料已绑定其他账号，已暂停同步");
            }
            return new Identity(scope, null, "暂时无法准备本机设备授权，请重试");
        }
    }

    private static Identity authorize(Context context, UUID attempt, SyncEnrollmentVault.Pending pending,
            String token, SyncHttp.Cancellation cancel) throws IOException {
        SyncConsentStore.requireLoginAttempt(context, attempt, cancel);
        try (SyncConnection verified = new SyncEnrollmentRequest(pending).authorize(token, cancel)) {
            SyncConsentStore.requireLoginAttempt(context, attempt, cancel);
            SyncConnectionStore.promoteAutomatic(context, verified, cancel);
            return new Identity(pending.scope, verified.device, null);
        }
    }

    private static String accessToken(byte[] reply) throws SyncHttp.Failure {
        String token = null;
        String type = null;
        Set<String> fields = new HashSet<>();
        try (JsonReader json = new JsonReader(new InputStreamReader(new ByteArrayInputStream(reply),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            json.setLenient(false);
            json.beginObject();
            while (json.hasNext()) {
                String name = json.nextName();
                if (fields.size() >= 16 || !fields.add(name) || name.equals("error")) throw new IOException();
                if (name.equals("access_token") || name.equals("token_type")) {
                    if (json.peek() != JsonToken.STRING) throw new IOException();
                    String value = json.nextString();
                    if (name.equals("access_token")) token = value;
                    else type = value;
                } else {
                    json.skipValue();
                }
            }
            json.endObject();
            if (json.peek() != JsonToken.END_DOCUMENT || token == null || !"Bearer".equalsIgnoreCase(type)) {
                throw new IOException();
            }
            return token;
        } catch (IOException | RuntimeException invalid) {
            throw new SyncHttp.Failure("login_response");
        }
    }

    private AccountLoginExchange() {}
}
