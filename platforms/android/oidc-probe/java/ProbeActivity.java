// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.a3loginprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertPathValidatorException;
import java.security.cert.PKIXReason;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import org.json.JSONObject;

/** Test-only Activity. Its fixed marker file cannot contain a credential or URL. */
public final class ProbeActivity extends Activity {
    private static final String ISSUER = "https://a3-oidc.example.test";
    private static final String RETURN_HOST = "return-a3.example.test";
    private static final String REDIRECT = "https://" + RETURN_HOST + "/callback";
    private static final String CLIENT = "zapime-android-synthetic";
    private static final String RESOURCE = ISSUER + "/a3-device-enrollment";
    private static final String SCOPE = "openid zapime.device_enrollment";
    private static final Executor WORKER = Executors.newSingleThreadExecutor();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LIMIT = 16 * 1024;
    private static Pending pending;
    private TextView status;
    private Button begin;

    private static final class Pending {
        final String state = random();
        final String verifier = random();
        final String nonce = random();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 48, 32, 32);
        status = new TextView(this);
        page.addView(status);
        begin = new Button(this);
        begin.setText("Only sign in");
        begin.setAllCaps(false);
        begin.setOnClickListener(view -> openAuthorization());
        page.addView(begin);
        setContentView(page);
        act(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        act(intent);
    }

    private void act(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            acceptCallback(intent.getData());
        } else if ("tls".equals(intent.getStringExtra("mode"))) {
            begin.setEnabled(false);
            WORKER.execute(this::checkDefaultTrust);
        } else {
            mark("ready");
        }
    }

    private void mark(String value) {
        try (FileOutputStream out = openFileOutput("phase", MODE_PRIVATE)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException failure) {
            return;
        }
        runOnUiThread(() -> status.setText(value));
    }

    private void openAuthorization() {
        begin.setEnabled(false);
        try {
            Pending next = new Pending();
            String challenge = encode(MessageDigest.getInstance("SHA-256")
                    .digest(next.verifier.getBytes(StandardCharsets.US_ASCII)));
            Uri authorization = Uri.parse(ISSUER + "/auth").buildUpon()
                    .appendQueryParameter("response_type", "code").appendQueryParameter("client_id", CLIENT)
                    .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("scope", SCOPE)
                    .appendQueryParameter("resource", RESOURCE).appendQueryParameter("state", next.state)
                    .appendQueryParameter("nonce", next.nonce).appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256").build();
            pending = next;
            mark("external_browser_opening");
            startActivity(new Intent(Intent.ACTION_VIEW, authorization).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception failure) {
            pending = null;
            mark("external_browser_failed");
        }
    }

    private void acceptCallback(Uri uri) {
        Pending current = pending;
        if (current == null || uri == null || !uri.isHierarchical() || !"https".equals(uri.getScheme()) ||
                !RETURN_HOST.equals(uri.getEncodedAuthority()) || !"/callback".equals(uri.getEncodedPath()) ||
                uri.getFragment() != null || !uri.getQueryParameters("error").isEmpty()) {
            mark("unexpected_callback");
            return;
        }
        List<String> state = uri.getQueryParameters("state");
        List<String> code = uri.getQueryParameters("code");
        List<String> issuer = uri.getQueryParameters("iss");
        if (state.size() != 1 || !current.state.equals(state.get(0)) || code.size() != 1 ||
                code.get(0).isEmpty() || code.get(0).length() > 512 ||
                (!issuer.isEmpty() && (issuer.size() != 1 || !ISSUER.equals(issuer.get(0))))) {
            mark("callback_state_or_issuer_rejected");
            return;
        }
        pending = null;
        begin.setEnabled(false);
        mark("verified_callback_received");
        WORKER.execute(() -> exchange(current, code.get(0)));
    }

    private void exchange(Pending request, String code) {
        try {
            String form = "grant_type=authorization_code&client_id=" + Uri.encode(CLIENT)
                    + "&redirect_uri=" + Uri.encode(REDIRECT) + "&resource=" + Uri.encode(RESOURCE)
                    + "&code=" + Uri.encode(code) + "&code_verifier=" + Uri.encode(request.verifier);
            JSONObject tokens = new JSONObject(call("/token", "application/x-www-form-urlencoded", form));
            JSONObject proof = new JSONObject().put("accessToken", tokens.getString("access_token"))
                    .put("idToken", tokens.getString("id_token")).put("nonce", request.nonce);
            if (!new JSONObject(call("/android-proof", "application/json", proof.toString())).getBoolean("ok")) {
                mark("provider_token_rejected");
                return;
            }
            mark("signed_in_only");
        } catch (Exception failure) {
            mark("token_exchange_failed");
        }
    }

    private void checkDefaultTrust() {
        try {
            call("/.well-known/openid-configuration", null, null);
            mark("tls_trusted");
        } catch (SSLHandshakeException rejected) {
            for (Throwable cause = rejected.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof CertPathValidatorException &&
                        (((CertPathValidatorException) cause).getReason() == PKIXReason.NO_TRUST_ANCHOR ||
                         "Trust anchor for certification path not found.".equals(cause.getMessage()))) {
                    mark("tls_rejected");
                    return;
                }
            }
            mark("tls_other_handshake_failure");
        } catch (IOException failure) {
            mark("tls_transport_failed");
        }
    }

    private static String call(String path, String type, String body) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(ISSUER + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", type);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            if (connection.getResponseCode() != 200) throw new IOException("status");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (out.size() + count > LIMIT) throw new IOException("bound");
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
