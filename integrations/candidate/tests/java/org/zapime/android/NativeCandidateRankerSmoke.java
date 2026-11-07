// SPDX-License-Identifier: GPL-3.0-only
// Actual JNI/real-model coverage on a host JVM; not Android/UI acceptance.
package org.zapime.android;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeCandidateRankerSmoke {
    private static int checks;
    private static void check(boolean success, String code) {
        if (!success) throw new AssertionError(code);
        checks++;
    }
    private static String repeated(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; ++i) result.append(text);
        return result.toString();
    }
    private static boolean finite(float[] values, int size) {
        if (values == null || values.length != size) return false;
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }
    private static String sha(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("model_runtime_report_required");
        String model = args[0], runtime = args[1];
        String modelSha = sha(Files.readAllBytes(Paths.get(model, "lm_step.ort")));
        check(modelSha.equals("e036febfb8f7bdf941f1637be127ff539ea95b937a4c2a27f4c6ab897138fc6c"),
                "release_sha");
        long handle = NativeCandidateRanker.open(model, runtime);
        check(handle != 0, "open_real_model");
        try {
            String[] rows = {"拟好", "逆号", "你好"};
            float[] first = NativeCandidateRanker.score(handle, "朋友见面先说", rows);
            check(finite(first, 3), "real_scores");
            check(first[2] > first[0] && first[0] > first[1], "real_greeting_order");
            check(Arrays.equals(first, NativeCandidateRanker.score(handle, "朋友见面先说", rows)), "warm_cache_equal");
            check(NativeCandidateRanker.clear(handle), "clear");
            check(Arrays.equals(first, NativeCandidateRanker.score(handle, "朋友见面先说", rows)), "clear_reload_equal");

            check(finite(NativeCandidateRanker.score(handle, "", new String[]{"你", "𠮷", "😀", "a\u0301"}), 4),
                    "utf16_supplementary_combining");
            check(NativeCandidateRanker.score(handle, "", new String[]{"\ud800"}) == null, "isolated_high_surrogate");
            check(NativeCandidateRanker.score(handle, "", new String[]{"\udc00"}) == null, "isolated_low_surrogate");
            check(NativeCandidateRanker.score(handle, "", new String[]{repeated("你", 65)}) == null, "scalar_limit");
            check(finite(NativeCandidateRanker.score(handle, "", new String[]{repeated("😀", 64)}), 1), "64_scalars_128_units");
            String tail = repeated("你", 64);
            float[] shortContext = NativeCandidateRanker.score(handle, tail, rows);
            float[] clippedContext = NativeCandidateRanker.score(handle, "😀" + repeated("你", 127), rows);
            check(finite(shortContext, 3) && Arrays.equals(shortContext, clippedContext), "context_cut_at_surrogate_pair");

            check(NativeCandidateRanker.score(handle, null, rows) == null, "null_context");
            check(NativeCandidateRanker.score(handle, "", null) == null, "null_pool");
            check(NativeCandidateRanker.score(handle, "", new String[0]) == null, "empty_pool");
            check(NativeCandidateRanker.score(handle, "", new String[]{null}) == null, "null_row");
            check(NativeCandidateRanker.score(handle, "", new String[]{""}) == null, "empty_row");
            String[] large = new String[33];
            Arrays.fill(large, "你");
            check(NativeCandidateRanker.score(handle, "", large) == null, "pool_limit");
            check(finite(NativeCandidateRanker.score(handle, "", rows), 3), "invalid_input_recovery");

            AtomicReference<Throwable> failure = new AtomicReference<>();
            final long active = handle;
            Thread foreign = new Thread(() -> {
                try {
                    if (NativeCandidateRanker.score(active, "", rows) != null ||
                            NativeCandidateRanker.clear(active) || NativeCandidateRanker.close(active))
                        throw new AssertionError("foreign_owner");
                    NativeCandidateRanker.cancel(active);
                } catch (Throwable error) { failure.set(error); }
            }, "candidate-foreign-probe");
            foreign.start(); foreign.join();
            check(failure.get() == null, "foreign_owner_and_cancel");
            check(NativeCandidateRanker.score(handle, "", rows) == null, "cancel_sticky_first");
            check(NativeCandidateRanker.score(handle, "", rows) == null, "cancel_sticky_second");
            check(NativeCandidateRanker.clear(handle), "clear_cancel");
            check(finite(NativeCandidateRanker.score(handle, "", rows), 3), "reuse_after_cancel");

            long second = NativeCandidateRanker.open(model, runtime);
            check(second != 0 && second != handle, "second_distinct_handle");
            try { check(NativeCandidateRanker.open(model, runtime) == 0, "handle_capacity"); }
            finally { check(NativeCandidateRanker.close(second), "second_close"); }
            check(NativeCandidateRanker.open("relative/model", runtime) == 0, "relative_path_rejected");
            check(NativeCandidateRanker.open(model, "/nonexistent/zapime-test-runtime.so") == 0, "missing_runtime_rejected");
        } finally { check(NativeCandidateRanker.close(handle), "close_owner"); }
        check(!NativeCandidateRanker.close(handle), "double_close_rejected");
        check(NativeCandidateRanker.score(handle, "", new String[]{"你"}) == null, "closed_score_rejected");
        check(!NativeCandidateRanker.clear(handle), "closed_clear_rejected");
        NativeCandidateRanker.cancel(handle);
        long reopened = NativeCandidateRanker.open(model, runtime);
        check(reopened != 0 && reopened != handle, "reopen_no_handle_reuse");
        check(NativeCandidateRanker.close(reopened), "reopen_close");
        String report = "{\"schema_version\":1,\"passed\":true,\"scope\":\"host_jvm_real_jni_real_model_not_android\","
                + "\"checks\":" + checks + ",\"model_sha256\":\"" + modelSha + "\","
                + "\"runtime_sha256\":\"" + sha(Files.readAllBytes(Paths.get(runtime))) + "\"}\n";
        Files.write(Paths.get(args[2]), report.getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }
}
