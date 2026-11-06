# SPDX-License-Identifier: GPL-3.0-only
"""Host validation of model evidence boundaries; these tests do not run JNI/model inference."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

from android_voicekey_model_smoke import (COMMIT_CASES, MODEL_CASES,
                                         verify_package, verify_reports)
from voicekey_cpu_probe import Blocked


class VoiceKeyModelEvidenceTest(unittest.TestCase):
    def fixtures(self):
        lock = {'model_id': 'synthetic-metadata-only', 'package': {'sha256': 'a'*64}}
        model = {'status': 'PASS', 'synthetic': True, 'engine_id': 'voicekey',
                 'native_engine_id': 'voicekey-wechat', 'model_id': lock['model_id'],
                 'model_package_sha256': 'a'*64, 'selected_engine': 'voicekey',
                 'xasr_model_present': False, 'model_decode_executed': True,
                 'cleanup_completed': True,
                 'voicekey_selected_for_following_ime_probe': True,
                 'result': {'recognized_codepoints': 3, 'hypothesis_sha256': 'b'*64},
                 'fixture_sha256': 'c'*64, 'partial_updates': 1,
                 'first_nonempty_partial_ms': 100,
                 'cases': [{'id': name, 'passed': True} for name in sorted(MODEL_CASES)]}
        commit = {'synthetic': True, 'actual_jni_decoded_text_used': True,
                  'recognized_codepoints': 3,
                  'cases': [{'id': name, 'passed': True, 'actual_editable_verified': True}
                            for name in sorted(COMMIT_CASES)]}
        states = {'permission_granted': (True, True), 'permission_denied': (False, False),
                  'permission_revoked_before_confirmation': (True, False)}
        for row in commit['cases']:
            if row['id'] in states:
                initial, final = states[row['id']]
                row.update(initial_permission_granted=initial, final_permission_granted=final,
                           production_permission_queries=2, os_permission_revocation_tested=False,
                           permission_state_scope='Host-only metadata fixture; not a JNI execution.',
                           commit_attempts=1 if final else 0)
        return lock, model, commit

    def test_complete_metadata_shape(self):
        lock, model, commit = self.fixtures()
        verify_reports(model, commit, lock)

    def test_evidence_cannot_substitute_default_engine_or_missing_decode(self):
        lock, model, commit = self.fixtures()
        for key, value in [('engine_id', 'xasr'), ('native_engine_id', 'xasr'),
                           ('selected_engine', 'xasr'), ('xasr_model_present', True),
                           ('model_package_sha256', 'd'*64),
                           ('model_id', 'unknown'), ('model_decode_executed', False),
                           ('voicekey_selected_for_following_ime_probe', False),
                           ('cleanup_completed', False), ('partial_updates', 0),
                           ('first_nonempty_partial_ms', None)]:
            changed = copy.deepcopy(model)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_reports(changed, commit, lock)

    def test_empty_result_missing_or_duplicated_case_is_rejected(self):
        lock, model, commit = self.fixtures()
        for mutation in ('empty', 'missing', 'duplicate', 'failed'):
            changed = copy.deepcopy(model)
            if mutation == 'empty': changed['result']['recognized_codepoints'] = 0
            if mutation == 'missing': changed['cases'].pop()
            if mutation == 'duplicate': changed['cases'].append(changed['cases'][0])
            if mutation == 'failed': changed['cases'][0]['passed'] = False
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                verify_reports(changed, commit, lock)

    def test_confirmation_must_use_real_decoded_text_and_actual_editable(self):
        lock, model, commit = self.fixtures()
        for mutation in ('wrong_length', 'no_jni', 'not_editable', 'missing'):
            changed = copy.deepcopy(commit)
            if mutation == 'wrong_length': changed['recognized_codepoints'] = 4
            if mutation == 'no_jni': changed['actual_jni_decoded_text_used'] = False
            if mutation == 'not_editable': changed['cases'][0]['actual_editable_verified'] = False
            if mutation == 'missing': changed['cases'].pop()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                verify_reports(model, changed, lock)

    def test_permission_evidence_cannot_claim_revocation_or_accept_late_commit(self):
        lock, model, commit = self.fixtures()
        for key, value in [('final_permission_granted', True), ('commit_attempts', 1),
                           ('production_permission_queries', 1), ('os_permission_revocation_tested', True),
                           ('permission_state_scope', '')]:
            changed = copy.deepcopy(commit)
            row = next(r for r in changed['cases'] if r['id'] == 'permission_revoked_before_confirmation')
            row[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_reports(model, changed, lock)

    def test_prepared_package_must_match_fixed_hash_size_and_not_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory)/'synthetic-integrity-fixture'
            raw = b'host-only integrity boundary, never model inference'
            package.write_bytes(raw)
            lock = {'package': {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}}
            verify_package(package, lock)
            package.write_bytes(raw[:-1]+b'?')
            with self.assertRaises(Blocked): verify_package(package, lock)
            package.write_bytes(raw[:-1])
            with self.assertRaises(Blocked): verify_package(package, lock)
            link = package.with_name('link')
            link.symlink_to(package)
            with self.assertRaises(Blocked): verify_package(link, lock)


if __name__ == '__main__':
    unittest.main()
