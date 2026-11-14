# SPDX-License-Identifier: GPL-3.0-only
"""Prevent losing adb-root failure evidence when a CI gate exits early."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RootArtifactEvidenceTest(unittest.TestCase):
    def test_explicit_candidate_and_voicekey_upload_paths_include_root_trace(self):
        candidates = (ROOT / ".github/workflows/candidate-ranking.yml").read_text()
        voicekey = (ROOT / ".github/workflows/voicekey-android-model.yml").read_text()
        for directory in ("candidate-ranking-android", "candidate-ime-android"):
            with self.subTest(directory=directory):
                parent = f"target/{directory}/offline-uid.json"
                trace = f"target/{directory}/offline-uid-root.txt"
                self.assertEqual(candidates.count(parent), 1)
                self.assertEqual(candidates.count(trace), 1)
                self.assertGreater(candidates.index(trace), candidates.index(parent))
        self.assertEqual(voicekey.count("target/voicekey-android/offline-uid-root.txt"), 1)
        self.assertEqual(voicekey.count("target/voicekey-android/offline-uid.json"), 1)

    def test_existing_product_acceptance_uses_whole_evidence_directories(self):
        native = (ROOT / ".github/workflows/android.yml").read_text()
        for directory in ("target/device-smoke/", "target/voice-android/", "target/voicekey-android/"):
            with self.subTest(directory=directory):
                self.assertIn(directory, native)


if __name__ == "__main__":
    unittest.main()
