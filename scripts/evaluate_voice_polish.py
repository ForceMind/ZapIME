#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Offline, content-redacted evaluation against frozen synthetic voice corpus.

No model inference and no HTTP. A supplied model-output JSONL is local-only.
Only fixed case IDs, counts and guard reason codes are emitted; never text.
"""
import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'integrations/voice-polish/corpus.v1.jsonl'
PIN = ROOT / 'integrations/voice-polish/corpus.v1.sha256'
HARNESS = ROOT / 'integrations/voice-polish/VoicePolishEvalHarness.java'
PRODUCT = ROOT / 'platforms/android/java/org/zapime/android/VoicePolishSafety.java'
MAX_FILE_BYTES = 256 * 1024
MAX_CASES = 64
ID = re.compile(r'[a-z][a-z0-9-]{2,47}\Z')
ALLOWED_CATEGORIES = frozenset({'filler','repetition','negation','number','health','entity','mixed'})

class EvaluationError(Exception):
    """Fixed-code rejection, never including transcript content."""


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise EvaluationError('duplicate_json_key')
        result[key] = value
    return result


def load_rows(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_FILE_BYTES:
        raise EvaluationError('input_file_unavailable_or_oversized')
    try:
        data = path.read_bytes().decode('utf-8', errors='strict')
        lines = data.splitlines()
        if not 1 <= len(lines) <= MAX_CASES:
            raise EvaluationError('invalid_case_count')
        result = [json.loads(line, object_pairs_hook=no_duplicate_keys)
                  for line in lines]
    except (UnicodeError, json.JSONDecodeError):
        raise EvaluationError('invalid_utf8_or_json') from None
    if not all(isinstance(x, dict) for x in result):
        raise EvaluationError('invalid_json_object')
    return result


def valid_utf8_text(value):
    if not isinstance(value, str):
        return False
    try:
        value.encode('utf-8', errors='strict')
    except UnicodeError:
        return False
    return True


def corpus_with_pin(root=ROOT):
    corpus = root / 'integrations/voice-polish/corpus.v1.jsonl'
    pinfile = root / 'integrations/voice-polish/corpus.v1.sha256'
    try:
        pin = pinfile.read_text(encoding='ascii').strip()
    except (OSError, UnicodeError):
        raise EvaluationError('missing_corpus_pin') from None
    if not re.fullmatch('[0-9a-f]{64}', pin):
        raise EvaluationError('invalid_corpus_pin')
    if hashlib.sha256(corpus.read_bytes()).hexdigest() != pin:
        raise EvaluationError('corpus_pin_mismatch')
    rows = load_rows(corpus)
    if len(rows) != 24:
        raise EvaluationError('incomplete_frozen_corpus')
    known = set()
    for case in rows:
        if set(case) != {'id','category','raw','reference','preserve'}:
            raise EvaluationError('invalid_corpus_schema')
        id_, cat, raw, reference, preserve = (case[k] for k in
                ('id','category','raw','reference','preserve'))
        if (not isinstance(id_, str) or not ID.fullmatch(id_) or id_ in known
                or cat not in ALLOWED_CATEGORIES
                or not valid_utf8_text(raw) or not valid_utf8_text(reference)
                or not raw.strip() or not reference.strip()
                or len(raw)>2048 or len(reference)>2048
                or not isinstance(preserve,list) or not preserve
                or any(not valid_utf8_text(x) or not x or len(x)>120 or x not in raw
                       or x not in reference for x in preserve)):
            raise EvaluationError('invalid_corpus_case')
        known.add(id_)
    return rows, pin


def candidate_rows(path, ids):
    rows = load_rows(path)
    if len(rows) != len(ids):
        raise EvaluationError('missing_or_extra_model_candidates')
    values = {}
    for row in rows:
        if set(row) != {'id','candidate'}:
            raise EvaluationError('invalid_candidate_schema')
        id_, candidate = row['id'], row['candidate']
        if not isinstance(id_,str) or id_ not in ids or id_ in values:
            raise EvaluationError('duplicate_or_foreign_candidate_id')
        if not valid_utf8_text(candidate) or not candidate.strip() or len(candidate)>4096:
            raise EvaluationError('invalid_candidate_text')
        values[id_] = candidate
    if set(values) != set(ids):
        raise EvaluationError('candidate_set_mismatch')
    return values


def invoke_exact_java_guard(cases, root=ROOT):
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise EvaluationError('jdk_required')
    with tempfile.TemporaryDirectory(prefix='zapime-voice-eval-') as build:
        try:
            subprocess.run([javac,'-encoding','UTF-8','-d',build,
                            str(root/'platforms/android/java/org/zapime/android/VoicePolishSafety.java'),
                            str(root/'integrations/voice-polish/VoicePolishEvalHarness.java')],
                           check=True,capture_output=True,text=True,timeout=30)
            payload = '\n'.join('\t'.join(base64.b64encode(text.encode('utf-8')).decode('ascii')
                                            for text in (raw,candidate))
                                for raw,candidate in cases) + '\n'
            result = subprocess.run([java,'-cp',build,'org.zapime.android.VoicePolishEvalHarness'],
                                    input=payload,text=True,capture_output=True,timeout=30,check=True)
        except (subprocess.SubprocessError,OSError):
            raise EvaluationError('java_contract_failed') from None
    lines = result.stdout.splitlines()
    if len(lines) != len(cases):
        raise EvaluationError('java_report_length_mismatch')
    verdicts = []
    for line in lines:
        parts=line.split('\t')
        if len(parts) != 2 or not re.fullmatch('[A-Z_]+',parts[0]) or parts[1] not in ('0','1'):
            raise EvaluationError('invalid_java_verdict')
        if (parts[0]=='ACCEPTED') != (parts[1]=='1'):
            raise EvaluationError('java_guard_fallback_mismatch')
        verdicts.append(parts[0])
    return verdicts


def evaluate(corpus, candidates, guard):
    result = {'schema_version':1,'scope':'synthetic_offline_evaluation_not_semantic_accuracy',
              'count':len(corpus),'guard_accepted':0,'required_facts_kept':0,
              'reference_exact_match':0,'guard_reasons':{},'by_category':{},'failed_ids':[]}
    reasons=Counter()
    by_category={}
    for case,reason in zip(corpus,guard):
        c=candidates[case['id']]
        facts=all(term in c for term in case['preserve'])
        ref=c==case['reference']
        allowed=reason=='ACCEPTED'
        reasons[reason]+=1
        if allowed: result['guard_accepted']+=1
        if facts: result['required_facts_kept']+=1
        if ref: result['reference_exact_match']+=1
        counters=by_category.setdefault(case['category'], {'total':0,'guard_accepted':0,'facts_kept':0,'reference_exact':0})
        counters['total']+=1
        counters['guard_accepted']+=int(allowed)
        counters['facts_kept']+=int(facts)
        counters['reference_exact']+=int(ref)
        if not (allowed and facts):
            result['failed_ids'].append({'id':case['id'],'reason':reason,
                                         'required_facts_kept':facts})
    result['guard_reasons']=dict(sorted(reasons.items()))
    result['by_category']=dict(sorted(by_category.items()))
    return result


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate-jsonl',type=Path,default=None,
                        help='local JSONL with id and candidate; no model or network run')
    parser.add_argument('--report',type=Path,default=None,
                        help='optional metadata-only output path (never includes text)')
    args=parser.parse_args(argv)
    corpus,pin=corpus_with_pin()
    candidates=({case['id']:case['reference'] for case in corpus}
                if args.candidate_jsonl is None else candidate_rows(args.candidate_jsonl, {x['id'] for x in corpus}))
    verdicts=invoke_exact_java_guard([(x['raw'],candidates[x['id']]) for x in corpus])
    report=evaluate(corpus,candidates,verdicts)
    report['corpus_sha256']=pin
    report['candidate_source']='synthetic_reference_not_model' if args.candidate_jsonl is None else 'local_external_model_output'
    encoded=json.dumps(report,ensure_ascii=False,sort_keys=True,indent=2)+'\n'
    if args.report is not None:
        if args.report.is_symlink():
            raise EvaluationError('report_path_must_not_be_symlink')
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(encoded,encoding='utf-8')
    print(encoded,end='')
    return report


if __name__ == '__main__':
    try: main()
    except EvaluationError as error:
        print('VOICE_POLISH_EVAL_ERROR:'+str(error),file=sys.stderr)
        sys.exit(1)
