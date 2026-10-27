# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import itertools
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import wave

import voice_probe
import voice_quality
from voice_quality_metrics import MAX_CODEPOINTS, aggregate, counts, normalize


def independent_distance(reference, hypothesis):
    # Full-matrix scalar oracle, independent of the rolling counted aligner.
    matrix = [[0]*(len(hypothesis)+1) for _ in range(len(reference)+1)]
    for i in range(len(reference)+1):
        matrix[i][0] = i
    for j in range(len(hypothesis)+1):
        matrix[0][j] = j
    for i, a in enumerate(reference, 1):
        for j, b in enumerate(hypothesis, 1):
            matrix[i][j] = min(matrix[i-1][j]+1, matrix[i][j-1]+1, matrix[i-1][j-1]+(a != b))
    return matrix[-1][-1]


class MetricTests(unittest.TestCase):
    def test_substitution_deletion_and_insertion(self):
        self.assertEqual(counts("甲乙丙", "甲丁丙")['substitutions'], 1)
        self.assertEqual(counts("甲乙丙", "甲丙")['deletions'], 1)
        self.assertEqual(counts("甲丙", "甲乙丙")['insertions'], 1)

    def test_exhaustive_short_alignment_matches_independent_distance(self):
        strings = [''.join(c) for n in range(5) for c in itertools.product('甲a', repeat=n)]
        for reference, hypothesis in itertools.product(strings, repeat=2):
            row = counts(reference, hypothesis, "verbatim")
            self.assertEqual(row['errors'], independent_distance(reference, hypothesis))
            self.assertEqual(row['errors'], row['substitutions']+row['deletions']+row['insertions'])
            self.assertEqual(len(hypothesis), len(reference)-row['deletions']+row['insertions'])

    def test_deterministic_ambiguous_tie_prefers_substitution(self):
        row = counts("甲乙", "乙甲", "verbatim")
        self.assertEqual((row['substitutions'], row['deletions'], row['insertions']), (2, 0, 0))

    def test_empty_hypothesis_is_all_deletions(self):
        row = counts("你好", "")
        self.assertEqual((row['deletions'], row['cer']), (2, 1.0))

    def test_empty_reference_is_undefined_not_zero_accuracy(self):
        row = counts("", "甲乙")
        self.assertIsNone(row['cer'])
        self.assertEqual(row['insertions'], 2)
        self.assertIsNone(aggregate([])['cer'])

    def test_insertions_can_exceed_one_hundred_percent(self):
        self.assertEqual(counts("甲", "甲乙丙丁")['cer'], 3.0)

    def test_micro_averaging_uses_total_reference_length(self):
        rows = [counts("甲", "乙"), counts("甲"*9, "甲"*9)]
        self.assertEqual(aggregate(rows)['cer'], 0.1)
        self.assertEqual(aggregate(iter(rows))['cer'], 0.1)

    def test_normalization_is_explicit_and_does_not_hide_semantic_errors(self):
        self.assertEqual(normalize("ＡＢＣ， 你好！", "speech-v1"), "abc你好")
        self.assertNotEqual(normalize("一百元", "speech-v1"), normalize("100元", "speech-v1"))
        self.assertNotEqual(normalize("不是", "speech-v1"), normalize("是", "speech-v1"))
        self.assertNotEqual(normalize("臺灣", "speech-v1"), normalize("台湾", "speech-v1"))
        self.assertEqual(normalize("$100+20", "speech-v1"), "$100+20")
        self.assertEqual(normalize("A， B", "verbatim"), "A， B")

    def test_codepoints_not_utf16_or_graphemes_or_words(self):
        self.assertEqual(counts("你好😀", "你好")['reference_characters'], 3)
        self.assertEqual(counts("WiFi", "wifi")['reference_characters'], 4)

    def test_invalid_unicode_and_bounds_fail_before_alignment(self):
        for text in ("甲"*(MAX_CODEPOINTS+1), "\ud800", "\u202e甲", "甲\n乙", "甲\u200b"):
            with self.assertRaises(ValueError):
                counts(text, "甲")
        with self.assertRaises(ValueError):
            normalize("甲", "unversioned")


class FixtureTests(unittest.TestCase):
    def test_default_report_row_omits_transcripts_but_counts_empty_output(self):
        case = {'id': 'synthetic', 'category': 'short', 'reference': '合成原文'}
        row = voice_quality.case_result(case, '', 1.0, {})
        self.assertNotIn('reference', row)
        self.assertNotIn('hypothesis', row)
        self.assertEqual(row['metrics']['speech-v1']['deletions'], 4)
        explicit = voice_quality.case_result(case, '合成识别', 1.0, {}, include_text=True)
        self.assertEqual(explicit['hypothesis'], '合成识别')

    def test_failure_replaces_old_success_and_saves_no_exception_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp)/'report.json'
            voice_quality.save_json(report, {'complete': True, 'hypothesis': 'old text'})
            with patch.object(voice_quality, 'evaluate_synthetic', side_effect=ValueError('do not log this message')):
                with self.assertRaises(ValueError):
                    voice_quality.evaluate(Path(tmp), Path(tmp), 2, 3, report, False)
            actual = json.loads(report.read_text())
            self.assertFalse(actual['complete'])
            self.assertEqual(actual['error_type'], 'ValueError')
            self.assertNotIn('old text', report.read_text())
            self.assertNotIn('do not log', report.read_text())

    def make_fixture(self, root):
        config = {"cases": [{"id": "case"}]}
        wav = root/"case.wav"
        with wave.open(str(wav), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(16000)
            output.writeframes(b'\0\0'*160)
        spec = {"size": wav.stat().st_size, "sha256": hashlib.sha256(wav.read_bytes()).hexdigest(), "samples": 160}
        manifest = {"schema_version": 1, "kind": "synthetic", "definition_sha256": voice_quality.sha256(voice_quality.DEFINITION), "files": {"case.wav": spec}}
        voice_quality.save_json(root/"manifest.json", manifest)
        return config, manifest

    def test_definition_is_fixed_synthetic_and_covers_declared_diagnostics(self):
        config = voice_quality.definition()
        self.assertEqual(config['kind'], 'synthetic')
        self.assertEqual(len(config['cases']), 12)
        self.assertTrue({'negation', 'numbers', 'code-switch', 'english', 'names', 'rate'} <= {case['category'] for case in config['cases']})

    def test_verified_audio_tamper_and_member_set_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config, manifest = self.make_fixture(root)
            voice_quality.verify_fixtures(root, config)
            (root/'case.wav').write_bytes(b'not PCM')
            with self.assertRaises(ValueError):
                voice_quality.verify_fixtures(root, config)
            config, manifest = self.make_fixture(root)
            manifest['files']['../extra.wav'] = manifest['files']['case.wav']
            voice_quality.save_json(root/'manifest.json', manifest)
            with self.assertRaises(ValueError):
                voice_quality.verify_fixtures(root, config)

    def test_stale_definition_and_truncated_pcm_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config, manifest = self.make_fixture(root)
            manifest['definition_sha256'] = '0'*64
            voice_quality.save_json(root/'manifest.json', manifest)
            with self.assertRaises(ValueError):
                voice_quality.verify_fixtures(root, config)
            config, manifest = self.make_fixture(root)
            wav = root/'case.wav'
            wav.write_bytes(wav.read_bytes()[:-2])
            manifest['files']['case.wav'].update(size=wav.stat().st_size, sha256=voice_quality.sha256(wav))
            voice_quality.save_json(root/'manifest.json', manifest)
            with self.assertRaises(ValueError):
                voice_quality.verify_fixtures(root, config)
            with self.assertRaises(ValueError):
                voice_probe.read_audio(wav)

    def test_invalid_definition_does_not_run_synthesizer(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)/'definition.json'
            config = voice_quality.definition()
            config['cases'][1]['id'] = config['cases'][0]['id']
            source.write_text(json.dumps(config), encoding='utf-8')
            with patch.object(voice_quality, 'DEFINITION', source), patch.object(voice_quality.subprocess, 'check_output') as synth:
                with self.assertRaises(ValueError):
                    voice_quality.generate(Path(tmp)/'out', 'unused')
                synth.assert_not_called()


if __name__ == '__main__':
    unittest.main()
