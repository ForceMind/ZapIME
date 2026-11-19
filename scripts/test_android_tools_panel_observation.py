# SPDX-License-Identifier: GPL-3.0-only
"""Run the Android test observer's actual Java policy against synthetic traces.

This checks the acceptance gate, not an Android UI or a product input session.
The existing real IME/Editable/privacy/teardown workflow remains required.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
OBSERVER = (ROOT / "platforms/android/tests/java/org/zapime/android/test"
            / "ToolsPanelObservation.java")


class ToolsPanelObservationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get("JAVA_HOME")
        binary = Path(java_home) / "bin" if java_home else None
        cls.javac = str(binary / "javac") if binary else shutil.which("javac")
        cls.java = str(binary / "java") if binary else shutil.which("java")
        if not cls.javac or not cls.java:
            raise RuntimeError("JDK javac/java required for tools panel observation regression")
        cls.temporary = tempfile.TemporaryDirectory(prefix="zapime-tools-observation-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.build = Path(cls.temporary.name)
        harness = cls.build / "ToolsPanelObservationHarness.java"
        harness.write_text(r'''
package org.zapime.android.test;
public final class ToolsPanelObservationHarness {
    public static void main(String[] args) {
        ToolsPanelObservation proof = new ToolsPanelObservation(0);
        for (String event : args) {
            String[] fields = event.split(",");
            long now = Long.parseLong(fields[0]);
            ToolsPanelObservation.Outcome outcome = proof.observe(now,
                    fields[1].equals("1"), fields[2].equals("1"),
                    fields[3].equals("1"), fields[4].equals("1"));
            System.out.println(outcome + ":" + proof.samples() + ":" + proof.stableMillis(now));
        }
    }
}
''', encoding="utf-8")
        subprocess.run([cls.javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
                        "-d", str(cls.build), str(OBSERVER), str(harness)],
                       check=True, capture_output=True, text=True, timeout=25)

    @staticmethod
    def event(now, *, original=True, letters=False, complete=True, same=True):
        return ",".join(map(str, (now, int(original), int(letters), int(complete), int(same))))

    def trace(self, events):
        result = subprocess.run(
            [self.java, "-cp", str(self.build),
             "org.zapime.android.test.ToolsPanelObservationHarness", *events],
            check=True, capture_output=True, text=True, timeout=25)
        return [(state, int(samples), int(duration))
                for state, samples, duration in (line.split(":") for line in result.stdout.splitlines())]

    def test_success_needs_both_ten_samples_and_half_second(self):
        rows = self.trace([self.event(now) for now in range(0, 501, 50)])
        self.assertEqual(rows[-2], ("WAITING", 10, 450))
        self.assertEqual(rows[-1], ("STABLE", 11, 500))
        sparse = self.trace([self.event(0), self.event(1000)])
        self.assertEqual(sparse[-1], ("WAITING", 2, 1000))
        burst = self.trace([self.event(0) for _ in range(20)])
        self.assertEqual(burst[-1], ("WAITING", 20, 0))

    def test_detached_tree_discards_old_proof_then_requires_full_new_window(self):
        events = [self.event(now) for now in range(0, 301, 50)]
        events += [self.event(350, complete=False)]
        events += [self.event(now) for now in range(400, 901, 50)]
        rows = self.trace(events)
        self.assertEqual(rows[7], ("WAITING", 0, 0))
        self.assertEqual(rows[-2], ("WAITING", 10, 450))
        self.assertEqual(rows[-1], ("STABLE", 11, 500))

    def test_new_node_geometry_or_animation_identity_restarts_stability(self):
        events = [self.event(now) for now in range(0, 301, 50)]
        events += [self.event(350, same=False)]
        events += [self.event(now) for now in range(400, 851, 50)]
        rows = self.trace(events)
        self.assertEqual(rows[7], ("WAITING", 1, 0))
        self.assertEqual(rows[-2], ("WAITING", 10, 450))
        self.assertEqual(rows[-1], ("STABLE", 11, 500))

    def test_inconsistent_sample_at_threshold_cannot_complete_or_keep_old_proof(self):
        events = [self.event(now) for now in range(0, 451, 50)]
        # The host/animation state changed between the two UI-thread reads.
        # Even apparently unchanged target identities are not a complete sample.
        events += [self.event(500, complete=False, same=True)]
        events += [self.event(now) for now in range(550, 1051, 50)]
        rows = self.trace(events)
        self.assertEqual(rows[10], ("WAITING", 0, 0))
        self.assertEqual(rows[-2], ("WAITING", 10, 450))
        self.assertEqual(rows[-1], ("STABLE", 11, 500))

    def test_persistent_missing_or_unready_observation_times_out(self):
        for same in (False, True):
            with self.subTest(same=same):
                rows = self.trace([self.event(now, complete=False, same=same)
                                   for now in (0, 500, 4000, 7999, 8000)])
                self.assertTrue(all(row[0] == "WAITING" and row[1] == 0 for row in rows[:-1]))
                self.assertEqual(rows[-1][0], "TIMED_OUT")

    def test_rebuilding_cannot_extend_total_deadline_or_pass_at_deadline(self):
        events = [self.event(now, same=False) for now in (0, 2000, 4000, 6000)]
        events += [self.event(7500, same=False)]
        events += [self.event(now) for now in range(7550, 8001, 50)]
        rows = self.trace(events)
        self.assertNotIn("STABLE", [row[0] for row in rows])
        self.assertEqual(rows[-1][0], "TIMED_OUT")

    def test_real_letters_reject_immediately_even_when_other_proof_is_missing(self):
        for complete in (False, True):
            with self.subTest(complete=complete):
                rows = self.trace([self.event(0), self.event(100, letters=True, complete=complete),
                                   *[self.event(now) for now in range(200, 901, 50)]])
                self.assertEqual(rows[1][0], "WRONG_PANEL")
                self.assertTrue(all(row[0] == "WRONG_PANEL" for row in rows[1:]))

    def test_another_activity_cannot_complete_the_original_editor_proof(self):
        rows = self.trace([self.event(0), self.event(100, original=False),
                           *[self.event(now) for now in range(200, 901, 50)]])
        self.assertEqual(rows[1][0], "HOST_CHANGED")
        self.assertTrue(all(row[0] == "HOST_CHANGED" for row in rows[1:]))

    def test_backwards_clock_is_not_additional_stable_time(self):
        rows = self.trace([self.event(100), self.event(90), self.event(800)])
        self.assertEqual(rows[1][0], "CLOCK_INVALID")
        self.assertEqual(rows[2][0], "CLOCK_INVALID")


if __name__ == "__main__":
    unittest.main()
