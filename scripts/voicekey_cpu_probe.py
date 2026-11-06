#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Strict, explicit model compatibility probe; never downloads during inference.

Only a separately reviewed PINNED lock is accepted. Observed hashes or an
installation's self-generated manifest are not promoted to trust automatically.
The Rust process receives only verified model files and fixed synthetic PCM.
"""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import platform
import re
import resource
import subprocess
import sys
import tempfile
import time
import zipfile
from voice_quality_metrics import PROFILES, counts

ROOT = Path(__file__).resolve().parents[1]
MAX_MODEL = 256 * 1024 * 1024
MAX_PCM = 960000 * 2
HEX = re.compile(r'[0-9a-f]{64}')
class Blocked(Exception): pass

def digest(path):
    with path.open('rb') as source: return hashlib.file_digest(source, 'sha256').hexdigest()

def load_json(path, limit=262144):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit: raise ValueError('invalid_metadata_file')
    return json.loads(path.read_text(encoding='utf-8'))

def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as out:
        pending=Path(out.name)
        try:
            json.dump(value,out,ensure_ascii=False,indent=2,allow_nan=False);out.write('\n');out.close();pending.replace(path)
        finally: pending.unlink(missing_ok=True)

def member_path(name):
    if (not isinstance(name,str) or not name or '\\' in name or '\0' in name or
            PurePosixPath(name).is_absolute() or any(x in ('','.','..') for x in name.split('/'))):
        raise ValueError('unsafe_member_path')

def trusted_lock(path):
    from audit_voicekey_model import URL, SOURCE_COMMIT, INNER
    lock=load_json(path)
    if lock.get('schema_version') != 1 or lock.get('status') != 'PINNED': raise Blocked('trusted_model_identity_not_pinned')
    if not isinstance(lock.get('model_id'),str) or not lock['model_id'] or len(lock['model_id'])>128: raise ValueError('invalid_model_id')
    package=lock.get('package',{});source=lock.get('source',{})
    if (not isinstance(package.get('size'),int) or not 0<package['size']<=MAX_MODEL or
            not HEX.fullmatch(package.get('sha256') or '') or not source.get('url','').startswith('https://')):
        raise Blocked('trusted_package_identity_missing')
    if source.get('url')!=URL or source.get('repository')!='J3n5en/voicekey' or source.get('commit')!=SOURCE_COMMIT: raise ValueError('unapproved_model_origin')
    inner=lock.get('inner_archive',{})
    if inner.get('path')!=INNER or not isinstance(inner.get('size'),int) or not 0<inner['size']<=MAX_MODEL or not HEX.fullmatch(inner.get('sha256') or ''): raise ValueError('invalid_inner_identity')
    outer=lock.get('outer_members',[])
    if not isinstance(outer,list) or not 1<=len(outer)<=128: raise ValueError('invalid_outer_identity')
    seen=set()
    for row in outer:
        member_path(row.get('path'))
        if row['path'] in seen or not isinstance(row.get('size'),int) or not 0<=row['size']<=MAX_MODEL or not HEX.fullmatch(row.get('sha256') or '') or not isinstance(row.get('directory'),bool): raise ValueError('invalid_outer_identity')
        seen.add(row['path'])
    matches=[row for row in outer if row['path']==INNER]
    if len(matches)!=1 or matches[0]['directory'] or matches[0]['size']!=inner['size'] or matches[0]['sha256']!=inner['sha256']: raise ValueError('inconsistent_inner_identity')
    files=lock.get('files',{})
    if not isinstance(files,dict) or not 1<=len(files)<=128: raise Blocked('trusted_member_identity_missing')
    outputs={};total=0
    for name,spec in files.items():
        member_path(name)
        if not isinstance(spec,dict) or not isinstance(spec.get('size'),int) or not 0<=spec['size']<=MAX_MODEL or not HEX.fullmatch(spec.get('sha256') or ''): raise ValueError('invalid_member_identity')
        total+=spec['size']
        if total>MAX_MODEL: raise ValueError('model_expansion_limit')
        output=spec.get('output')
        if output is not None:
            if not isinstance(output,str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,128}',output) or output in ('.','..') or output in outputs: raise ValueError('invalid_member_output')
            outputs[output]=spec
    if 'dict.decoder.utf8.txt' not in outputs or sum(x.endswith('.xnet') for x in outputs)!=1 or len(outputs)!=2: raise ValueError('ambiguous_model_combination')
    return lock,outputs

def verify_model(directory, outputs):
    if directory.is_symlink() or not directory.is_dir(): raise Blocked('model_directory_missing')
    if {p.name for p in directory.iterdir()} != set(outputs): raise ValueError('unexpected_model_files')
    for name,spec in outputs.items():
        p=directory/name
        if p.is_symlink() or not p.is_file() or p.stat().st_size!=spec['size'] or digest(p)!=spec['sha256']: raise ValueError('model_member_integrity')

def verify_pcm(path, manifest_path):
    manifest=load_json(manifest_path)
    if manifest.get('schema_version')!=1 or manifest.get('kind')!='synthetic' or manifest.get('format')!='pcm16le-16000-mono': raise ValueError('synthetic_pcm_manifest_required')
    reference=manifest.get('reference')
    if not isinstance(reference,str) or not reference or len(reference)>256: raise ValueError('invalid_synthetic_reference')
    if path.is_symlink() or not path.is_file() or not 0<path.stat().st_size<=MAX_PCM or path.stat().st_size%2: raise ValueError('invalid_pcm_boundary')
    if path.stat().st_size!=manifest.get('bytes') or digest(path)!=manifest.get('sha256') or path.stat().st_size!=manifest.get('samples',0)*2: raise ValueError('synthetic_pcm_integrity')
    return manifest

def percentile(values, ratio):
    if not values or not 0 < ratio <= 1 or any(not isinstance(x,(int,float)) or not math.isfinite(x) or x < 0 for x in values): raise ValueError('invalid_latency_series')
    return sorted(values)[max(0,math.ceil(len(values)*ratio)-1)]

def prepare(args):
    started=time.perf_counter()
    lock,outputs=trusted_lock(args.model_lock)
    # Reuse the official-only downloader; verify against the pre-existing lock
    # before any extraction. No automatic lock updates from observed bytes.
    from audit_voicekey_model import audit, members, INNER
    observed=audit(args.download_dir)
    if observed.get('status')!='OBSERVED': raise Blocked('official_model_source_unavailable')
    package=args.download_dir/'official-package.apk'
    if package.stat().st_size!=lock['package']['size'] or digest(package)!=lock['package']['sha256']: raise ValueError('fixed_package_sha256_mismatch')
    with zipfile.ZipFile(package) as outer:
        actual={x['path']:x for x in members(outer)}
        wanted={x['path']:x for x in lock['outer_members']}
        if set(actual)!=set(wanted): raise ValueError('outer_members_mismatch')
        for n,x in actual.items():
            s=wanted[n]
            if x['bytes']!=s['size'] or x['sha256']!=s['sha256'] or x['directory']!=s['directory']: raise ValueError('outer_identity_mismatch')
        inner_bytes=outer.read(INNER)
    inner_spec=lock['inner_archive']
    if len(inner_bytes)!=inner_spec['size'] or hashlib.sha256(inner_bytes).hexdigest()!=inner_spec['sha256']: raise ValueError('inner_identity_mismatch')
    args.model.mkdir(parents=True,exist_ok=False)
    try:
        with zipfile.ZipFile(io.BytesIO(inner_bytes)) as inner:
            rows={x['path']:x for x in members(inner)}
            if set(rows)!=set(lock['files']): raise ValueError('inner_members_mismatch')
            for name,spec in lock['files'].items():
                actual=rows[name]
                if actual['bytes']!=spec['size'] or actual['sha256']!=spec['sha256'] or actual['directory']: raise ValueError('inner_member_identity_mismatch')
                if spec['output'] is not None:
                    (args.model/spec['output']).write_bytes(inner.read(name))
        verify_model(args.model,outputs)
    except BaseException:
        for p in args.model.iterdir():p.unlink()
        args.model.rmdir();raise
    return {'status':'PREPARED','engine_id':'voicekey','model_id':lock['model_id'],'package_sha256':lock['package']['sha256'],'source_download_hash_and_extraction_ms':(time.perf_counter()-started)*1000,'redistribution_permission':'NOT ESTABLISHED; no weights uploaded'}

def fixture(args):
    import voice_probe
    import wave
    voice_probe.synthesize(args.wav,args.espeak)
    with wave.open(str(args.wav)) as audio:
        if (audio.getnchannels(),audio.getsampwidth(),audio.getframerate())!=(1,2,16000): raise ValueError('invalid_synthesis_format')
        raw=audio.readframes(audio.getnframes());samples=audio.getnframes()
    if not 0<len(raw)<=MAX_PCM or len(raw)!=samples*2: raise ValueError('invalid_synthesis_size')
    args.pcm.write_bytes(raw)
    save(args.fixture_manifest,{'schema_version':1,'kind':'synthetic','format':'pcm16le-16000-mono','reference':voice_probe.SYNTHETIC_TEXT,'bytes':len(raw),'samples':samples,'sha256':digest(args.pcm),'generator':subprocess.check_output([args.espeak,'--version'],text=True).strip()})
    return {'status':'SYNTHESIZED','samples':samples,'sha256':digest(args.pcm)}

def run(args):
    args.native_started=False
    verification_started=time.perf_counter()
    lock,outputs=trusted_lock(args.model_lock);verify_model(args.model,outputs);meta=verify_pcm(args.pcm,args.fixture_manifest)
    verification_ms=(time.perf_counter()-verification_started)*1000
    if not 1<=args.rounds<=10: raise ValueError('rounds_boundary')
    if args.binary.is_symlink() or not args.binary.is_file(): raise Blocked('probe_binary_missing')
    # Seccomp is installed before spawning the native process and inherited by
    # its threads. No network capability is needed to load or infer.
    from qwen_translation_probe.network import deny_network
    isolation=deny_network()
    started=time.perf_counter();before=resource.getrusage(resource.RUSAGE_CHILDREN)
    args.native_started=True
    completed=subprocess.run([str(args.binary.resolve()),str(args.model.resolve()),str(args.pcm.resolve()),str(args.rounds),'paced' if args.paced else 'unpaced'],capture_output=True,timeout=900,check=False)
    usage=resource.getrusage(resource.RUSAGE_CHILDREN)
    if completed.returncode!=0: raise ValueError('native_model_probe_failed')
    raw=json.loads(completed.stdout)
    if raw.get('complete') is not True or raw.get('engine_id')!='voicekey': raise ValueError('incomplete_native_report')
    reference=meta['reference']
    for group in ['cases','runs']:
        for row in raw[group]:
            if 'text' not in row: continue
            text=row.pop('text')
            if not isinstance(text,str) or len(text)>2048: raise ValueError('invalid_native_transcript')
            row['recognized_codepoints']=len(text);row['hypothesis_sha256']=hashlib.sha256(text.encode()).hexdigest()
            if group=='runs':
                row['metrics']={profile:counts(reference,text,profile) for profile in PROFILES}
                if not text.strip(): raise ValueError('synthetic_fixture_empty_transcript')
            if args.include_text: row['hypothesis']=text
    summary={}
    for mode in ['release_before_start','repeated_start']:
        rows=[x for x in raw['runs'] if x['mode']==mode]
        summary[mode]={}
        for key in ['model_start_ms','finish_call_ms','feed_and_finish_ms','first_partial_observed_ms']:
            values=[x[key] for x in rows if x[key] is not None]
            summary[mode][key]={'measured_rounds':len(values),'total_rounds':len(rows),'p50':percentile(values,.5) if values else None,'p95':percentile(values,.95) if values else None}

    result={'schema_version':1,'status':'PASS','engine_id':'voicekey','model_id':lock['model_id'],'model_lock_sha256':digest(args.model_lock),'model_package_sha256':lock['package']['sha256'],'model_files':outputs,'platform':platform.platform(),'architecture':platform.machine(),'synthetic':True,'distinct_audio_samples':1,'repeated_rounds_are_not_new_speech_samples':True,'fixed_model_and_fixture_hash_verification_ms':verification_ms,'fixture_sha256':meta['sha256'],'reference_sha256':hashlib.sha256(reference.encode()).hexdigest(),'audio_samples':meta['samples'],'network_restriction':isolation,'native':raw,'latency_summary_ms':summary,'process_seconds':time.perf_counter()-started,'child_cpu_seconds':(usage.ru_utime+usage.ru_stime)-(before.ru_utime+before.ru_stime),'child_peak_rss':usage.ru_maxrss,'rss_unit':'KiB on Linux, bytes on macOS','energy':'NOT MEASURED','scope':'Model-driven Rust CPU compatibility on the recorded runner architecture, not JNI/AudioRecord/IME, natural speech or ARM phone acceptance. Both series reload weights after physical worker exit; repeated_start does not retain an engine model cache. OS page cache is warm after integrity/parse checks. RTF is not end-to-end latency; CER is not accuracy. Model redistribution permission not established.'}
    if args.include_text:result['reference']=reference
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','fixture','run','check']);p.add_argument('--model-lock',type=Path,default=ROOT/'third_party/voicekey-model-lock.json');p.add_argument('--model',type=Path,default=ROOT/'target/voicekey-probe/model');p.add_argument('--download-dir',type=Path,default=ROOT/'target/voicekey-probe/source');p.add_argument('--wav',type=Path,default=ROOT/'target/voicekey-probe/synthetic.wav');p.add_argument('--pcm',type=Path,default=ROOT/'target/voicekey-probe/synthetic.pcm');p.add_argument('--fixture-manifest',type=Path,default=ROOT/'target/voicekey-probe/fixture.json');p.add_argument('--espeak',default='espeak-ng');p.add_argument('--binary',type=Path,default=ROOT/'integrations/voicekey/target/release/examples/offline_probe');p.add_argument('--rounds',type=int,default=3);p.add_argument('--paced',action='store_true');p.add_argument('--include-text',action='store_true');p.add_argument('--report',type=Path,default=ROOT/'target/voicekey-probe/report.json');args=p.parse_args()
    args.native_started=False
    try:
        if args.action=='prepare':result=prepare(args)
        elif args.action=='fixture':args.pcm.parent.mkdir(parents=True,exist_ok=True);result=fixture(args)
        elif args.action=='check':lock,outputs=trusted_lock(args.model_lock);verify_model(args.model,outputs);verify_pcm(args.pcm,args.fixture_manifest);result={'status':'VERIFIED_INPUTS','model_id':lock['model_id']}
        else:result=run(args)
        save(args.report,result);print(json.dumps({'status':result['status'],'report':str(args.report)},ensure_ascii=False));return 0
    except Exception as error:
        result={'schema_version':1,'status':'BLOCKED' if isinstance(error,(Blocked,FileNotFoundError)) else 'FAIL','reason':str(error) if isinstance(error,(Blocked,ValueError)) else type(error).__name__,'engine_id':'voicekey','native_process_started':args.native_started,'model_decode_executed':False if not args.native_started else 'NOT ESTABLISHED'}
        save(args.report,result);print(json.dumps(result,ensure_ascii=False));return 2 if result['status']=='BLOCKED' else 1
if __name__=='__main__':raise SystemExit(main())
