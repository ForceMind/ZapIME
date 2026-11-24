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

/** Only login, identity lookup and device-session checks. Runs on the sync worker. */
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

    static Identity verify(Context context, AccountLoginProtocol.Exchange exchange, SyncHttp.Cancellation cancel)
            throws IOException {
        byte[] tokenReply = SyncHttp.exchangeLogin(exchange, cancel);
        byte[] lookup;
        try {
            lookup = SyncHttp.accountLogin(exchange.config, accessToken(tokenReply), cancel);
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
        cancel.check();
        try (SyncConnection saved = SyncConnectionStore.load(context)) {
            if (saved == null) return new Identity(scope, null, "此设备尚未获授权");
            if (!scope.account.equals(saved.account) || !exchange.config.syncServer.equals(saved.server)) {
                return new Identity(scope, null, "已保存的设备连接属于其他账号或服务器，请先核对");
            }
            byte[] response = saved.inspect(cancel);
            try (SyncConnection verified = saved.verify(response)) {
                cancel.check();
                return new Identity(scope, verified.device, "设备授权有效");
            } finally {
                Arrays.fill(response, (byte) 0);
            }
        } catch (SyncHttp.Failure failure) {
            if (failure.code.equals("cancelled")) throw failure;
            if (failure.code.equals("unauthorized") || failure.code.equals("forbidden")) {
                return new Identity(scope, null, "设备授权已失效");
            }
            return new Identity(scope, null, "暂时无法核对当前设备授权");
        } catch (IOException unavailable) {
            return new Identity(scope, null, "暂时无法读取本机设备连接");
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
