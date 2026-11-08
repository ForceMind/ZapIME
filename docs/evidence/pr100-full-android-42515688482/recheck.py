#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Report-only replay. No ADB, model inference, network or fixture reconstruction."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import stat
import sys
import textwrap
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCRATCH = ROOT / 'target/pr100-full-original'
for item in json.loads((OUT / 'producer-sources.json').read_text()):
    assert hashlib.sha256((ROOT / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
sys.path.insert(0, str(ROOT / 'scripts'))
from voicekey_cpu_probe import trusted_lock
from android_voicekey_model_smoke import verify_reports, verify_boundary_report, verify_watchdog_report, verify_permission_cases
from android_sync_preparation_report import verify_report as verify_sync_preparation

SOURCE = '4c98059dc95c56bd46cf568ec9189b1bdbfae591'
CHECKOUT = 'c50e375966c302598dcc23dc2cc032e2ae07cb5c'
TREE = '71972650824144dcb3c59952e541ff2ea26f6b9c'
ARTIFACTS = {
    13631732190: (28, 'regular', 248729970, '5f523a8c25a340183f4cea2791ffe38e9b3472458482d64c06934ffc9a42671f'),
    13631863222: (35, 'regular', 2417942, '8656d7e552caaf8881cdba4cf4bcb0311202c2df65ab404a84aa23aee4313241'),
    13631997783: (36, 'regular', 2419193, 'e4285e117369e278661473beb1daf787bc25444099dcfb37136e13bd21a87376'),
    13631852420: (28, 'voicekey', 185148, '93f7f16eff833c4531de6420e648b0172ff9c1db87ac853f2a1cdb5322f9d127'),
    13631529441: (35, 'voicekey', 239993, '36fab662a1b20016a3e17baf72c54b5161d70b83b3227f41f3315fbcdf5be3ed'),
    13631747923: (36, 'voicekey', 238655, 'e42f181f65b19c2c1bd3f730f7e1e28f5e80a4c42fbf444c3c75ef471ee24d6e'),
}
sha = lambda b: hashlib.sha256(b).hexdigest()
load = lambda p: json.loads(p.read_text())
summary = {'source_head': SOURCE, 'actual_checkout': CHECKOUT, 'tree': TREE,
           'run': 42515688482, 'attempt': 1, 'regular': {}, 'voicekey': {},
           'preview_report': 'NOT PRODUCED: this full workflow does not exercise the Rust-only inventory preview',
           'raw_waveform_replayed_locally': False}
manifest = []
lock, _ = trusted_lock(ROOT / 'third_party/voicekey-model-lock.json')
voice_source = (ROOT / 'scripts/android_voice_smoke.py').read_text()
start = voice_source.index('    evidence=json.loads(')
end = voice_source.index('    verify_boundary_report(vk)', start) + len('    verify_boundary_report(vk)')
# Execute the exact producer's pure report-validation block, not device code.
producer_report_checks = compile(textwrap.dedent(voice_source[start:end]), 'scripts/android_voice_smoke.py:report-only-block', 'exec')

def health(p, api, regular=False):
    system = load(p / 'system-keyboard.json')
    assert system['api_level'] == api and system['system_package_verified'] is True
    assert 'org.zapime.android' not in system['selected_ime']
    teardown = p / ('ime-teardown.log' if regular else 'ime-teardown.txt')
    assert 'ZapInputMethodService' not in teardown.read_text()
    error = p / ('runtime-errors.log' if regular else 'crash.txt')
    if regular:
        assert not re.search(r'Process: org\.zapime\.android(?:\.test)?,', error.read_text())
    else:
        assert not error.read_text().strip()
    if (p / 'activity-errors.txt').exists():
        assert not re.search(r'ANR in org\.zapime\.android(?:\.|\s|\(|$)', (p / 'activity-errors.txt').read_text())

for aid, (api, kind, size, digest) in ARTIFACTS.items():
    original = SCRATCH / f'{aid}.zip'
    archive_out = OUT / (f'artifact-{aid}-reports-only.zip' if kind == 'regular' else f'artifact-{aid}.zip')
    # A replay can use preserved archives after scratch originals are discarded.
    original_present = original.exists()
    source = original if original_present else archive_out
    if original_present:
        assert original.stat().st_size == size and sha(original.read_bytes()) == digest
    else:
        expected = next(x for x in load(OUT / 'artifact-manifest.json') if x['artifact_id'] == aid)
        assert source.stat().st_size == expected['preserved_archive_size']
        assert sha(source.read_bytes()) == expected['preserved_archive_sha256']
    extracted = ROOT / f'target/pr100-replay/{aid}'
    shutil.rmtree(extracted, ignore_errors=True)
    extracted.mkdir(parents=True)
    members = []
    with zipfile.ZipFile(source) as z:
        names = z.namelist()
        assert len(names) == len(set(names))
        safe = {}
        for info in z.infolist():
            n = info.filename
            assert not Path(n).is_absolute() and '..' not in Path(n).parts
            assert not info.flag_bits & 1 and not stat.S_ISLNK(info.external_attr >> 16)
            data = z.read(info)
            keep = Path(n).suffix in {'.json', '.txt', '.log', '.png'}
            members.append({'path': n, 'size': len(data), 'sha256': sha(data), 'preserved': keep})
            if keep:
                safe[n] = data
                dest = extracted / n
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)
        if original_present:
            if kind == 'voicekey':
                assert all(m['preserved'] for m in members)
                shutil.copyfile(original, archive_out)
            else:
                with zipfile.ZipFile(archive_out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as dst:
                    for n, data in sorted(safe.items()):
                        info = zipfile.ZipInfo(n, date_time=(1980, 1, 1, 0, 0, 0))
                        info.compress_type = zipfile.ZIP_DEFLATED
                        info.external_attr = 0o100644 << 16
                        dst.writestr(info, data)
    if original_present:
        manifest.append({'artifact_id': aid, 'api': api, 'kind': kind, 'original_size': size,
                         'original_sha256': digest, 'preserved_archive': archive_out.name,
                         'preserved_archive_size': archive_out.stat().st_size,
                         'preserved_archive_sha256': sha(archive_out.read_bytes()),
                         'original_members': members})
    if kind == 'regular':
        for r in (1, 2):
            p = extracted / f'device-smoke-evidence/42515688482-1/round-{r}'
            health(p, api, regular=True)
            t = (p / 'instrumentation.log').read_text()
            assert 'PASS ZapIME native keyboard:' in t and 'INSTRUMENTATION_FAILED' not in t
        p = extracted / 'voice-android'
        assert load(p / 'combined-gates.json') == {'android_smoke_repeat.py': 0, 'android_voice_smoke.py': 0}
        assert 'PASS Android offline voice native acceptance' in (p / 'instrumentation.txt').read_text()
        assert 'PASS ZapIME voice UI:' in (p / 'ui-instrumentation.txt').read_text()
        assert 'empty_result_insert_checked=true' in (p / 'ui-instrumentation.txt').read_text()
        health(p, api)
        uid = load(p / 'offline-uid.json')
        assert all(uid[f] is True for f in ('ipv4_reject', 'ipv6_reject', 'removed'))
        exec(producer_report_checks, {'out': p, 'json': json, 'verify_permission_cases': verify_permission_cases,
                                    'verify_sync_preparation': verify_sync_preparation,
                                    'verify_boundary_report': verify_boundary_report})
        native = load(p / 'native-report.json')
        assert native['api_level'] == api and native['synthetic'] is True
        assert native['audio_samples'] == 125490 and native['expected_fragments_passed'] is True
        assert native['model'] == 'x-asr-streaming-int8-2026-06-05' and native['abi'] == 'x86_64'
        summary['regular'][str(api)] = {'native_clean_rounds': 2, 'xasr_jni': 'PASS',
          'voice_commit_cases': len(load(p / 'voice-commit-report.json')['cases']),
          'empty_pcm_cases': len(load(p / 'empty-pcm-report.json')['cases']),
          'sync_connection_cases': len(load(p / 'sync-connection-report.json')['cases']),
          'sync_profile_cases': len(load(p / 'sync-profile-report.json')['cases']),
          'sync_preparation_cases': len(load(p / 'sync-preparation-report.json')['cases']),
          'local_backup_cases': len(load(p / 'local-backup-report.json')['cases']),
          'voicekey_boundary_cases': len(load(p / 'voicekey-boundary-report.json')['cases']),
          'system_ime_exit_and_fatal_anr_checks': 'PASS', 'uid_rules_removed': True,
          'producer_report_validation_block_replayed': True}
    else:
        p = extracted / 'voicekey-android'
        model = load(p / 'voicekey-model-report.json'); commit = load(p / 'voice-commit-report.json')
        boundary = load(p / 'voicekey-boundary-report.json'); watch = load(p / 'voicekey-watchdog-report.json')
        verify_reports(model, commit, lock); verify_boundary_report(boundary); verify_watchdog_report(watch, lock)
        assert model['api_level'] == api
        assert next(c for c in model['cases'] if c['id'] == 'actual_android_stop_watchdog') == watch
        acceptance = load(p / 'acceptance-summary.json')
        assert acceptance['status'] == 'PASS' and acceptance['synthetic'] is True and acceptance['model_weights_uploaded'] is False
        assert all(acceptance[f] is True for f in ('actual_ime_service_exit', 'emulator_soft_keyboard_policy_restored', 'shared_document_removed'))
        assert acceptance['saf_jni_editable'] == acceptance['activated_system_ime_and_emulator_audio_record'] == 'PASS'
        assert acceptance['phone_and_arm'] == acceptance['natural_speech'] == 'NOT RUN'
        uid = load(p / 'offline-uid.json'); assert all(uid[f] is True for f in ('ipv4_reject','ipv6_reject','removed'))
        health(p, api)
        assert 'PASS ZapIME voice UI' in (p / 'ime-instrumentation.txt').read_text()
        prep = load(extracted / 'voicekey-prepare-report.json')
        assert prep['status'] == 'PREPARED' and prep['model_id'] == lock['model_id'] and prep['package_sha256'] == lock['package']['sha256']
        summary['voicekey'][str(api)] = {'model_cases': len(model['cases']), 'commit_cases': len(commit['cases']),
          'boundary_cases': len(boundary['cases']), 'watchdog_included_in_model_cases': True,
          'saf_jni_editable_and_actual_microphone': 'PASS', 'uid_rules_removed': True,
          'actual_ime_service_exit': True, 'shared_document_removed': True}

source_paths = ['.github/workflows/android.yml', 'scripts/android_acceptance.py', 'scripts/android_smoke.py',
 'scripts/android_smoke_repeat.py', 'scripts/android_voice_smoke.py', 'scripts/android_voicekey_model_smoke.py',
 'scripts/android_sync_preparation_report.py', 'scripts/android_ci_device.py', 'scripts/voicekey_cpu_probe.py',
 'third_party/voicekey-model-lock.json', 'platforms/android/tests/java/org/zapime/android/test/SmokeInstrumentation.java']
summary['producer_sources'] = [{'path': p, 'sha256': sha((ROOT / p).read_bytes())} for p in source_paths]
(OUT / 'strict-validation.json').write_text(json.dumps(summary, indent=2) + '\n')
if manifest:
    (OUT / 'artifact-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('PASS PR100 exact-tree report replay: native 3 APIs x2 rounds; X-ASR/native boundaries; VoiceKey 37+5 per API. No preview/phone/ARM/PCM claim.')
