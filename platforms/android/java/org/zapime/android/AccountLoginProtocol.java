// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Exact browser callback and one-shot PKCE state. Tokens are verified by the configured sync service. */
final class AccountLoginProtocol {
    static final String SCOPE = "openid zapime.device_enrollment";
    private static final long LIFETIME = TimeUnit.MINUTES.toMillis(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Config {
        final URI issuer, authorization, token, callback, resource, syncServer;
        final String clientId;

        Config(String issuer, String authorization, String token, String callback, String resource,
               String syncServer, String clientId) throws SyncHttp.Failure {
            this.issuer = https(issuer);
            this.authorization = https(authorization);
            this.token = https(token);
            this.callback = https(callback);
            this.resource = https(resource);
            this.syncServer = SyncHttp.origin(syncServer);
            this.clientId = clientId;
            if (!origin(this.issuer).equals(origin(this.authorization))
                    || !origin(this.issuer).equals(origin(this.token))
                    || !this.syncServer.equals(origin(this.resource))
                    || this.callback.getRawPath().isEmpty() || this.callback.getRawPath().equals("/")
                    || !visible(clientId, 255)) {
                throw new SyncHttp.Failure("login_config");
            }
        }

        private static URI https(String value) throws SyncHttp.Failure {
            if (!visible(value, 1024)) throw new SyncHttp.Failure("login_config");
            try {
                URI uri = URI.create(value);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || uri.getRawPath().contains("%") || !uri.normalize().equals(uri)) {
                    throw new SyncHttp.Failure("login_config");
                }
                origin(uri);
                return uri;
            } catch (IllegalArgumentException invalid) {
                throw new SyncHttp.Failure("login_config");
            }
        }

        private static URI origin(URI uri) throws SyncHttp.Failure {
            return SyncHttp.origin("https://" + uri.getRawAuthority());
        }
    }

    static final class Attempt {
        private final Config config;
        private final long created;
        private final String state = random();
        private final String verifier = random();
        private boolean finished;

        Attempt(Config config, long elapsedRealtime) {
            this.config = config;
            this.created = elapsedRealtime;
        }

        URI authorization() {
            return URI.create(config.authorization + "?" + form(
                    "response_type", "code", "client_id", config.clientId, "redirect_uri", config.callback.toString(),
                    "scope", SCOPE, "resource", config.resource.toString(), "state", state,
                    "code_challenge", base64(sha256(verifier.getBytes(StandardCharsets.US_ASCII))),
                    "code_challenge_method", "S256"));
        }

        // Called by the main thread. Invalid intents do not consume an active attempt.
        Exchange accept(String value, long elapsedRealtime) throws SyncHttp.Failure {
            if (finished || elapsedRealtime < created || elapsedRealtime - created >= LIFETIME) {
                throw new SyncHttp.Failure("login_expired");
            }
            String prefix = config.callback + "?";
            if (!visible(value, 8192) || !value.startsWith(prefix) || value.indexOf('#') >= 0) {
                throw new SyncHttp.Failure("login_callback");
            }
            Map<String, String> fields = query(value.substring(prefix.length()));
            String callbackState = fields.get("state");
            String issuer = fields.get("iss");
            if (!visible(callbackState, 64) || !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                    callbackState.getBytes(StandardCharsets.US_ASCII))
                    || (issuer != null && !config.issuer.toString().equals(issuer))) {
                throw new SyncHttp.Failure("login_callback");
            }
            String code = fields.get("code");
            String error = fields.get("error");
            if (error != null && code == null && visible(error, 128)) {
                finished = true;
                throw new SyncHttp.Failure("login_denied");
            }
            if (error != null || !visible(code, 2048)) throw new SyncHttp.Failure("login_callback");
            finished = true;
            return new Exchange(config, code, verifier);
        }

        void cancel() {
            finished = true;
        }
    }

    static final class Exchange {
        final Config config;
        private final String code, verifier;

        private Exchange(Config config, String code, String verifier) {
            this.config = config;
            this.code = code;
            this.verifier = verifier;
        }

        byte[] body() {
            return form("grant_type", "authorization_code", "client_id", config.clientId,
                    "redirect_uri", config.callback.toString(), "resource", config.resource.toString(),
                    "code", code, "code_verifier", verifier).getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static Map<String, String> query(String query) throws SyncHttp.Failure {
        Map<String, String> values = new HashMap<>();
        for (String field : query.split("&", -1)) {
            int equals = field.indexOf('=');
            if (equals < 1 || values.size() >= 16) throw new SyncHttp.Failure("login_callback");
            try {
                String name = URLDecoder.decode(field.substring(0, equals), "UTF-8");
                String value = URLDecoder.decode(field.substring(equals + 1), "UTF-8");
                if (!visible(name, 64) || values.put(name, value) != null) {
                    throw new SyncHttp.Failure("login_callback");
                }
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            } catch (IllegalArgumentException invalid) {
                throw new SyncHttp.Failure("login_callback");
            }
        }
        return values;
    }

    private static String form(String... fields) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (i != 0) value.append('&');
            try {
                value.append(fields[i]).append('=').append(URLEncoder.encode(fields[i + 1], "UTF-8"));
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            }
        }
        return value.toString();
    }

    private static boolean visible(String text, int max) {
        if (text == null || text.isEmpty() || text.length() > max) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '!' || text.charAt(i) > '~') return false;
        }
        return true;
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try {
            return base64(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String base64(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private AccountLoginProtocol() {}
}
