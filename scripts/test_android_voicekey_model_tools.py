# SPDX-License-Identifier: GPL-3.0-only
"""Host validation of model evidence boundaries; these tests do not run JNI/model inference."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

from android_voicekey_model_smoke import (COMMIT_CASES, MODEL_CASES,
                                         verify_package, verify_reports, verify_boundary_report, verify_watchdog_report)
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
        model.update(audio_samples=125490, independent_producer=True,
                     producer_clock='monotonic_absolute_deadlines',
                     production_pcm_queue_reused=True, production_pcm_queue_capacity=602,
                     trailing_zero_samples=371, accepted_samples=125861, produced_samples=125861,
                     producer_first_frame_ms=100, producer_eof_elapsed_ms=7867,
                     producer_epoch_elapsed_realtime_ms=1000, producer_eof_elapsed_realtime_ms=8867,
                     producer_failure=False, producer_exited=True, producer_cleanup_completed=True,
                     provider_selection_method='stable_observed_filename_physical_touch',
                     provider_exit_observed=True, import_complete_status_observed=True,
                     drain_after_producer_eof_ms=100, remaining_final_timeout_ms=29900,
                     producer_eof_to_final_ms=200, stop_to_final_ms=200, finish_call_ms=100)
        model.update(provider_pointer_id=0, provider_pointer_count=1, provider_tool_type=1,
                     provider_event_source=4098, provider_physical_selection_count=1,
                     provider_touch_point=[100, 300],
                     provider_selected_filename_bounds=[74, 290, 144, 310])
        commit = {'synthetic': True, 'actual_jni_decoded_text_used': True,
                  'recognized_codepoints': 3,
                  'cases': [{'id': name, 'passed': True, 'actual_editable_verified': True}
                            for name in sorted(COMMIT_CASES)]}
        next(r for r in model['cases'] if r['id'] == 'no_samples')['invalid_finish_budget_session_preserved'] = True
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

    def test_boundary_requires_real_timeout_abi_and_exact_cases(self):
        row = {'engine_id': 'voicekey', 'model_decode_executed': False,
               'finish_timeout_contract_checked': True,
               'cases': [{'id': name, 'passed': True} for name in
                         ('bounded_allocation', 'foreign_owner', 'invalid_parameters',
                          'cancel_close_stale', 'reclaimed_idle_release')]}
        verify_boundary_report(row)
        for key, value in [('finish_timeout_contract_checked', False), ('model_decode_executed', True)]:
            changed = copy.deepcopy(row); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): verify_boundary_report(changed)
        changed = copy.deepcopy(row); changed['cases'].append(changed['cases'][0])
        with self.assertRaises(ValueError): verify_boundary_report(changed)

    def test_watchdog_scope_cannot_claim_active_inference_or_skip_physical_exit(self):
        lock = {'model_id': 'host-metadata-only', 'package': {'sha256': 'a'*64}}
        report = dict(id='actual_android_stop_watchdog', engine_id='voicekey',
                      native_engine_id='voicekey-wechat', model_id=lock['model_id'],
                      model_package_sha256='a'*64, callback_count=1,
                      callback_elapsed_ms=30010, remaining_budget_ms=0,
                      audio_record_executed=False, matrix_inference_executed=False,
                      system_ime_executed=False)
        for key in ('passed', 'synthetic', 'controlled_capture_state', 'real_android_timer',
                    'real_model_native_session_started', 'callback_on_main_thread',
                    'timeout_result_empty', 'native_finish_rejected_cancelled',
                    'physical_idle_worker_exit_confirmed', 'cleanup_completed'):
            report[key] = True
        verify_watchdog_report(report, lock)
        for key, value in [('matrix_inference_executed', True), ('physical_idle_worker_exit_confirmed', False),
                           ('callback_elapsed_ms', 29999), ('remaining_budget_ms', 1), ('callback_count', 2)]:
            changed = copy.deepcopy(report); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): verify_watchdog_report(changed, lock)

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

    def test_independent_producer_samples_clock_cleanup_and_shared_deadline(self):
        lock, model, commit = self.fixtures()
        for key, value in [('independent_producer', False), ('production_pcm_queue_reused', False),
                           ('production_pcm_queue_capacity', 603), ('accepted_samples', 125860),
                           ('produced_samples', 125862), ('producer_first_frame_ms', 0),
                           ('producer_eof_elapsed_ms', 10000), ('producer_failure', True),
                           ('producer_exited', False), ('producer_cleanup_completed', False),
                           ('provider_exit_observed', False), ('import_complete_status_observed', False),
                           ('remaining_final_timeout_ms', 30000), ('producer_eof_to_final_ms', 30001)]:
            changed = copy.deepcopy(model)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_reports(changed, commit, lock)

    def test_selection_requires_finger_source_and_one_observed_target(self):
        lock, model, commit = self.fixtures()
        for key, value in [('provider_tool_type', 0), ('provider_event_source', 0),
                           ('provider_pointer_id', 1), ('provider_pointer_count', 2),
                           ('provider_physical_selection_count', 2),
                           ('provider_touch_point', [200, 300]),
                           ('provider_selected_filename_bounds', [])]:
            changed = copy.deepcopy(model)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_reports(changed, commit, lock)

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
