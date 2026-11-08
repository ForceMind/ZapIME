// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Editable;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputMethodManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Production system-IME path, from injected touchscreen key events to an
 * independent Editable. No product reflection, JNI calls, preference writes,
 * dictionary replacement, fake scores or direct editor writes are permitted.
 */
final class CandidateImeProbe {
    private static final String SCOPE = "production-system-ime-candidate-ranking";
    private static final String MODEL_ID = "msime-keystroke-8m-rerank-v2";
    private static final String[] FIXTURES = {"shijie", "tianqi"};
    private static final Pattern RANKING = Pattern.compile(
            "(?m)^\\s*ZapIME candidate ranking: enabled=(true|false) eligible=(true|false) state=([a-z_]+) scored=([0-9]+) inputRevision=([0-9]+) appliedRevision=(-1|[0-9]+)\\s*$");
    private static final Pattern SUBMITTED = Pattern.compile("(?m)^\\s*ZapIME candidate requests: submitted=([0-9]+)\\s*$");
    private static final Pattern LIFECYCLE = Pattern.compile("(?m)^\\s*(ZapIME lifecycle: [^\\r\\n]+)$");
    private static final Pattern START_COUNTS = Pattern.compile("starts=([0-9]+) restarts=([0-9]+)");
    private static final Pattern TRACE_SEQUENCE = Pattern.compile("([0-9]+)@[0-9]+:[A-Z_]+:-?[0-9]+");
    private static final Pattern COMPOSITION = Pattern.compile("(?m)^\\s*(ZapIME composition trace \\(seq@uptime-ms:fixed-code:epoch\\): [^\\r\\n]+)$");
    private final Instrumentation instrumentation;
    private final UiAutomation automation;
    private final String token;
    private final File output;
    private final JSONObject report = new JSONObject();
    private final JSONArray attempts = new JSONArray();
    private String stage = "launch";
    private int keyTaps,contextSeedCommits;
    private String committedPrefix="";

    private static final class Editor {
        String text, committed;
        boolean composing, served, focused;
        int animations, animationVersion;
    }

    private static final class Stats {
        boolean enabled, eligible;
        int scored, submitted;
        long inputRevision, appliedRevision;
        String state, line;
        int starts=-1,restarts=-1;
        long traceSequence;
        boolean hasTrace;
    }

    private static final class Choice {
        final String text;
        final Rect bounds;
        Choice(String text, Rect bounds) { this.text = text; this.bounds = bounds; }
    }

    private CandidateImeProbe(Instrumentation instrumentation, UiAutomation automation, String token) throws Exception {
        this.instrumentation = instrumentation;
        this.automation = automation;
        this.token = token;
        output = instrumentation.getTargetContext().getExternalFilesDir(null);
        require(output != null && output.isDirectory(), "report_directory_unavailable");
    }

    static void run(Instrumentation instrumentation, UiAutomation automation, String token) throws Exception {
        require(token != null && token.matches("[0-9]+-[0-9]+"), "candidate_ci_token_required");
        new CandidateImeProbe(instrumentation, automation, token).execute();
    }

    static void runConfigurationRestart(Instrumentation instrumentation, UiAutomation automation, String token) throws Exception {
        require(token != null && token.matches("[0-9]+-[0-9]+") && Build.VERSION.SDK_INT == 35,
                "configuration_probe_requires_api35_ci_token");
        new CandidateImeProbe(instrumentation, automation, token).executeConfigurationRestart();
    }

    private static void require(boolean condition, String reason) throws Exception {
        if (!condition) throw new Exception(reason);
    }

    private Editor editor() throws Exception {
        final Editor[] result = new Editor[1];
        instrumentation.runOnMainSync(() -> {
            ProbeActivity activity = ProbeActivity.current.get();
            if (activity == null) return;
            Editable value = activity.normal.getText();
            int start = BaseInputConnection.getComposingSpanStart(value);
            int end = BaseInputConnection.getComposingSpanEnd(value);
            Editor state = new Editor();
            state.text = value.toString();
            state.composing = start >= 0 && end >= start;
            state.committed = state.composing ? state.text.substring(0, start) + state.text.substring(end) : state.text;
            state.focused = activity.normal.hasWindowFocus() && activity.normal.hasFocus();
            state.served = ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).isActive(activity.normal);
            state.animations = activity.activeImeAnimations;
            state.animationVersion = activity.imeAnimationVersion;
            result[0] = state;
        });
        require(result[0] != null, "independent_editor_unavailable");
        return result[0];
    }

    private AccessibilityNodeInfo button(AccessibilityNodeInfo node, String label) {
        if (node == null || !node.refresh()) return null;
        if ("android.widget.Button".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                && node.isVisibleToUser() && node.isEnabled()
                && (label.contentEquals(node.getText() == null ? "" : node.getText())
                    || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); ++i) {
            AccessibilityNodeInfo found = button(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo imeButton(String label) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !"org.zapime.android".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            AccessibilityNodeInfo found = button(root, label);
            if (found != null) return found;
        }
        return null;
    }

    private boolean hasLabel(AccessibilityNodeInfo node, String label) {
        if (node == null || !node.refresh()) return false;
        if (node.isVisibleToUser() && (label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return true;
        for (int i = 0; i < node.getChildCount(); ++i) if (hasLabel(node.getChild(i), label)) return true;
        return false;
    }

    private void awaitReadyEditor() throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            final boolean[] created = new boolean[1];
            instrumentation.runOnMainSync(() -> {
                ProbeActivity host = ProbeActivity.current.get();
                created[0] = host != null && host.normal != null;
            });
            if (created[0]) {
                Editor state = editor();
                if (state.served && state.focused) for (AccessibilityWindowInfo window : automation.getWindows()) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && "org.zapime.android".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                            && hasLabel(root, "⚡ 闪电 · 离线")) return;
                }
            }
            SystemClock.sleep(50);
        }
        throw new Exception("production_editor_did_not_become_ready");
    }

    private Rect stableButton(String label) throws Exception {
        Rect previous = null;
        long stableAt = 0, end = SystemClock.uptimeMillis() + 15000;
        int animation = -1;
        while (SystemClock.uptimeMillis() < end) {
            Editor host = editor();
            AccessibilityNodeInfo node = imeButton(label);
            Rect current = new Rect();
            if (node != null) node.getBoundsInScreen(current);
            if (host.served && host.focused && host.animations == 0 && !current.isEmpty()) {
                if (!current.equals(previous) || animation != host.animationVersion) {
                    previous = current; animation = host.animationVersion; stableAt = SystemClock.uptimeMillis();
                } else if (SystemClock.uptimeMillis() - stableAt >= 100) {
                    // Fresh geometry immediately before this single injection;
                    // no stale coordinates or accessibility-click fallback.
                    AccessibilityNodeInfo fresh = imeButton(label);
                    Rect checked = new Rect();
                    if (fresh != null) fresh.getBoundsInScreen(checked);
                    if (checked.equals(current)) return checked;
                    stableAt = 0; previous = null;
                }
            } else { stableAt = 0; previous = null; }
            SystemClock.sleep(25);
        }
        throw new Exception("stable_product_key_unavailable");
    }

    private void tap(Rect bounds) throws Exception {
        long downAt = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(automation.injectInputEvent(down, true), "touch_down_rejected"); }
        finally { down.recycle(); }
        SystemClock.sleep(35);
        MotionEvent up = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(automation.injectInputEvent(up, true), "touch_up_rejected"); }
        finally { up.recycle(); }
    }

    private void key(String label, String expectedCode) throws Exception {
        ProbeActivity.keyStep(keyTaps+1,false);
        tap(stableButton(label));
        ++keyTaps;
        long end = SystemClock.uptimeMillis() + 4000;
        while (SystemClock.uptimeMillis() < end) {
            Editor state = editor();
            require(state.text.startsWith(committedPrefix), "committed_context_changed");
            String raw = state.text.substring(committedPrefix.length()).replace("'", "").replace(" ", "");
            if (raw.equals(expectedCode) && state.committed.equals(committedPrefix)
                    && (expectedCode.isEmpty() || state.composing)) {ProbeActivity.keyStep(keyTaps,true);return;}
            require(state.committed.equals(committedPrefix), "key_committed_unexpected_text");
            SystemClock.sleep(25);
        }
        throw new Exception("touch_key_not_observed_in_editable");
    }

    private Stats stats() throws Exception {
        String text;
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(
                    "dumpsys -t 3 activity service org.zapime.android/.ZapInputMethodService");
                ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                require(bytes.size() + count <= 262144, "service_dump_overflow");
                bytes.write(buffer, 0, count);
            }
            text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        Matcher found = RANKING.matcher(text);
        require(found.find(), "production_ranking_diagnostic_missing");
        Stats result = new Stats();
        result.enabled = Boolean.parseBoolean(found.group(1));
        result.eligible = Boolean.parseBoolean(found.group(2));
        result.state = found.group(3);
        result.scored = Integer.parseInt(found.group(4));
        result.inputRevision = Long.parseLong(found.group(5));
        result.appliedRevision = Long.parseLong(found.group(6));
        result.line = found.group().trim();
        require(!found.find(), "ambiguous_ranking_diagnostic");
        Matcher requests = SUBMITTED.matcher(text);
        require(requests.find(), "production_request_diagnostic_missing");
        result.submitted = Integer.parseInt(requests.group(1));
        result.line += "\n" + requests.group().trim();
        require(!requests.find(), "ambiguous_request_diagnostic");
        Matcher lifecycle = LIFECYCLE.matcher(text);
        if (lifecycle.find()) result.line += "\n" + lifecycle.group(1);
        Matcher composition = COMPOSITION.matcher(text);
        if (composition.find()) {
            result.hasTrace=true;
            String trace=composition.group(1);
            result.line += "\n" + trace;
            Matcher sequence=TRACE_SEQUENCE.matcher(trace);
            while(sequence.find())result.traceSequence=Long.parseLong(sequence.group(1));
        }
        Matcher counts=START_COUNTS.matcher(result.line);
        if(counts.find()){result.starts=Integer.parseInt(counts.group(1));result.restarts=Integer.parseInt(counts.group(2));}
        return result;
    }

    private static boolean han(String value) {
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 64) return false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            if (!(cp >= 0x3400 && cp <= 0x4dbf || cp >= 0x4e00 && cp <= 0x9fff
                    || cp >= 0xf900 && cp <= 0xfaff || cp >= 0x20000 && cp <= 0x323af)) return false;
            offset += Character.charCount(cp);
        }
        return true;
    }

    private void candidates(AccessibilityNodeInfo node, Rect rail, List<Choice> choices) {
        if (node == null || !node.refresh()) return;
        CharSequence className = node.getClassName();
        Rect currentRail = rail;
        if ("android.widget.HorizontalScrollView".contentEquals(className == null ? "" : className)) {
            currentRail = new Rect(); node.getBoundsInScreen(currentRail);
        }
        if (currentRail != null && "android.widget.Button".contentEquals(className == null ? "" : className)
                && node.isVisibleToUser() && node.isEnabled() && node.getText() != null) {
            String value = node.getText().toString();
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (han(value) && bounds.intersect(currentRail) && bounds.width() >= 20 && bounds.height() >= 20) choices.add(new Choice(value, bounds));
        }
        for (int i = 0; i < node.getChildCount(); ++i) candidates(node.getChild(i), currentRail, choices);
    }

    private List<Choice> choices() {
        ArrayList<Choice> choices = new ArrayList<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && "org.zapime.android".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) candidates(root, null, choices);
        }
        return choices;
    }

    private Choice settledChoice() throws Exception {
        String previous = "";
        long stableAt = 0, end = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < end) {
            List<Choice> visible = choices();
            StringBuilder signature = new StringBuilder();
            Choice selected = null;
            for (Choice choice : visible) {
                signature.append(choice.text).append(':').append(choice.bounds.toShortString()).append(';');
                // Both frozen codes have two syllables. Choose a currently
                // visible two-Han candidate, without predicting its wording or
                // whether the model promotes a particular word.
                if (selected == null && choice.text.codePointCount(0, choice.text.length()) == 2) selected = choice;
            }
            Editor host = editor();
            if (visible.size() >= 2 && selected != null && host.served && host.focused && host.animations == 0) {
                if (!signature.toString().equals(previous)) { previous = signature.toString(); stableAt = SystemClock.uptimeMillis(); }
                else if (SystemClock.uptimeMillis() - stableAt >= 200) {
                    report.put("visible_han_candidates", visible.size());
                    return selected;
                }
            } else { previous = ""; stableAt = 0; }
            SystemClock.sleep(25);
        }
        throw new Exception("stable_chinese_candidate_unavailable");
    }

    private void write(String name, String value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void screenshot(String name) throws Exception {
        Bitmap image = automation.takeScreenshot();
        require(image != null, "screenshot_unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
            require(image.compress(Bitmap.CompressFormat.PNG, 100, stream), "screenshot_write_failed");
        } finally { image.recycle(); }
    }

    private void acceptContextSeed() throws Exception {
        // Only an actual visible-candidate tap can add this context. The
        // existing prefix stays in the host; no direct editor write occurs.
        Choice contextChoice = settledChoice();
        String expected = committedPrefix + contextChoice.text;
        tap(stableButton(contextChoice.text));
        long end = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < end) {
            Editor state = editor();
            if (!state.composing && state.text.equals(expected)) break;
            SystemClock.sleep(25);
        }
        require(!editor().composing && editor().text.equals(expected), "context_seed_not_committed");
        committedPrefix = expected;
        ++contextSeedCommits;
        report.put("context_seed_touch", true).put("context_seed_text", committedPrefix)
                .put("context_seed_commits", contextSeedCommits);
    }

    private int hardwareSetting() {
        return android.provider.Settings.Secure.getInt(instrumentation.getContext().getContentResolver(),
                "show_ime_with_hard_keyboard", -1);
    }

    private void putHardwareSetting(int value) throws Exception {
        require(value==0||value==1,"noncanonical_hardware_setting");
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            require(android.provider.Settings.Secure.putInt(instrumentation.getContext().getContentResolver(),
                    "show_ime_with_hard_keyboard",value),"hardware_setting_write_rejected");
            require(hardwareSetting()==value,"hardware_setting_readback_mismatch");
        } finally { automation.dropShellPermissionIdentity(); }
    }

    private boolean traceHasFinishAfter(Stats state,long before) {
        Matcher entries=Pattern.compile("([0-9]+)@[0-9]+:INPUT_FINISHED:-?[0-9]+").matcher(state.line);
        while(entries.find())if(Long.parseLong(entries.group(1))>before)return true;
        return false;
    }

    private boolean continuity(ConfigurationContinuityObserver observer,Stats baseline,int connections,int command) throws Exception {
        Editor state=editor();Stats current=stats();
        boolean finished=traceHasFinishAfter(current,baseline.traceSequence);
        boolean complete=baseline.hasTrace&&current.hasTrace&&current.traceSequence>=baseline.traceSequence
                &&current.traceSequence-baseline.traceSequence<64;
        report.put("visibility_hidden_observations",observer.hiddenObservations)
                .put("window_focus_losses",observer.windowFocusLosses).put("editor_focus_losses",observer.editorFocusLosses)
                .put("insets_observations",observer.observations).put("service_trace_complete",complete)
                .put("input_finished_after_before",finished).put("after_connection_count",ProbeActivity.normalConnections.get())
                .put("after_command_connection",ProbeActivity.normalCommandConnection.get());
        final boolean[] visible=new boolean[1];instrumentation.runOnMainSync(()->visible[0]=observer.visible());
        boolean same=complete&&!finished&&state.focused&&state.served&&visible[0]
                &&observer.hiddenObservations==0&&observer.windowFocusLosses==0&&observer.editorFocusLosses==0
                &&connections==ProbeActivity.normalConnections.get()&&command==ProbeActivity.normalCommandConnection.get();
        report.put("same_editor_continuity",same);
        return same;
    }

    private void executeConfigurationRestart() throws Exception {
        report.put("schema_version",1).put("scope","production-system-ime-configuration-restart")
                .put("run_attempt",token).put("api_level",Build.VERSION.SDK_INT).put("synthetic",true)
                .put("passed",false).put("classification","not_executed")
                .put("product_preferences_written",false).put("human_touch_or_phone_latency",false)
                .put("model_execution_claimed",false).put("setting_restored",false);
        int original=-1;Throwable failure=null;ConfigurationContinuityObserver observer=null;
        Stats baseline=null;int connections=0,command=0;
        try {
            stage="configuration_launch";
            original=hardwareSetting();require(original==0||original==1,"hardware_setting_baseline_not_canonical");
            report.put("before_setting",original);
            Intent intent=new Intent().setClassName("org.zapime.android.test","org.zapime.android.test.ProbeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            instrumentation.getContext().startActivity(intent);awaitReadyEditor();stableButton("q");
            require(editor().text.isEmpty(),"fresh_editor_not_empty");
            Stats initial=stats();require(initial.enabled&&initial.eligible,"default_ranking_not_enabled");
            stage="configuration_seed_touch";
            String seed="nihao";for(int i=0;i<seed.length();++i)key(seed.substring(i,i+1),seed.substring(0,i+1));
            acceptContextSeed();
            stage="configuration_sh_touch";key("s","s");key("h","sh");
            Editor before=editor();require(before.composing&&before.text.equals(committedPrefix+"sh"),"sh_fixture_not_composing");
            baseline=stats();require(baseline.hasTrace&&baseline.restarts>=0,"configuration_diagnostics_missing");
            connections=ProbeActivity.normalConnections.get();command=ProbeActivity.normalCommandConnection.get();
            require(connections>0&&command>0,"configuration_connection_identity_missing");
            report.put("before_connection_count",connections).put("before_command_connection",command)
                    .put("before_restarts",baseline.restarts).put("before_text",before.text).put("before_composing",before.composing);
            write("configuration-compose-service-before.txt",baseline.line+"\n");
            final ConfigurationContinuityObserver[] installed=new ConfigurationContinuityObserver[1];
            instrumentation.runOnMainSync(()->installed[0]=new ConfigurationContinuityObserver(ProbeActivity.current.get()));
            observer=installed[0];
            final boolean[] initiallyVisible=new boolean[1];
            instrumentation.runOnMainSync(()->initiallyVisible[0]=installed[0].visible());
            require(initiallyVisible[0],"configuration_initial_ime_not_visible");
            stage="configuration_framework_setting_transition";
            // These are two explicit platform settings writes, not a Service,
            // core, hidden lifecycle or editor mutation. The test-only process
            // temporarily borrows shell WRITE_SECURE_SETTINGS and drops it.
            putHardwareSetting(1-original);report.put("inverse_readback",hardwareSetting());
            putHardwareSetting(original);report.put("trigger_restored_readback",hardwareSetting());
            Stats after;long deadline=SystemClock.uptimeMillis()+5000;
            do {after=stats();if(after.restarts>baseline.restarts)break;SystemClock.sleep(25);}while(SystemClock.uptimeMillis()<deadline);
            report.put("after_restarts",after.restarts).put("framework_restart_observed",after.restarts>baseline.restarts);
            require(after.restarts>baseline.restarts,"framework_restart_not_observed");
            write("configuration-compose-service-after.txt",after.line+"\n");
            if(!continuity(observer,baseline,connections,command)){
                report.put("classification","interrupted_not_same_editor");
                throw new Exception("configuration_interrupted_not_same_editor");
            }
            require(observer.observations>0,"configuration_insets_callbacks_not_observed");
            report.put("classification","same_editor_composition_failure");
            stage="configuration_i_touch";key("i","shi");
            Editor typed=editor();report.put("after_i_text",typed.text).put("after_i_composing",typed.composing);
            require(continuity(observer,baseline,connections,command),"configuration_continuity_lost_after_key");
            stage="configuration_visible_candidate_touch";
            List<Choice> visible=choices();require(visible.size()>=2,"configuration_candidates_missing");
            Choice selected=visible.get(0);String expected=committedPrefix+selected.text;
            tap(stableButton(selected.text));
            long until=SystemClock.uptimeMillis()+5000;Editor committed;
            do{committed=editor();if(!committed.composing&&committed.text.equals(expected))break;SystemClock.sleep(25);}while(SystemClock.uptimeMillis()<until);
            require(!committed.composing&&committed.text.equals(expected),"configuration_candidate_not_exact_visible_text");
            require(continuity(observer,baseline,connections,command),"configuration_continuity_lost_at_commit");
            report.put("selected_text",selected.text).put("actual_editable",committed.text).put("editor_composing",committed.composing)
                    .put("candidate_touch",true).put("classification","same_editor_composition_preserved").put("passed",true);
            stage="configuration_completed";
        } catch(Throwable error) {
            failure=error;report.put("passed",false).put("failure_type",error.getClass().getSimpleName())
                    .put("failure_reason",error.getMessage()==null?"unknown":error.getMessage());
            if(observer!=null&&baseline!=null){
                try{if(!continuity(observer,baseline,connections,command))report.put("classification","interrupted_not_same_editor");}catch(Exception ignored){}
            }
            try{write("configuration-compose-service-failure.txt",stats().line+"\n");}catch(Exception ignored){}
        } finally {
            try{if(observer!=null){final ConfigurationContinuityObserver installed=observer;instrumentation.runOnMainSync(installed::close);}}
            catch(Throwable cleanup){report.put("passed",false).put("observer_cleanup_error",cleanup.getClass().getSimpleName());if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            try{
                if(original>=0){putHardwareSetting(original);report.put("setting_restored",hardwareSetting()==original);}
            }catch(Throwable cleanup){report.put("passed",false).put("setting_restore_error",cleanup.getClass().getSimpleName());if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            report.put("stage",stage).put("touch_keypresses",keyTaps);
            try{write("configuration-compose-host-lifecycle.txt",ProbeActivity.lifecycle());}
            catch(Throwable cleanup){report.put("passed",false).put("lifecycle_write_error",cleanup.getClass().getSimpleName());if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            write("configuration-compose-report.json",report.toString(2)+"\n");
        }
        if(failure!=null)throw new Exception("configuration_compose_gate_failed_at_"+stage,failure);
    }

    private void execute() throws Exception {
        report.put("schema_version", 2).put("scope", SCOPE).put("run_attempt", token)
                .put("api_level", Build.VERSION.SDK_INT).put("model_id", MODEL_ID)
                .put("synthetic", true).put("system_ime_executed", false)
                .put("production_lexicon", false).put("production_model", false)
                .put("product_preferences_written", false).put("attempts", attempts)
                .put("passed", false).put("human_touch_or_phone_latency", false);
        try {
            Intent intent = new Intent().setClassName("org.zapime.android.test", "org.zapime.android.test.ProbeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            instrumentation.getContext().startActivity(intent);
            stage = "wait_product_keyboard";
            awaitReadyEditor();
            stableButton("q");
            require(editor().text.isEmpty(), "fresh_editor_not_empty");
            Stats initial = stats();
            require(initial.enabled && initial.eligible, "default_ranking_not_enabled");
            report.put("system_ime_executed", true);
            write("candidate-ime-service-before.txt", initial.line + "\n");
            report.put("ranking_enabled", initial.enabled).put("ranking_eligible", initial.eligible)
                    .put("scored_before", initial.scored);

            // First exercise an empty editor through real keys. The product
            // must preserve base order without even submitting an LM job.
            stage = "empty_context_base_order";
            String seed = "nihao";
            for (int i = 0; i < seed.length(); ++i) key(seed.substring(i, i + 1), seed.substring(0, i + 1));
            long baseUntil = SystemClock.uptimeMillis() + 600;
            do {
                Stats base = stats();
                require(base.submitted == initial.submitted && base.scored == initial.scored,
                        "empty_context_submitted_inference");
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < baseUntil);
            report.put("empty_context_base_order", true).put("submitted_before", initial.submitted)
                    .put("submitted_after_empty", stats().submitted);
            acceptContextSeed();

            Stats successful = null;
            for (int attempt = 0; attempt < FIXTURES.length; ++attempt) {
                String code = FIXTURES[attempt];
                Stats before = stats();
                JSONObject evidence = new JSONObject().put("code", code).put("scored_before", before.scored)
                        .put("attempt", attempt + 1).put("scored_increased", false).put("current_ranking_applied", false);
                attempts.put(evidence);
                stage = "touch_pinyin_" + (attempt + 1);
                for (int i = 0; i < code.length(); ++i) key(code.substring(i, i + 1), code.substring(0, i + 1));
                stage = "wait_real_ranking_" + (attempt + 1);
                long deadline = SystemClock.uptimeMillis() + 3500;
                Stats observed;
                do {
                    observed = stats();
                    require(observed.enabled && observed.eligible, "ranking_became_ineligible");
                    if (observed.scored > before.scored && observed.state.equals("ready")
                            && observed.appliedRevision == observed.inputRevision) break;
                    SystemClock.sleep(100);
                } while (SystemClock.uptimeMillis() < deadline);
                evidence.put("scored_after", observed.scored).put("state", observed.state)
                        .put("input_revision", observed.inputRevision).put("applied_revision", observed.appliedRevision)
                        .put("scored_increased", observed.scored > before.scored)
                        .put("current_ranking_applied", observed.scored > before.scored && observed.state.equals("ready")
                                && observed.appliedRevision == observed.inputRevision);
                if (evidence.getBoolean("current_ranking_applied")) { successful = observed; break; }
                // The first cold load can exceed the production deadline. Keep
                // its failure in the report, clear using real keys, and allow
                // only the second frozen code to exercise the warmed model.
                if (attempt + 1 < FIXTURES.length) {
                    stage = "clear_cold_attempt";
                    for (int i = code.length(); i > 0; --i) key("⌫", code.substring(0, i - 1));
                    // Backspace intentionally clears trusted context. Rebuild
                    // it by another actual accepted candidate, never by copying
                    // the editor text back into the product's context object.
                    for (int i = 0; i < seed.length(); ++i) key(seed.substring(i, i + 1), seed.substring(0, i + 1));
                    acceptContextSeed();
                }
            }
            require(successful != null, "production_scored_counter_did_not_increase");
            report.put("production_model", true).put("production_lexicon", true);
            stage = "touch_visible_candidate";
            Choice choice = settledChoice();
            screenshot("candidate-ime-candidates.png");
            Stats beforeTouch = stats();
            require(beforeTouch.inputRevision == successful.inputRevision
                    && beforeTouch.appliedRevision == beforeTouch.inputRevision && beforeTouch.state.equals("ready"),
                    "current_ranking_changed_before_candidate_touch");
            write("candidate-ime-service-scored.txt", beforeTouch.line + "\n");
            // Screenshot acquisition may yield; reacquire this exact visible
            // label and fresh geometry before injecting a single real tap.
            tap(stableButton(choice.text));
            long commitDeadline = SystemClock.uptimeMillis() + 5000;
            Editor committed;
            do {
                committed = editor();
                if (!committed.composing && committed.text.equals(committedPrefix+choice.text)) break;
                require(committed.committed.equals(committedPrefix) || committed.committed.equals(committedPrefix+choice.text), "candidate_committed_different_text");
                SystemClock.sleep(25);
            } while (SystemClock.uptimeMillis() < commitDeadline);
            require(!committed.composing && committed.text.equals(committedPrefix+choice.text), "candidate_touch_did_not_commit_exact_visible_text");
            Stats after = stats();
            require(after.enabled && after.eligible && after.scored > initial.scored, "production_ranking_evidence_lost");
            write("candidate-ime-service-after.txt", after.line + "\n");
            report.put("scored_after", after.scored).put("submitted_after", after.submitted).put("selected_text", choice.text)
                    .put("current_ranking_applied", true).put("scoring_input_revision", successful.inputRevision)
                    .put("scoring_applied_revision", successful.appliedRevision)
                    .put("actual_editable", committed.text).put("editor_composing", committed.composing)
                    .put("candidate_touch", true).put("touch_keypresses", keyTaps)
                    .put("observed_connection_serial", ProbeActivity.normalCommandConnection.get()).put("passed", true);
            screenshot("candidate-ime-committed.png");
            stage = "completed";
        } catch (Throwable error) {
            report.put("passed", false).put("failure_type", error.getClass().getSimpleName())
                    .put("failure_reason", error.getMessage() == null ? "unknown" : error.getMessage());
            try {
                Stats failure = stats();
                write("candidate-ime-service-failure.txt", failure.line + "\n");
            } catch (Exception ignored) { }
            try {
                Editor failure = editor();
                report.put("failure_editor_text_units", failure.text.length())
                        .put("failure_editor_committed_units", failure.committed.length())
                        .put("failure_editor_composing", failure.composing)
                        .put("failure_editor_focused", failure.focused)
                        .put("failure_editor_served", failure.served);
            } catch (Exception ignored) { }
            try { screenshot("candidate-ime-failure.png"); } catch (Exception ignored) { }
            throw new Exception("candidate_system_ime_gate_failed_at_" + stage, error);
        } finally {
            report.put("stage", stage).put("touch_keypresses", keyTaps);
            write("candidate-ime-host-lifecycle.txt", ProbeActivity.lifecycle());
            write("candidate-ime-report.json", report.toString(2) + "\n");
        }
    }
}
