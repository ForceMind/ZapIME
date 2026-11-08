# SPDX-License-Identifier: GPL-3.0-only
"""Run the live test-host continuity decision with host-only Java stand-ins.

The methods are extracted from CandidateImeProbe at test time, rather than
reimplementing their decision. This is not Android, Binder, or system-IME proof.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "platforms/android/tests/java/org/zapime/android/test/CandidateImeProbe.java"


class ConfigurationProbeStateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get("JAVA_HOME")
        java_bin = Path(java_home) / "bin" if java_home else None
        cls.javac = str(java_bin / "javac") if java_bin else shutil.which("javac")
        cls.java = str(java_bin / "java") if java_bin else shutil.which("java")
        if not cls.javac or not cls.java:
            raise RuntimeError("JDK 17 javac/java required for live probe state regression")
        cls.directory = tempfile.TemporaryDirectory(prefix="zapime-config-state-")
        cls.addClassCleanup(cls.directory.cleanup)
        cls.build = Path(cls.directory.name)
        source = PROBE.read_text(encoding="utf-8")
        begin = source.index("    private boolean traceEventAfter(")
        end = source.index("    private void executeConfigurationRestart()", begin)
        methods = source[begin:end]
        harness = r'''
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigurationProbeStateHarness {
    static final class Editor { boolean focused = true, served = true; }
    static final class Stats {
        boolean hasTrace = true;
        long traceSequence;
        int restarts;
        String line;
    }
    static final class ConfigurationContinuityObserver {
        int hiddenObservations, windowFocusLosses, editorFocusLosses;
        int observations = 1, visibleObservations = 1, visibility = 1;
        boolean visible() { return visibility == 1; }
        int visibilityState() { return visibility; }
    }
    static final class ProbeActivity {
        static final AtomicInteger normalConnections = new AtomicInteger(1);
        static final AtomicInteger normalCommandConnection = new AtomicInteger(1);
    }
    static final class Report extends LinkedHashMap<String,Object> {
        @Override public Report put(String key, Object value) {
            super.put(key, value);
            return this;
        }
        boolean optBoolean(String key) { return Boolean.TRUE.equals(get(key)); }
    }
    static final class Instrumentation {
        void runOnMainSync(Runnable action) { action.run(); }
    }
    final Report report = new Report();
    final Instrumentation instrumentation = new Instrumentation();
    final Editor editorState = new Editor();
    final Stats current = new Stats();
    Editor editor() { return editorState; }
    Stats stats() { return current; }
    static void require(boolean condition, String reason) throws Exception {
        if (!condition) throw new Exception(reason);
    }
''' + methods + r'''
    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        ConfigurationProbeStateHarness host = new ConfigurationProbeStateHarness();
        ConfigurationContinuityObserver observer = new ConfigurationContinuityObserver();
        Stats baseline = new Stats();
        baseline.traceSequence = 10;
        baseline.restarts = 0;
        host.current.traceSequence = 12;
        host.current.restarts = 1;
        String events = "11@100:INPUT_RESTART:1";
        boolean validFresh = scenario.equals("valid") || scenario.equals("normal_commit")
                || scenario.equals("rejected") || scenario.equals("empty_finish")
                || scenario.equals("null_insets");
        if (validFresh) events += " 12@101:RESTART_FRESH_VALIDATED:1";
        if (scenario.equals("finish")) events += " 12@101:INPUT_FINISHED:1";
        if (scenario.equals("hidden")) observer.hiddenObservations = 1;
        if (scenario.equals("invisible")) observer.visibility = 0;
        if (scenario.equals("window_focus")) observer.windowFocusLosses = 1;
        if (scenario.equals("editor_focus")) observer.editorFocusLosses = 1;
        if (scenario.equals("unfocused")) host.editorState.focused = false;
        if (scenario.equals("not_served")) host.editorState.served = false;
        if (scenario.equals("connection")) ProbeActivity.normalConnections.set(2);
        if (scenario.equals("command_connection")) ProbeActivity.normalCommandConnection.set(2);
        if (scenario.equals("missing_trace")) host.current.hasTrace = false;
        if (scenario.equals("overflow")) host.current.traceSequence = 74;
        if (scenario.equals("reversed_trace")) host.current.traceSequence = 9;
        if (scenario.equals("null_insets")) observer.visibility = -1;
        if (scenario.equals("rejected")) {
            events += " 13@102:RESTART_FRESH_REJECTED:1";
            host.current.traceSequence = 13;
        }
        if (scenario.equals("empty_finish")) {
            events += " 13@102:APPLY_FINISH_EMPTY:1";
            host.current.traceSequence = 13;
        }
        if (scenario.equals("normal_commit")) {
            events += " 13@102:APPLY_FINISH_AFTER_COMMIT:1";
            host.current.traceSequence = 13;
        }
        host.current.line = events;
        String direct;
        try { direct = Boolean.toString(host.continuity(observer, baseline, 1, 1)); }
        catch (Exception failure) { direct = "threw"; }

        // Keep the actual trigger/fresh requirement and interruption catch
        // structure. A failed fresh proof must not erase observed interruption.
        host.report.put("classification", "not_executed");
        boolean allowed = false;
        try {
            require(host.current.restarts > baseline.restarts, "framework_restart_not_observed");
            boolean fresh = host.traceEventAfter(host.current, baseline.traceSequence, "RESTART_FRESH_VALIDATED");
            boolean rejected = host.traceEventAfter(host.current, baseline.traceSequence, "RESTART_FRESH_REJECTED");
            boolean emptyFinish = host.traceEventAfter(host.current, baseline.traceSequence, "APPLY_FINISH_EMPTY");
            require(fresh && !rejected && !emptyFinish, "configuration_fresh_verification_not_completed");
            if (!host.continuity(observer, baseline, 1, 1)) {
                host.report.put("classification", "interrupted_not_same_editor");
                throw new Exception("configuration_interrupted_not_same_editor");
            }
            host.report.put("classification", "same_editor_composition_failure");
            allowed = true;
        } catch (Exception failure) {
            try {
                if (!host.continuity(observer, baseline, 1, 1))
                    host.report.put("classification", "interrupted_not_same_editor");
            } catch (Exception ignored) { }
        }
        System.out.println("direct=" + direct + ";allowed=" + allowed
                + ";classification=" + host.report.get("classification"));
    }
}
'''
        path = cls.build / "ConfigurationProbeStateHarness.java"
        path.write_text(harness, encoding="utf-8")
        subprocess.run([cls.javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
                        "-d", str(cls.build), str(path)], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def decision(self, scenario):
        result = subprocess.run([self.java, "-cp", str(self.build),
                                 "ConfigurationProbeStateHarness", scenario],
                                check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True)
        return dict(part.split("=", 1) for part in result.stdout.strip().split(";"))

    def test_observed_interruption_does_not_need_a_successful_fresh_proof(self):
        for scenario in ("finish", "hidden", "invisible", "window_focus", "editor_focus",
                         "unfocused", "not_served", "connection", "command_connection"):
            with self.subTest(scenario=scenario):
                result = self.decision(scenario)
                self.assertEqual(result, {"direct": "false", "allowed": "false",
                                          "classification": "interrupted_not_same_editor"})

    def test_unknown_trace_is_not_reported_as_observed_interruption(self):
        for scenario in ("missing_trace", "overflow", "reversed_trace"):
            with self.subTest(scenario=scenario):
                self.assertEqual(self.decision(scenario),
                                 {"direct": "threw", "allowed": "false",
                                  "classification": "not_executed"})

    def test_null_insets_is_unknown_instead_of_a_measured_hidden_window(self):
        self.assertEqual(self.decision("null_insets"),
                         {"direct": "threw", "allowed": "false",
                          "classification": "not_executed"})

    def test_complete_fresh_proof_and_normal_candidate_finish_are_allowed(self):
        for scenario in ("valid", "normal_commit"):
            with self.subTest(scenario=scenario):
                self.assertEqual(self.decision(scenario),
                                 {"direct": "true", "allowed": "true",
                                  "classification": "same_editor_composition_failure"})

    def test_missing_rejected_or_empty_fresh_proof_remains_a_failure(self):
        for scenario in ("no_fresh", "rejected", "empty_finish"):
            with self.subTest(scenario=scenario):
                result = self.decision(scenario)
                self.assertEqual(result["direct"], "threw")
                self.assertEqual(result["allowed"], "false")
                self.assertNotEqual(result["classification"], "interrupted_not_same_editor")


if __name__ == "__main__":
    unittest.main()
