// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real APK model + JNI + MSIME + independent Editable; never an activated IME. */
final class CandidateRankingProbe {
    private static final String MODEL_ID = "msime-keystroke-8m-rerank-v2";
    private static final String MODEL_SHA = "e036febfb8f7bdf941f1637be127ff539ea95b937a4c2a27f4c6ab897138fc6c";
    private static final String FIXTURE_ID = "candidate-ranking-android-v1";
    // Frozen before inference. The small dictionary controls the prior only;
    // every score and resulting permutation comes from the real bundled model.
    private static final String FIXTURE = "ni/你/900\nhao/好/900\nni'hao/拟好/900\nni'hao/逆号/800\nni'hao/你好/100\ncontext=朋友见面先说\nscore-order=拟好|逆号|你好\n";
    private static final String CONTEXT = "朋友见面先说";
    private static final String[] WORDS = {"拟好", "逆号", "你好"};
    private static final float[] REFERENCE = {-9.413619995117188f, -11.37354850769043f, -3.759044647216797f};
    private static final double TOLERANCE = 0.001;
    private static final String SCOPE = "Actual Android JNI on APK-pinned 8M model and real MSIME with a synthetic lexicon; original-ID selection enters production trackCoreCommit and an independent EditText Editable. Not activated system IME, physical taps, phone latency, active-inference kill or heap-leak acceptance.";

    private interface Task<T> { T run() throws Exception; }
    private final Instrumentation instrumentation;
    private final Context base;
    private final File output;
    private final JSONArray cases = new JSONArray();
    private final JSONObject report = new JSONObject();
    private final String preferencePrefix = "candidate-ranking-probe-" + UUID.randomUUID() + "-";
    private Class<?> nativeRanker, core, installer, resources;
    private File fixtureDirectory, model, runtime;
    private Context fixtureContext;
    private long rankHandle, coreHandle, privateHandle;
    private String stage = "initialization";
    private int scoreCalls, completedScores;
    private boolean realModel, realCore, editableVerified, defaultVerified, privateVerified;
    private JSONObject actualRankedResult;

    private CandidateRankingProbe(Instrumentation instrumentation, File output) {
        this.instrumentation = instrumentation;
        this.base = instrumentation.getTargetContext();
        this.output = output;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static Object invoke(Class<?> type, Object owner, String name, Class<?>[] signature, Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(owner, arguments); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw wrapped;
        }
    }

    private static Object field(Class<?> type, Object owner, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Class<?> type, Object owner, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private <T> T main(Task<T> task) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(task.run()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new Exception("candidate synthetic main task", failure.get());
        return value.get();
    }

    private void passed(String id) throws Exception {
        cases.put(new JSONObject().put("id", id).put("passed", true));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item & 255) >>> 4, 16));
            value.append(Character.forDigit(item & 15, 16));
        }
        return value.toString();
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) if (count != 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private JSONObject coreRequest(long handle, JSONObject request) throws Exception {
        String wire = (String)invoke(core, null, "request", new Class<?>[]{long.class, String.class}, handle, request.toString());
        check(wire != null && wire.length() < 65536, "bounded native core reply");
        return new JSONObject(wire);
    }

    private JSONObject successful(long handle, JSONObject request) throws Exception {
        JSONObject reply = coreRequest(handle, request);
        check(!reply.has("error"), "native core request rejected");
        return reply;
    }

    private JSONObject typeCode(long handle) throws Exception {
        successful(handle, new JSONObject().put("op", "reset"));
        successful(handle, new JSONObject().put("op", "layout").put("name", "qwerty"));
        JSONObject result = null;
        for (char c : "nihao".toCharArray()) result = successful(handle, new JSONObject().put("op", "key").put("text", String.valueOf(c)));
        check(result != null, "synthetic composition missing");
        return result;
    }

    private float[] score(long handle, String context, String[] candidates) throws Exception {
        ++scoreCalls;
        float[] result = (float[])invoke(nativeRanker, null, "score", new Class<?>[]{long.class, String.class, String[].class}, handle, context, candidates);
        if (result != null) {
            check(result.length == candidates.length, "native score count");
            for (float value : result) check(!Float.isNaN(value) && !Float.isInfinite(value), "non-finite native score");
            ++completedScores;
        }
        return result;
    }

    private boolean rankBoolean(String method, long handle) throws Exception {
        return (Boolean)invoke(nativeRanker, null, method, new Class<?>[]{long.class}, handle);
    }

    private long openRanker(File directory) throws Exception {
        return (Long)invoke(nativeRanker, null, "open", new Class<?>[]{String.class, String.class}, directory.getAbsolutePath(), runtime.getAbsolutePath());
    }

    private static void sameScores(float[] actual, float[] expected) {
        check(actual != null && actual.length == expected.length, "native score result missing");
        for (int i = 0; i < expected.length; ++i) check(Math.abs(actual[i] - expected[i]) <= TOLERANCE, "fixed native score tolerance");
    }

    private void sameView(long handle, JSONObject expected) throws Exception {
        JSONObject current = successful(handle, new JSONObject().put("op", "view"));
        check(current.toString().equals(expected.toString()), "rejected operation changed core view");
    }

    private void initialize() throws Exception {
        check(Looper.myLooper() != Looper.getMainLooper(), "candidate model probe requires instrumentation worker");
        ClassLoader loader = base.getClassLoader();
        nativeRanker = Class.forName("org.zapime.android.NativeCandidateRanker", true, loader);
        core = Class.forName("org.zapime.android.NativeCore", true, loader);
        installer = Class.forName("org.zapime.android.ResourceInstaller", true, loader);
        resources = Class.forName("org.zapime.android.CandidateRankingResources", true, loader);
        fixtureDirectory = Files.createTempDirectory(base.getFilesDir().getCanonicalFile().toPath(), "candidate-ranking-probe-").toFile();
        fixtureContext = new ContextWrapper(base) {
            @Override public File getFilesDir() { return fixtureDirectory; }
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return base.getSharedPreferences(preferencePrefix + name, mode);
            }
        };
        runtime = new File(base.getApplicationInfo().nativeLibraryDir, "libonnxruntime.so");
        check(runtime.isFile(), "APK ONNX Runtime missing");
        report.put("runtime_sha256", digest(runtime));
    }

    private void preferenceCase() throws Exception {
        stage = "default_on_preference";
        Class<?> preferences = Class.forName("org.zapime.android.CandidateRankingPreferences", true, base.getClassLoader());
        SharedPreferences isolated = fixtureContext.getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        check(!isolated.contains("candidate_ranking_enabled"), "fresh preference fixture already set");
        check((Boolean)invoke(preferences, null, "enabled", new Class<?>[]{Context.class}, fixtureContext), "candidate ranking not on by default");
        check(isolated.edit().putBoolean("candidate_ranking_enabled", false).commit(), "isolated preference save");
        check(!(Boolean)invoke(preferences, null, "enabled", new Class<?>[]{Context.class}, fixtureContext), "explicit local off ignored");
        check(isolated.edit().remove("candidate_ranking_enabled").commit(), "isolated preference reset");
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT;
        Class<?>[] signature = {EditorInfo.class, boolean.class};
        check((Boolean)invoke(preferences, null, "eligible", signature, info, false), "ordinary text not eligible");
        check(!(Boolean)invoke(preferences, null, "eligible", signature, info, true), "private editor eligible");
        info.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        check(!(Boolean)invoke(preferences, null, "eligible", signature, info, false), "no-learning editor eligible");
        info.imeOptions = EditorInfo.IME_ACTION_SEARCH;
        check(!(Boolean)invoke(preferences, null, "eligible", signature, info, false), "search editor eligible");
        defaultVerified = true;
        passed(stage);
    }

    private void resourceCase() throws Exception {
        stage = "pinned_apk_resources";
        Class<?> catalog = Class.forName("org.zapime.android.CandidateModelCatalog", true, base.getClassLoader());
        check(MODEL_ID.equals(field(catalog, null, "MODEL_ID")), "candidate catalog model identity");
        report.put("model_revision", (String)field(catalog, null, "REVISION"));
        model = (File)invoke(installer, null, "prepareCandidateRanking", new Class<?>[]{Context.class}, fixtureContext);
        check((Boolean)invoke(resources, null, "verify", new Class<?>[]{File.class}, model), "APK candidate resource verification");
        check(MODEL_SHA.equals(digest(new File(model, "lm_step.ort"))), "fixed model SHA differs");
        check(model.getCanonicalFile().toPath().startsWith(fixtureDirectory.getCanonicalFile().toPath()), "model escaped isolated fixture");
        passed(stage);

        // Copy the ordinary packaged resources through the production installer,
        // then replace only this test-owned pinyin database with a frozen lexicon.
        invoke(installer, null, "prepare", new Class<?>[]{Context.class}, fixtureContext);
        File pinyin = new File(fixtureDirectory, "zapime/resources/msime-pinyin.db");
        check(pinyin.isFile() && pinyin.delete(), "replace isolated pinyin fixture");
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(pinyin, null)) {
            db.execSQL("CREATE TABLE tbl_1_n(key TEXT,jp TEXT,value TEXT,weight INTEGER)");
            db.execSQL("INSERT INTO tbl_1_n VALUES('ni','n','你',900)");
            db.execSQL("CREATE TABLE tbl_1_h(key TEXT,jp TEXT,value TEXT,weight INTEGER)");
            db.execSQL("INSERT INTO tbl_1_h VALUES('hao','h','好',900)");
            db.execSQL("CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER)");
            for (int i = 0; i < WORDS.length; ++i) {
                db.execSQL("INSERT INTO tbl_2_n VALUES(?,?,?,?)", new Object[]{"ni'hao", "nh", WORDS[i], new int[]{900, 800, 100}[i]});
            }
        }
        coreHandle = (Long)invoke(core, null, "open", new Class<?>[]{String.class, boolean.class}, new File(fixtureDirectory, "zapime").getAbsolutePath(), false);
        check(coreHandle != 0, "real MSIME fixture initialization");
        realCore = true;
    }

    private void rankingCases() throws Exception {
        stage = "real_model_msime_pool";
        JSONObject original = typeCode(coreHandle);
        JSONObject ranking = original.getJSONObject("ranking");
        check(ranking.getBoolean("enabled") && !ranking.getBoolean("applied"), "core default ranking policy");
        JSONArray pool = ranking.getJSONArray("candidates");
        check(pool.length() == WORDS.length, "fixed MSIME full-candidate pool changed");
        String[] texts = new String[pool.length()], ids = new String[pool.length()];
        Set<String> observed = new HashSet<>();
        for (int i = 0; i < pool.length(); ++i) {
            texts[i] = pool.getJSONObject(i).getString("text");
            ids[i] = pool.getJSONObject(i).getString("id");
            check(observed.add(texts[i]), "duplicate MSIME fixture text");
        }
        check(observed.equals(new HashSet<>(Arrays.asList(WORDS))), "MSIME fixture candidate set changed");
        long begin = SystemClock.elapsedRealtimeNanos();
        rankHandle = openRanker(model);
        report.put("model_load_ms", (SystemClock.elapsedRealtimeNanos() - begin) / 1000000.0);
        check(rankHandle != 0, "real bundled model did not load");
        float[] scores = score(rankHandle, CONTEXT, texts);
        check(scores != null, "real bundled model did not score");
        realModel = true;
        JSONArray canonicalScores = new JSONArray();
        for (String word : WORDS) canonicalScores.put((double)scores[Arrays.asList(texts).indexOf(word)]);
        report.put("scores", canonicalScores);
        for (int i = 0; i < WORDS.length; ++i) check(Math.abs(canonicalScores.getDouble(i) - REFERENCE[i]) <= TOLERANCE, "real model fixed reference score");
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; ++i) order[i] = i;
        Arrays.sort(order, (a, b) -> scores[a] == scores[b] ? Integer.compare(a, b) : Float.compare(scores[b], scores[a]));
        check("你好".equals(texts[order[0]]), "fixed model winning candidate");
        JSONArray orderedIds = new JSONArray();
        for (int index : order) orderedIds.put(ids[index]);
        JSONObject rankRequest = new JSONObject().put("op", "candidate_rank").put("generation", original.getLong("generation"))
                .put("model", MODEL_ID).put("ids", orderedIds);
        passed(stage);

        stage = "invalid_rank_requests_preserve_view";
        JSONArray foreignIds = new JSONArray(orderedIds.toString());
        foreignIds.put(0, "engine:999999");
        JSONArray duplicateIds = new JSONArray();
        for (int i = 0; i < ids.length; ++i) duplicateIds.put(ids[0]);
        for (JSONObject invalid : new JSONObject[]{
                new JSONObject(rankRequest.toString()).put("generation", original.getLong("generation") - 1),
                new JSONObject(rankRequest.toString()).put("ids", foreignIds),
                new JSONObject(rankRequest.toString()).put("ids", duplicateIds),
                new JSONObject(rankRequest.toString()).put("model", "unknown-model")}) {
            check(coreRequest(coreHandle, invalid).has("error"), "invalid rank request accepted");
            sameView(coreHandle, original);
        }
        passed(stage);

        stage = "model_rank_changes_display_only";
        JSONObject ranked = successful(coreHandle, rankRequest);
        actualRankedResult = new JSONObject(ranked.toString());
        check(ranked.getJSONObject("ranking").getBoolean("applied"), "model ranking not applied");
        check(ranked.isNull("commit") && "none".equals(ranked.getString("commit_source")), "ranking committed text");
        check(ranked.getString("preedit").equals(original.getString("preedit")), "ranking rewrote composition");
        check(ranked.getLong("composition_revision") == original.getLong("composition_revision"), "ranking changed input revision");
        check(ranked.getLong("generation") != original.getLong("generation"), "fixed model did not change display order");
        check("你好".equals(ranked.getJSONArray("candidates").getString(0)), "model order did not reach display");
        passed(stage);

        stage = "stale_generation_and_bad_id";
        JSONObject stale = coreRequest(coreHandle, new JSONObject().put("op", "select").put("index", 0).put("generation", original.getLong("generation")));
        check("stale_generation".equals(stale.optString("error")), "old display generation accepted");
        JSONObject foreign = coreRequest(coreHandle, new JSONObject().put("op", "select").put("index", 0)
                .put("generation", ranked.getLong("generation")).put("id", "engine:999999"));
        check("stale_candidate_id".equals(foreign.optString("error")), "foreign candidate ID accepted");
        sameView(coreHandle, ranked);
        passed(stage);

        stage = "original_id_click_after_rank_commits_editable";
        int oldIndex = -1, newIndex = -1;
        JSONArray oldIds = original.getJSONArray("candidate_ids"), newIds = ranked.getJSONArray("candidate_ids");
        for (int i = 0; i < oldIds.length() && oldIndex < 0; ++i) {
            String id = oldIds.getString(i);
            if (!Arrays.asList(ids).contains(id)) continue;
            for (int j = 0; j < newIds.length(); ++j) if (id.equals(newIds.getString(j)) && i != j) { oldIndex = i; newIndex = j; break; }
        }
        check(oldIndex >= 0 && newIndex >= 0, "no actually moved complete candidate");
        String expected = original.getJSONArray("candidates").getString(oldIndex);
        JSONObject selected = successful(coreHandle, new JSONObject().put("op", "select").put("index", oldIndex)
                .put("id", oldIds.getString(oldIndex)).put("generation", original.getLong("generation"))
                .put("composition_revision", original.getLong("composition_revision")));
        check(expected.equals(selected.getString("commit")) && "engine".equals(selected.getString("commit_source")), "old click changed candidate identity");
        check(selected.getString("preedit").isEmpty(), "complete selection left composition");
        commitToEditable(selected, expected);
        report.put("old_click_index", oldIndex).put("ranked_index_for_same_id", newIndex);
        passed(stage);

        stage = "old_composition_revision_rejected";
        JSONObject previous = typeCode(coreHandle);
        JSONObject changed = successful(coreHandle, new JSONObject().put("op", "command").put("name", "Backspace"));
        check(changed.getLong("composition_revision") != previous.getLong("composition_revision"), "backspace did not change input revision");
        JSONObject oldClick = coreRequest(coreHandle, new JSONObject().put("op", "select").put("index", 0)
                .put("id", previous.getJSONArray("candidate_ids").getString(0)).put("generation", previous.getLong("generation"))
                .put("composition_revision", previous.getLong("composition_revision")));
        check("stale_generation".equals(oldClick.optString("error")), "old composition candidate accepted");
        sameView(coreHandle, changed);
        passed(stage);
    }

    private void commitToEditable(JSONObject selected, String expected) throws Exception {
        List<ExecutorService> executors = new ArrayList<>();
        try {
            main(() -> {
                Class<?> type = Class.forName("org.zapime.android.ZapInputMethodService", true, base.getClassLoader());
                Object service = type.getDeclaredConstructor().newInstance();
                try {
                    for (Field value : type.getDeclaredFields()) if (ExecutorService.class.isAssignableFrom(value.getType())) {
                        value.setAccessible(true);
                        executors.add((ExecutorService)value.get(service));
                    }
                    EditText editor = new EditText(new ContextThemeWrapper(base, android.R.style.Theme_Material_Light_NoActionBar));
                    String before = "合成前缀：", after = "：合成后缀";
                    editor.setText(before + after);
                    editor.setSelection(before.length());
                    AtomicInteger commits = new AtomicInteger();
                    InputConnection connection = new BaseInputConnection(editor, true) {
                        @Override public Editable getEditable() { return editor.getText(); }
                        @Override public boolean commitText(CharSequence text, int cursor) {
                            check(Looper.myLooper() == Looper.getMainLooper(), "candidate commit left main thread");
                            commits.incrementAndGet();
                            return super.commitText(text, cursor);
                        }
                        @Override public CharSequence getTextBeforeCursor(int length, int flags) { throw new AssertionError("candidate commit read remote context"); }
                        @Override public CharSequence getTextAfterCursor(int length, int flags) { throw new AssertionError("candidate commit read remote context"); }
                    };
                    Object echoes = field(type, service, "selectionEcho");
                    invoke(echoes.getClass(), echoes, "reset", new Class<?>[]{int.class, int.class}, before.length(), before.length());
                    Class<?> ticket = Class.forName("org.zapime.android.EnglishLearningSession$Ticket", true, base.getClassLoader());
                    invoke(type, service, "trackCoreCommit", new Class<?>[]{InputConnection.class, JSONObject.class, ticket}, connection, selected, null);
                    check(commits.get() == 1 && (before + expected + after).contentEquals(editor.getText()), "production core commit did not match actual Editable");
                    editableVerified = true;
                    return null;
                } finally {
                    // This service instance is never attached or activated. Do
                    // not run its lifecycle or disturb the actual system IME.
                    for (ExecutorService executor : executors) executor.shutdownNow();
                }
            });
        } finally {
            for (ExecutorService executor : executors) check(executor.awaitTermination(5, TimeUnit.SECONDS), "synthetic service executor retained");
        }
    }

    private void privateCase() throws Exception {
        stage = "private_pool_cannot_enable";
        privateHandle = (Long)invoke(core, null, "open", new Class<?>[]{String.class, boolean.class}, new File(fixtureDirectory, "zapime").getAbsolutePath(), true);
        check(privateHandle != 0, "private native core initialization");
        JSONObject view = typeCode(privateHandle);
        check(!view.getJSONObject("ranking").getBoolean("enabled") && view.getJSONObject("ranking").getJSONArray("candidates").length() == 0, "private core exported candidates");
        JSONObject enabled = successful(privateHandle, new JSONObject().put("op", "candidate_ranking").put("enabled", true));
        check(!enabled.getJSONObject("ranking").getBoolean("enabled") && enabled.getJSONObject("ranking").getJSONArray("candidates").length() == 0, "private ranking explicitly enabled");
        JSONObject rejected = coreRequest(privateHandle, new JSONObject().put("op", "candidate_rank").put("generation", enabled.getLong("generation"))
                .put("model", MODEL_ID).put("ids", new JSONArray()));
        check("candidate_ranking_disabled".equals(rejected.optString("error")), "private rank result accepted");
        privateVerified = true;
        passed(stage);
    }

    private void blockedPublicationCase() throws Exception {
        stage = "blocked_rank_publication_preserves_view";
        check(actualRankedResult != null, "actual ranked result missing from publication probe");
        List<ExecutorService> executors = new ArrayList<>();
        try {
            main(() -> {
                ClassLoader loader = base.getClassLoader();
                Class<?> type = Class.forName("org.zapime.android.ZapInputMethodService", true, loader);
                Object service = type.getDeclaredConstructor().newInstance();
                Object touches = field(type, service, "touches");
                Object replies = field(type, service, "inputReplies");
                try {
                    for (Field value : type.getDeclaredFields()) if (ExecutorService.class.isAssignableFrom(value.getType())) {
                        value.setAccessible(true);
                        executors.add((ExecutorService)value.get(service));
                    }
                    LinearLayout visible = new LinearLayout(new ContextThemeWrapper(base, android.R.style.Theme_Material_Light_NoActionBar));
                    Button original = new Button(visible.getContext());
                    original.setText("固定可见候选");
                    visible.addView(original);
                    setField(type, service, "candidates", visible);
                    setField(type, service, "generation", 17L);
                    setField(type, service, "rankedSnapshots", 7);
                    setField(type, service, "rankingVisible", true);
                    setField(type, service, "rankingEligible", true);
                    setField(type, service, "ready", true);
                    setField(type, service, "chinese", true);
                    // A deterministic tripwire: if the blocked-path gate is
                    // removed, canRank reaches layer.equals and fails. Merely
                    // having a null unattached InputConnection must not make a
                    // missing early guard pass this test by accident.
                    setField(type, service, "layer", null);
                    setField(type, service, "preedit", "ni'hao");
                    JSONObject before = new JSONObject().put("synthetic_visible", true);
                    setField(type, service, "latestCandidateView", before);
                    Class<?> requestType = Class.forName("org.zapime.android.CandidateRankingWorker$Request", true, loader);
                    Constructor<?> requestConstructor = requestType.getDeclaredConstructor(int.class, long.class, long.class, long.class,
                            String.class, String[].class, String[].class, long.class);
                    requestConstructor.setAccessible(true);
                    Class<?> bindingType = Class.forName("org.zapime.android.KeyboardTouch$Binding", true, loader);
                    Constructor<?> bindingConstructor = bindingType.getDeclaredConstructor(touches.getClass(), Button.class, Runnable.class,
                            boolean.class, BooleanSupplier.class);
                    bindingConstructor.setAccessible(true);
                    Object binding = bindingConstructor.newInstance(touches, original, (Runnable)() -> {}, false, (BooleanSupplier)() -> true);
                    Class<?> ticket = Class.forName("org.zapime.android.EnglishLearningSession$Ticket", true, loader);
                    Class<?> replyType = Class.forName("org.zapime.android.ZapInputMethodService$InputReply", true, loader);
                    Constructor<?> replyConstructor = replyType.getDeclaredConstructor(int.class, JSONObject.class, String.class, int.class, ticket);
                    replyConstructor.setAccessible(true);
                    for (String blocked : new String[]{"active_touch", "marker_handoff", "held_reply"}) {
                        setField(touches.getClass(), touches, "active", null);
                        setField(type, service, "pendingMarkerLoss", null);
                        replies.getClass().getMethod("clear").invoke(replies);
                        if (blocked.equals("active_touch")) setField(touches.getClass(), touches, "active", binding);
                        if (blocked.equals("marker_handoff")) setField(type, service, "pendingMarkerLoss", (Runnable)() -> {});
                        if (blocked.equals("held_reply")) replies.getClass().getMethod("addLast", Object.class)
                                .invoke(replies, replyConstructor.newInstance(0, new JSONObject(), null, 0, null));
                        Object request = requestConstructor.newInstance(0, 17L, 0L, 0L, "", new String[]{"engine:0", "engine:1"}, new String[]{"拟好", "你好"}, 2000L);
                        check(!(Boolean)invoke(requestType, request, "expired", new Class<?>[0]), "publication fixture expired before call");
                        invoke(type, service, "publishRanking", new Class<?>[]{requestType, JSONObject.class}, request, actualRankedResult);
                        check(!(Boolean)invoke(requestType, request, "expired", new Class<?>[0]), "publication guard was masked by expiry");
                        check(((Long)field(type, service, "generation")) == 17L && ((Integer)field(type, service, "rankedSnapshots")) == 7,
                                "blocked publication changed display state");
                        check(field(type, service, "latestCandidateView") == before && visible.getChildCount() == 1 && visible.getChildAt(0) == original,
                                "blocked publication detached visible candidate");
                    }
                    return null;
                } finally {
                    setField(touches.getClass(), touches, "active", null);
                    setField(type, service, "pendingMarkerLoss", null);
                    replies.getClass().getMethod("clear").invoke(replies);
                    for (ExecutorService executor : executors) executor.shutdownNow();
                }
            });
        } finally {
            for (ExecutorService executor : executors) check(executor.awaitTermination(5, TimeUnit.SECONDS), "publication fixture executor retained");
        }
        passed(stage);
    }

    private void nativeLifecycleCases() throws Exception {
        stage = "clear_context_isolation";
        check(rankBoolean("clear", rankHandle), "native clear failed");
        float[] original = score(rankHandle, CONTEXT, WORDS);
        sameScores(original, REFERENCE);
        check(score(rankHandle, "另一位编辑器的合成上文", WORDS) != null, "second context did not score");
        check(rankBoolean("clear", rankHandle), "native context clear failed");
        sameScores(score(rankHandle, CONTEXT, WORDS), original);
        passed(stage);

        stage = "cross_thread_cancel_and_owner_guards";
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        final long active = rankHandle;
        Thread foreign = new Thread(() -> {
            try {
                Object values = invoke(nativeRanker, null, "score", new Class<?>[]{long.class, String.class, String[].class}, active, CONTEXT, WORDS);
                check(values == null && !rankBoolean("clear", active) && !rankBoolean("close", active), "foreign thread changed native state");
                invoke(nativeRanker, null, "cancel", new Class<?>[]{long.class}, active);
            } catch (Throwable failure) { foreignFailure.set(failure); }
        }, "candidate-probe-foreign-owner");
        foreign.start();
        foreign.join(5000);
        check(!foreign.isAlive() && foreignFailure.get() == null, "cross-thread native contract failed");
        check(score(rankHandle, CONTEXT, WORDS) == null, "cancel did not remain sticky");
        check(rankBoolean("clear", rankHandle), "cancelled session did not clear");
        sameScores(score(rankHandle, CONTEXT, WORDS), original);
        passed(stage);

        stage = "close_stale_handle_and_reopen";
        long closed = rankHandle;
        check(rankBoolean("close", closed), "native owner close failed");
        rankHandle = 0;
        check(!rankBoolean("close", closed) && !rankBoolean("clear", closed), "closed native handle remained active");
        check(score(closed, CONTEXT, WORDS) == null, "closed handle produced scores");
        invoke(nativeRanker, null, "cancel", new Class<?>[]{long.class}, closed);
        rankHandle = openRanker(model);
        check(rankHandle != 0 && rankHandle != closed, "native handle identity was reused");
        sameScores(score(rankHandle, CONTEXT, WORDS), original);
        passed(stage);
    }

    private void corruptionCase() throws Exception {
        stage = "corrupt_model_rejected_valid_bundle_preserved";
        Class<?> source = Class.forName("org.zapime.android.CandidateRankingResources$Source", true, base.getClassLoader());
        AtomicInteger sourceOpens = new AtomicInteger();
        Object damagedSource = Proxy.newProxyInstance(base.getClassLoader(), new Class<?>[]{source}, (proxy, method, arguments) -> {
            if (!method.getName().equals("open")) throw new AssertionError("unexpected candidate source method");
            sourceOpens.incrementAndGet();
            String name = (String)arguments[0];
            InputStream input = base.getAssets().open("candidate-model/" + name);
            if (!name.equals("lm_step.ort")) return input;
            return new FilterInputStream(input) {
                private boolean first = true;
                @Override public int read(byte[] data, int offset, int length) throws IOException {
                    int count = in.read(data, offset, length);
                    if (first && count > 0) { data[offset] ^= 1; first = false; }
                    return count;
                }
                @Override public int read() throws IOException {
                    int value = in.read();
                    if (first && value >= 0) { value ^= 1; first = false; }
                    return value;
                }
            };
        });
        File reused = (File)invoke(resources, null, "prepare", new Class<?>[]{File.class, source}, model.getParentFile(), damagedSource);
        check(reused.equals(model) && sourceOpens.get() == 0, "valid resource bundle was replaced or refetched");
        File badParent = new File(fixtureDirectory, "rejected-model-source");
        boolean rejected = false;
        try { invoke(resources, null, "prepare", new Class<?>[]{File.class, source}, badParent, damagedSource); }
        catch (IOException expected) { rejected = true; }
        check(rejected && sourceOpens.get() > 0 && !new File(badParent, model.getName()).exists(), "corrupt candidate source was published");
        check((Boolean)invoke(resources, null, "verify", new Class<?>[]{File.class}, model), "failed source damaged valid bundle");

        File corrupt = new File(fixtureDirectory, "invalid-native-model");
        check(corrupt.mkdir(), "fresh invalid model fixture");
        String[] members = model.list();
        check(members != null, "valid model list unavailable");
        for (String name : members) Files.copy(new File(model, name).toPath(), new File(corrupt, name).toPath());
        try (RandomAccessFile graph = new RandomAccessFile(new File(corrupt, "lm_step.ort"), "rw")) { graph.seek(0); graph.writeInt(0); }
        check(!(Boolean)invoke(resources, null, "verify", new Class<?>[]{File.class}, corrupt), "corrupt graph passed trusted verification");
        long invalid = openRanker(corrupt);
        if (invalid != 0) {
            try { rankBoolean("close", invalid); }
            finally { throw new AssertionError("native accepted a corrupt ORT graph"); }
        }
        check(MODEL_SHA.equals(digest(new File(model, "lm_step.ort"))), "corrupt fixture altered verified model");
        sameScores(score(rankHandle, CONTEXT, WORDS), REFERENCE);
        passed(stage);
    }

    private void execute() throws Exception {
        initialize();
        preferenceCase();
        resourceCase();
        rankingCases();
        blockedPublicationCase();
        privateCase();
        nativeLifecycleCases();
        corruptionCase();
    }

    private Throwable cleanup() {
        Throwable failure = null;
        try {
            if (rankHandle != 0) {
                check(rankBoolean("close", rankHandle), "final native handle release");
                rankHandle = 0;
            }
        } catch (Throwable error) { failure = error; }
        for (boolean privateSession : new boolean[]{false, true}) {
            long handle = privateSession ? privateHandle : coreHandle;
            if (handle == 0) continue;
            try {
                invoke(core, null, "close", new Class<?>[]{long.class}, handle);
                if (privateSession) privateHandle = 0; else coreHandle = 0;
            } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        try {
            check(base.deleteSharedPreferences(preferencePrefix + "keyboard"), "isolated preferences cleanup");
            if (fixtureDirectory != null && fixtureDirectory.exists()) {
                check(fixtureDirectory.getCanonicalFile().getParentFile().equals(base.getFilesDir().getCanonicalFile()), "fixture cleanup root mismatch");
                try (java.util.stream.Stream<Path> paths = Files.walk(fixtureDirectory.toPath())) {
                    for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
                }
            }
        } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        return failure;
    }

    static JSONObject run(Instrumentation instrumentation, File output) throws Exception {
        CandidateRankingProbe probe = new CandidateRankingProbe(instrumentation, output);
        Throwable failure = null;
        boolean complete = false;
        try { probe.execute(); complete = true; }
        catch (Throwable error) {
            failure = error;
            probe.cases.put(new JSONObject().put("id", probe.stage).put("passed", false).put("failure_type", error.getClass().getSimpleName()));
        } finally {
            Throwable cleanup = probe.cleanup();
            if (cleanup != null) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            boolean passed = complete && cleanup == null;
            probe.report.put("schema_version", 1).put("passed", passed).put("status", passed ? "PASS" : "FAIL")
                    .put("synthetic", true).put("checks", probe.cases.length()).put("cases", probe.cases)
                    .put("api_level", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0])
                    .put("fixture_id", FIXTURE_ID).put("fixture_sha256", hex(MessageDigest.getInstance("SHA-256").digest(FIXTURE.getBytes(StandardCharsets.UTF_8))))
                    .put("model_id", MODEL_ID).put("model_sha256", MODEL_SHA).put("score_tolerance", (double)TOLERANCE)
                    .put("reference_runtime_version", "1.24.4").put("apk_runtime_version_expected", "1.28.2")
                    .put("real_model_executed", probe.realModel).put("real_msime_executed", probe.realCore)
                    .put("actual_editable_verified", probe.editableVerified).put("production_commit_path_used", probe.editableVerified)
                    .put("default_on_verified", probe.defaultVerified).put("private_pool_empty", probe.privateVerified)
                    .put("score_calls", probe.scoreCalls).put("completed_scores", probe.completedScores)
                    .put("non_main_model_thread", Looper.myLooper() != Looper.getMainLooper())
                    .put("cleanup_completed", cleanup == null).put("open_native_handles_at_exit", probe.rankHandle == 0 ? 0 : 1)
                    .put("open_core_handles_at_exit", (probe.coreHandle == 0 ? 0 : 1) + (probe.privateHandle == 0 ? 0 : 1))
                    .put("system_ime_executed", false).put("phone_executed", false).put("active_inference_cancel_tested", false)
                    .put("heap_leak_tested", false).put("probe_opens_network", false).put("scope", SCOPE);
            Files.write(new File(output, "candidate-ranking-report.json").toPath(), probe.report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (failure != null) throw new Exception("candidate ranking probe failed at " + probe.stage, failure);
        return probe.report;
    }

    private CandidateRankingProbe() { throw new AssertionError(); }
}
