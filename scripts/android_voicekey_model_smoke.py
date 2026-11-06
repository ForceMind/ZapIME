#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Official VoiceKey model SAF/JNI/IME acceptance on a fresh dedicated CI emulator.

Prepare the official package on the runner BEFORE this script (using
voicekey_cpu_probe.py prepare). Only metadata, synthetic fixture evidence and
screenshots are written to the report directory. Model weights are never APK
assets or uploaded artifacts. This is not natural speech or phone acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import wave
import zipfile

from android_ci_device import (assert_no_app_anr, collect_failure_state,
                               dedicated_ci, diagnostic_args, system_keyboard)
from android_offline_uid import offline_product
from voicekey_cpu_probe import Blocked, digest, save, trusted_lock

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ('voicekey-model-report.json', 'voice-commit-report.json', 'voicekey-boundary-report.json', 'voicekey-watchdog-report.json',
           'voicekey-provider-ui-shape.json', 'voicekey-provider-before.png', 'voicekey-provider-after.png')
MODEL_CASES = {'fresh_voicekey_only_profile', 'actual_saf_fixed_model_import',
               'voicekey_selected_without_xasr', 'no_samples', 'all_zero',
               'ignored_tail', 'positive_lsb', 'negative_lsb', 'cancelled_empty',
               'cancelled_nonzero_cross_thread',
               'actual_jni_synthetic_decode_and_tail',
               'actual_editable_production_confirmation', 'idle_release_after_real_model', 'actual_android_stop_watchdog'}
COMMIT_CASES = {'commit_once', 'reentrant_finish', 'cancelled_completion',
                'connection_changed', 'ticket_changed', 'epoch_changed',
                'selection_version_changed', 'cursor_changed_identical_context',
                'selected_range', 'unknown_selection', 'policy_excluded',
                'before_changed', 'after_changed', 'null_before', 'null_after',
                'oversize_context', 'context_exception', 'editor_rejected',
                'editor_exception', 'worker_thread_rejected', 'permission_granted',
                'permission_denied', 'permission_revoked_before_confirmation'}


def verify_package(package, lock):
    if (package.is_symlink() or not package.is_file() or
            package.stat().st_size != lock['package']['size'] or
            digest(package) != lock['package']['sha256']):
        raise Blocked('prepared_official_package_missing_or_different_from_fixed_lock')


def verify_watchdog_report(report, lock):
    required_true = ('passed', 'synthetic', 'controlled_capture_state', 'real_android_timer',
                     'real_model_native_session_started', 'callback_on_main_thread',
                     'timeout_result_empty', 'native_finish_rejected_cancelled',
                     'physical_idle_worker_exit_confirmed', 'cleanup_completed')
    if (report.get('id') != 'actual_android_stop_watchdog' or
            report.get('engine_id') != 'voicekey' or report.get('native_engine_id') != 'voicekey-wechat' or
            report.get('model_id') != lock['model_id'] or
            report.get('model_package_sha256') != lock['package']['sha256'] or
            any(report.get(key) is not True for key in required_true) or
            any(report.get(key) is not False for key in
                ('audio_record_executed', 'matrix_inference_executed', 'system_ime_executed')) or
            report.get('callback_count') != 1 or report.get('remaining_budget_ms') != 0 or
            not 30000 <= report.get('callback_elapsed_ms', -1) <= 40000):
        raise ValueError('incomplete_actual_android_idle_watchdog_evidence')


def verify_boundary_report(report):
    expected = {'bounded_allocation', 'foreign_owner', 'invalid_parameters',
                'cancel_close_stale', 'reclaimed_idle_release'}
    rows = report.get('cases', [])
    if (report.get('engine_id') != 'voicekey' or
            report.get('model_decode_executed') is not False or
            report.get('finish_timeout_contract_checked') is not True or
            len(rows) != len(expected) or {r.get('id') for r in rows} != expected or
            any(r.get('passed') is not True for r in rows)):
        raise ValueError('incomplete_real_voicekey_boundary_or_timeout_abi_evidence')


def verify_permission_cases(rows):
    expected = {'permission_granted': (True, True),
                'permission_denied': (False, False),
                'permission_revoked_before_confirmation': (True, False)}
    found = [r for r in rows if r.get('id') in expected]
    if len(found) != len(expected) or {r['id'] for r in found} != set(expected):
        raise ValueError('missing_confirmation_permission_evidence')
    for row in found:
        initial, final = expected[row['id']]
        if (row.get('initial_permission_granted') is not initial or
                row.get('final_permission_granted') is not final or
                row.get('production_permission_queries') != 2 or
                row.get('os_permission_revocation_tested') is not False or
                not row.get('permission_state_scope') or
                row.get('commit_attempts') != (1 if final else 0)):
            raise ValueError('invalid_simulated_confirmation_permission_evidence')


def verify_reports(model, commit, lock):
    verify_finger_selection(model)
    verify_independent_producer(model)
    rows = model.get('cases', [])
    if (model.get('status') != 'PASS' or model.get('synthetic') is not True or
            model.get('engine_id') != 'voicekey' or
            model.get('native_engine_id') != 'voicekey-wechat' or
            model.get('model_id') != lock['model_id'] or
            model.get('model_package_sha256') != lock['package']['sha256'] or
            model.get('selected_engine') != 'voicekey' or
            model.get('xasr_model_present') is not False or
            model.get('model_decode_executed') is not True or
            model.get('cleanup_completed') is not True or
            model.get('voicekey_selected_for_following_ime_probe') is not True or
            model.get('result', {}).get('recognized_codepoints', 0) <= 0 or
            not re.fullmatch(r'[0-9a-f]{64}', model.get('result', {}).get('hypothesis_sha256', '')) or
            not re.fullmatch(r'[0-9a-f]{64}', model.get('fixture_sha256', '')) or
            model.get('partial_updates', 0) <= 0 or
            not isinstance(model.get('first_nonempty_partial_ms'), int) or
            model['first_nonempty_partial_ms'] < 0 or
            len(rows) != len(MODEL_CASES) or
            {r.get('id') for r in rows} != MODEL_CASES or
            any(r.get('passed') is not True for r in rows)):
        raise ValueError('incomplete_real_voicekey_model_evidence')
    if next(r for r in rows if r['id'] == 'no_samples').get('invalid_finish_budget_session_preserved') is not True:
        raise ValueError('missing_live_invalid_budget_session_preservation')
    rows = commit.get('cases', [])
    if (commit.get('synthetic') is not True or
            commit.get('actual_jni_decoded_text_used') is not True or
            commit.get('recognized_codepoints') != model['result']['recognized_codepoints'] or
            len(rows) != len(COMMIT_CASES) or
            {r.get('id') for r in rows} != COMMIT_CASES or
            any(r.get('passed') is not True or
                r.get('actual_editable_verified') is not True for r in rows)):
        raise ValueError('incomplete_actual_editable_confirmation_evidence')
    verify_permission_cases(rows)


def verify_finger_selection(model):
    point = model.get('provider_touch_point')
    bounds = model.get('provider_selected_filename_bounds')
    if (model.get('provider_pointer_id') != 0 or model.get('provider_pointer_count') != 1 or
            model.get('provider_tool_type') != 1 or model.get('provider_event_source') != 4098 or
            model.get('provider_physical_selection_count') != 1 or
            not isinstance(point, list) or len(point) != 2 or
            not isinstance(bounds, list) or len(bounds) != 4 or
            any(type(n) is not int for n in point+bounds) or
            not bounds[0] <= point[0] < bounds[2] or not bounds[1] <= point[1] < bounds[3]):
        raise ValueError('missing_single_observed_finger_touch_selection_evidence')


def verify_independent_producer(model):
    audio = model.get('audio_samples', 0)
    if (not isinstance(audio, int) or audio != 125490 or
            model.get('independent_producer') is not True or
            model.get('producer_clock') != 'monotonic_absolute_deadlines' or
            model.get('production_pcm_queue_reused') is not True or
            model.get('production_pcm_queue_capacity') != 602 or
            model.get('trailing_zero_samples') != 371 or
            model.get('accepted_samples') != audio+371 or
            model.get('produced_samples') != audio+371 or
            model.get('producer_first_frame_ms', -1) < 100 or
            model.get('producer_eof_elapsed_ms', -1) < (audio+371)*1000//16000 or
            model.get('producer_eof_elapsed_realtime_ms', 0) - model.get('producer_epoch_elapsed_realtime_ms', 0) != model.get('producer_eof_elapsed_ms') or
            model.get('producer_failure') is not False or
            model.get('producer_exited') is not True or
            model.get('producer_cleanup_completed') is not True or
            model.get('provider_selection_method') != 'stable_observed_filename_physical_touch' or
            model.get('provider_exit_observed') is not True or
            model.get('import_complete_status_observed') is not True or
            not 0 <= model.get('drain_after_producer_eof_ms', -1) < 30000 or
            not 0 < model.get('remaining_final_timeout_ms', 0) <= 30000-model['drain_after_producer_eof_ms'] or
            model.get('stop_to_final_ms') != model.get('producer_eof_to_final_ms') or
            not 0 <= model.get('finish_call_ms', -1) <= model.get('stop_to_final_ms', -1) or
            not 0 <= model.get('producer_eof_to_final_ms', -1) <= 30000):
        raise ValueError('missing_independent_clock_no_drop_or_shared_eof_deadline_evidence')


def verify_fixture_report(model, path):
    with wave.open(str(path)) as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise ValueError('synthetic_fixture_format_changed')
        samples = audio.getnframes()
        raw = audio.readframes(samples)
    if (not 0 < samples <= 960000 or len(raw) != samples*2 or
            model.get('audio_samples') != samples or
            model.get('fixture_sha256') != hashlib.sha256(raw).hexdigest()):
        raise ValueError('android_jni_fixture_differs_from_built_synthetic_source')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('adb')
    parser.add_argument('serial')
    parser.add_argument('--package', type=Path,
                        default=ROOT/'target/voicekey-probe/source/official-package.apk')
    args = parser.parse_args()
    command = [args.adb, '-s', args.serial]
    if not dedicated_ci(command):
        raise SystemExit('Dedicated numeric-run GitHub emulator required; never run on a personal device')
    out = ROOT/'target/voicekey-android'
    out.mkdir(parents=True, exist_ok=True)
    lock, _ = trusted_lock(ROOT/'third_party/voicekey-model-lock.json')
    summary = {'schema_version': 1, 'status': 'FAIL', 'engine_id': 'voicekey',
               'native_engine_id': 'voicekey-wechat', 'model_id': lock['model_id'],
               'model_package_sha256': lock['package']['sha256'], 'synthetic': True,
               'model_weights_uploaded': False, 'natural_speech': 'NOT RUN',
               'phone_and_arm': 'NOT RUN',
               'scope': 'Real SAF official-model import, actual JNI synthetic decoding and Editable transaction, followed by activated system IME emulator microphone/stop/retry/cancel/focus/cursor/hide lifecycle. Synthetic result insertion is method-level production transaction; not physical system-IME confirmation or natural speech quality.'}
    def run(*arguments, **options):
        options.setdefault('check', True)
        options.setdefault('timeout', 30)
        return subprocess.run(command+list(arguments), **options)
    def pull_reports():
        for name in REPORTS:
            run('pull', '/sdcard/Android/data/org.zapime.android/files/voice-fixtures/'+name,
                str(out/name), check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for name in ('voice-recording.png', 'voice-stopped.png', 'hierarchy.txt',
                     'smoke.png', 'host-lifecycle.txt', 'ci-activity-top.txt',
                     'ci-activity-state.txt', 'ci-window-state.txt',
                     'ci-input-service.txt', 'ci-system-runtime.txt'):
            run('pull', '/sdcard/Android/data/org.zapime.android.test/files/'+name,
                str(out/name), check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fixture = 'ZapIME-VoiceKey-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']+'.apk'
    shared_path = '/sdcard/Download/'+fixture
    pushed = False
    try:
        verify_package(args.package, lock)
        for apk in (ROOT/'target/android-voice-tests/ZapIME-voice-tests.apk',
                    ROOT/'target/android-tests/ZapIME-tests.apk'):
            if not apk.is_file():
                raise Blocked('built_signed_test_harness_missing')
            with zipfile.ZipFile(apk) as archive:
                if any(n.endswith('.xnet') or n.endswith('ime_asr_decoder_model.zip')
                       for n in archive.namelist()):
                    raise ValueError('voicekey_weights_in_test_apk')
            run('install', '-r', '-t', str(apk))
        # Only a fresh product profile is accepted by the Java probe. No pm clear,
        # private-directory push/chown, synthetic package ownership or X-ASR import.
        run('shell', 'pm', 'revoke', 'org.zapime.android', 'android.permission.RECORD_AUDIO')
        run('shell', 'mkdir', '-p', '/sdcard/Download')
        run('push', str(args.package), shared_path, timeout=120)
        pushed = True
        system_method, proof = system_keyboard(command)
        save(out/'system-keyboard.json', proof)
        run('shell', 'svc', 'wifi', 'disable')
        run('shell', 'svc', 'data', 'disable')
        run('logcat', '-c')
        with offline_product(command, out):
            try:
                completed = run('shell', 'am', 'instrument', '-w', '-r',
                                '-e', 'zapime_ci_voicekey_model',
                                os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT'],
                                'org.zapime.android.voicetest/.VoiceInstrumentation',
                                text=True, capture_output=True, timeout=900)
                (out/'model-instrumentation.txt').write_text(completed.stdout+completed.stderr)
                print(completed.stdout, flush=True)
                if ('PASS Android VoiceKey official model acceptance' not in completed.stdout or
                        'INSTRUMENTATION_FAILED' in completed.stdout or
                        'FAIL Android voice' in completed.stdout):
                    raise ValueError('voicekey_model_instrumentation_failed')
                pull_reports()
                model = json.loads((out/'voicekey-model-report.json').read_text())
                commit = json.loads((out/'voice-commit-report.json').read_text())
                verify_reports(model, commit, lock)
                verify_boundary_report(json.loads((out/'voicekey-boundary-report.json').read_text()))
                watchdog = json.loads((out/'voicekey-watchdog-report.json').read_text())
                verify_watchdog_report(watchdog, lock)
                if next(r for r in model['cases'] if r['id'] == 'actual_android_stop_watchdog') != watchdog:
                    raise ValueError('watchdog_case_and_report_differ')
                verify_fixture_report(model, ROOT/'target/voice-probe/synthetic.wav')
                summary['saf_jni_editable'] = 'PASS'
                run('shell', 'pm', 'grant', 'org.zapime.android', 'android.permission.RECORD_AUDIO')
                for action in ('enable', 'set'):
                    run('shell', 'ime', action, 'org.zapime.android/.ZapInputMethodService')
                completed = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'voice_only', 'true',
                                *diagnostic_args(command),
                                'org.zapime.android.test/.SmokeInstrumentation',
                                text=True, capture_output=True, timeout=900)
                (out/'ime-instrumentation.txt').write_text(completed.stdout+completed.stderr)
                print(completed.stdout, flush=True)
                if ('PASS ZapIME voice UI:' not in completed.stdout or
                        'INSTRUMENTATION_FAILED' in completed.stdout):
                    raise ValueError('selected_voicekey_system_ime_lifecycle_failed')
                summary['activated_system_ime_and_emulator_audio_record'] = 'PASS'
            finally:
                # Keep isolation until after real system-IME switch and service exit.
                for action in ('enable', 'set'):
                    run('shell', 'ime', action, system_method)
                for attempt in range(100):
                    teardown = run('shell', 'dumpsys', 'activity', 'services',
                                   'org.zapime.android', text=True, capture_output=True).stdout
                    if 'ZapInputMethodService' not in teardown:
                        break
                    time.sleep(.1)
                else:
                    raise ValueError('voicekey_ime_service_did_not_exit')
                (out/'ime-teardown.txt').write_text(teardown)
                summary['actual_ime_service_exit'] = True
                pull_reports()
                collect_failure_state(command, out)
                crash = run('logcat', '-d', '-b', 'crash', text=True, capture_output=True).stdout
                (out/'crash.txt').write_text(crash)
                if 'org.zapime.android' in crash and ('FATAL EXCEPTION' in crash or 'Fatal signal' in crash):
                    raise ValueError('voicekey_target_process_fatal')
                assert_no_app_anr(command, out)
        summary['status'] = 'PASS'
        print('PASS isolated VoiceKey: fixed-model real SAF/JNI/Editable plus selected-engine emulator IME lifecycle', flush=True)
    except Exception as error:
        summary['status'] = 'BLOCKED' if isinstance(error, (Blocked, FileNotFoundError)) else 'FAIL'
        summary['reason'] = str(error) if isinstance(error, (Blocked, ValueError)) else type(error).__name__
        raise
    finally:
        try:
            if pushed:
                # The exact numeric-run fixture is the only shared document removed.
                run('shell', 'rm', shared_path)
            summary['shared_document_removed'] = True
        except Exception:
            summary['shared_document_removed'] = False
            summary['status'] = 'FAIL'
            raise
        finally:
            save(out/'acceptance-summary.json', summary)


if __name__ == '__main__':
    main()
