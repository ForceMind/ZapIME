// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class AccountLoginProtocolSmoke {
    private static final String ISSUER = "https://login.example.test/provider";
    private static final String CALLBACK = "https://return.example.test/android/login";
    private static final String RESOURCE = "https://sync.example.test/device-enrollment";
    private static final String SERVER = "https://sync.example.test";

    public static void main(String[] args) throws Exception {
        AccountLoginProtocol.Config config = config(CALLBACK, ISSUER + "/token", RESOURCE);
        AccountLoginProtocol.Attempt first = new AccountLoginProtocol.Attempt(config, 100);
        Map<String, String> request = parse(first.authorization().getRawQuery());
        same("S256", request.get("code_challenge_method"));
        same(CALLBACK, request.get("redirect_uri"));
        same(RESOURCE, request.get("resource"));
        String state = request.get("state");
        String reply = CALLBACK + "?code=fixture%2Dcode&state=" + state + "&iss=" + ISSUER;
        reject("login_callback", () -> first.accept(reply.replace("return.example", "other.example"), 110));
        reject("login_callback", () -> first.accept(reply.replace("/android/login?", "/android/login/../login?"), 110));
        reject("login_callback", () -> first.accept(reply + "&%73tate=" + state, 110));
        reject("login_callback", () -> first.accept(reply + "&code=second", 110));
        reject("login_callback", () -> first.accept(reply.replace(state, state + "x"), 110));
        reject("login_callback", () -> first.accept(reply.replace("login.example", "other.example"), 110));
        reject("login_callback", () -> first.accept(reply + "#fragment", 110));
        Map<String, String> exchange = parse(new String(first.accept(reply, 110).body(), StandardCharsets.US_ASCII));
        same("fixture-code", exchange.get("code"));
        same(RESOURCE, exchange.get("resource"));
        same("authorization_code", exchange.get("grant_type"));
        String verifier = exchange.get("code_verifier");
        if (verifier.length() != 43 || state.length() != 43 || verifier.equals(state)) {
            throw new AssertionError("state and verifier need unique independent randomness");
        }
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        same(request.get("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(hashed));
        reject("login_expired", () -> first.accept(reply, 111));

        AccountLoginProtocol.Attempt cancelled = new AccountLoginProtocol.Attempt(config, 5);
        String denialState = parse(cancelled.authorization().getRawQuery()).get("state");
        String declined = CALLBACK + "?error=access_denied&state=" + denialState;
        reject("login_denied", () -> cancelled.accept(declined, 6));
        reject("login_expired", () -> cancelled.accept(declined, 7));
        AccountLoginProtocol.Attempt expired = new AccountLoginProtocol.Attempt(config, 10);
        reject("login_expired", () -> expired.accept(reply, 300010));
        expired.cancel();
        reject("login_expired", () -> expired.accept(reply, 11));
        reject("login_config", () -> config(CALLBACK.replace("https", "http"), ISSUER + "/token", RESOURCE));
        reject("login_config", () -> config(CALLBACK + "#fragment", ISSUER + "/token", RESOURCE));
        reject("login_config", () -> config(CALLBACK, "https://redirect.example.test/token", RESOURCE));
        reject("login_config", () -> config(CALLBACK, ISSUER + "/token", RESOURCE.replace("sync", "other")));
        if (!config.syncServer.equals(URI.create(SERVER))) throw new AssertionError("sync target");
        System.out.println("AccountLoginProtocolSmoke passed");
    }

    private static AccountLoginProtocol.Config config(String callback, String token, String resource) throws Exception {
        return new AccountLoginProtocol.Config(ISSUER, ISSUER + "/auth", token, callback, resource, SERVER, "android-public");
    }

    private static Map<String, String> parse(String query) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            result.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
        }
        return result;
    }

    private static void same(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("login parameter mismatch");
    }

    private static void reject(String code, Action action) throws Exception {
        try {
            action.run();
        } catch (SyncHttp.Failure rejected) {
            same(code, rejected.code);
            return;
        }
        throw new AssertionError("accepted invalid login input");
    }

    private interface Action {
        void run() throws Exception;
    }
}
