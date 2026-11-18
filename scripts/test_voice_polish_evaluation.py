# SPDX-License-Identifier: GPL-3.0-only
"""Strict local quality-corpus schema and real Java output guard validation."""
from pathlib import Path
import json
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from evaluate_voice_polish import (EvaluationError, corpus_with_pin,
                                   candidate_rows, invoke_exact_java_guard,
                                   evaluate, load_rows)


class VoicePolishEvaluationTests(unittest.TestCase):
    def test_frozen_corpus_is_exact_and_current_reference_flags_are_honest(self):
        corpus,pin=corpus_with_pin()
        self.assertEqual(len(corpus),24)
        self.assertEqual(pin,'3d02c5ac6c351aa24770572282b14b85394e15b551a791f2f7e75b7eca130248')
        self.assertEqual(len({x['id'] for x in corpus}),24)
        candidates={x['id']:x['reference'] for x in corpus}
        verdicts=invoke_exact_java_guard([(x['raw'],candidates[x['id']]) for x in corpus])
        report=evaluate(corpus,candidates,verdicts)
        self.assertEqual(report['reference_exact_match'],24)
        self.assertEqual(report['required_facts_kept'],24)
        self.assertEqual(report['guard_accepted'],21)
        self.assertEqual({x['id'] for x in report['failed_ids']},
                         {'repeat-003','repeat-004','entity-002'})
        self.assertEqual(report['guard_reasons'],
                         {'ACCEPTED':21,'NEGATION_CHANGED':2,'NUMERIC_CHANGED':1})
        encoded=json.dumps(report,ensure_ascii=False)
        for case in corpus:
            self.assertNotIn(case['raw'],encoded)
            self.assertNotIn(case['reference'],encoded)

    def test_a_corrupted_frozen_corpus_is_rejected_not_rescored(self):
        with tempfile.TemporaryDirectory(prefix='zapime-polish-pin-') as folder:
            temp=Path(folder)
            d=temp/'integrations/voice-polish'
            d.mkdir(parents=True)
            (d/'corpus.v1.jsonl').write_bytes((ROOT/'integrations/voice-polish/corpus.v1.jsonl').read_bytes()+b' ')
            (d/'corpus.v1.sha256').write_bytes((ROOT/'integrations/voice-polish/corpus.v1.sha256').read_bytes())
            with self.assertRaisesRegex(EvaluationError,'corpus_pin_mismatch'):
                corpus_with_pin(temp)

    def test_exact_id_set_is_required_no_duplicates_or_invented_cases(self):
        corpus,_=corpus_with_pin()
        values=[{'id':r['id'],'candidate':r['reference']} for r in corpus]
        cases={r['id'] for r in corpus}
        with tempfile.TemporaryDirectory(prefix='zapime-polish-output-') as folder:
            path=Path(folder)/'outputs.jsonl'
            def check(rows):
                path.write_text(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in rows),encoding='utf-8')
                return candidate_rows(path,cases)
            self.assertEqual(len(check(values)),24)
            for invalid in [values[:-1],values[:-1]+[values[0]],
                            values[:-1]+[{'id':'invented-999','candidate':'none'}],
                            [dict(values[0],extra=True)]+values[1:],
                            [dict(values[0],candidate='')]+values[1:]]:
                with self.subTest(size=len(invalid)),self.assertRaises(EvaluationError):
                    check(invalid)

    def test_changed_technical_entity_is_caught_by_frozen_required_fact_even_if_guard_accepts(self):
        corpus,_=corpus_with_pin()
        candidates={x['id']:x['reference'] for x in corpus}
        candidates['mixed-002']='明天检查USB-D接口，别把它说成Lightning。'
        result=evaluate(corpus,candidates,invoke_exact_java_guard(
            [(x['raw'],candidates[x['id']]) for x in corpus]))
        self.assertEqual(result['required_facts_kept'],23)
        flagged=[x for x in result['failed_ids'] if x['id']=='mixed-002']
        self.assertEqual(len(flagged),1)
        self.assertFalse(flagged[0]['required_facts_kept'])
        self.assertEqual(flagged[0]['reason'],'ACCEPTED')

    def test_input_rejects_bad_json_encoding_and_oversized_file(self):
        with tempfile.TemporaryDirectory(prefix='zapime-polish-invalid-') as folder:
            p=Path(folder)/'x.jsonl'
            for payload in (b'\xff',b'{"id":"a","id":"b"}\n',
                            b'[]\n',b' '*(256*1024+1)):
                p.write_bytes(payload)
                with self.subTest(size=len(payload)),self.assertRaises(EvaluationError):
                    load_rows(p)


if __name__=='__main__':
    unittest.main()
