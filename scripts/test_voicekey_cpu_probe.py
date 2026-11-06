#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Model/fixture/report boundaries only; never asserts synthetic model decoding."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import voicekey_cpu_probe as probe
from audit_voicekey_model import URL, SOURCE_COMMIT, INNER


def identity(data):
    return {'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}


class ProbeInputs(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.lockfile=self.root/'lock.json'
        self.model=self.root/'model';self.model.mkdir()
        self.files={'dict.decoder.utf8.txt':b'dictionary fixture validator only', 'one.xnet':b'not a model, validator fixture only'}
        self.lock={'schema_version':1,'status':'PINNED','model_id':'test-validator-only',
            'source':{'url':URL,'repository':'J3n5en/voicekey','commit':SOURCE_COMMIT},
            'package':identity(b'package'),'inner_archive':{'path':INNER,**identity(b'inner')},
            'outer_members':[{'path':INNER,**identity(b'inner'),'directory':False}],
            'files':{name:{'output':name,**identity(data)} for name,data in self.files.items()}}
        for name,data in self.files.items():(self.model/name).write_bytes(data)
        self.write_lock()
        self.pcm=self.root/'synthetic.pcm';self.pcm.write_bytes(b'\x01\0\0\0')
        self.meta=self.root/'fixture.json'
        probe.save(self.meta,{'schema_version':1,'kind':'synthetic','format':'pcm16le-16000-mono','reference':'测试','bytes':4,'samples':2,'sha256':probe.digest(self.pcm)})

    def write_lock(self):probe.save(self.lockfile,self.lock)
    def outputs(self):return probe.trusted_lock(self.lockfile)[1]

    def test_reviewed_lock_and_exact_files(self):
        probe.verify_model(self.model,self.outputs())
        self.assertEqual(probe.verify_pcm(self.pcm,self.meta)['samples'],2)

    def test_missing_pins_block_before_decode(self):
        self.lock['status']='BLOCKED';self.write_lock()
        with self.assertRaises(probe.Blocked):self.outputs()
        self.lock['status']='PINNED';self.lock['package']['sha256']=None;self.write_lock()
        with self.assertRaises(probe.Blocked):self.outputs()

    def test_wrong_origin_rejected(self):
        self.lock['source']['url']='https://example.com/model';self.write_lock()
        with self.assertRaisesRegex(ValueError,'origin'):self.outputs()

    def test_duplicate_outer_rejected(self):
        self.lock['outer_members']*=2;self.write_lock()
        with self.assertRaises(ValueError):self.outputs()

    def test_inner_identity_must_match_outer(self):
        self.lock['inner_archive']['sha256']='f'*64;self.write_lock()
        with self.assertRaisesRegex(ValueError,'inconsistent'):self.outputs()

    def test_member_path_traversal_rejected(self):
        for name in ['../one.xnet','/one.xnet','one/../one.xnet','one\\two','one//two']:
            with self.subTest(name=name),self.assertRaises(ValueError):probe.member_path(name)

    def test_duplicate_output_rejected(self):
        self.lock['files']['other']={'output':'one.xnet',**identity(b'x')};self.write_lock()
        with self.assertRaisesRegex(ValueError,'output'):self.outputs()

    def test_output_path_rejected(self):
        self.lock['files']['one.xnet']['output']='../one.xnet';self.write_lock()
        with self.assertRaises(ValueError):self.outputs()

    def test_oversized_model_rejected(self):
        self.lock['files']['one.xnet']['size']=probe.MAX_MODEL+1;self.write_lock()
        with self.assertRaises(ValueError):self.outputs()

    def test_ambiguous_xnet_rejected(self):
        self.lock['files']['two.xnet']={'output':'two.xnet',**identity(b'x')};self.write_lock()
        with self.assertRaisesRegex(ValueError,'ambiguous'):self.outputs()

    def test_tampering_rejected(self):
        (self.model/'one.xnet').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'integrity'):probe.verify_model(self.model,self.outputs())

    def test_unknown_file_rejected(self):
        (self.model/'metadata').write_bytes(b'x')
        with self.assertRaisesRegex(ValueError,'unexpected'):probe.verify_model(self.model,self.outputs())

    def test_symlink_rejected(self):
        p=self.model/'one.xnet';p.unlink();p.symlink_to(self.pcm)
        with self.assertRaisesRegex(ValueError,'integrity'):probe.verify_model(self.model,self.outputs())

    def test_no_model_is_blocked(self):
        with self.assertRaises(probe.Blocked):probe.verify_model(self.root/'absent',self.outputs())

    def test_pcm_bytes_bound_format_and_hash(self):
        for raw in [b'',b'\1',b'wrong pcm even']:
            with self.subTest(raw=raw),self.assertRaises(ValueError):
                self.pcm.write_bytes(raw);probe.verify_pcm(self.pcm,self.meta)
        self.pcm.write_bytes(b'\x01\0\0\0');m=probe.load_json(self.meta);m['kind']='natural';probe.save(self.meta,m)
        with self.assertRaisesRegex(ValueError,'synthetic'):probe.verify_pcm(self.pcm,self.meta)

    def test_pcm_symlink_rejected(self):
        alias=self.root/'alias.pcm';alias.symlink_to(self.pcm)
        with self.assertRaises(ValueError):probe.verify_pcm(alias,self.meta)

    def test_percentile_and_invalid_samples(self):
        self.assertEqual(probe.percentile([30.,10.,20.],.5),20.)
        self.assertEqual(probe.percentile([30.,10.,20.],.95),30.)
        for values in [[],[-1],[float('nan')],[float('inf')]]:
            with self.subTest(values=values),self.assertRaises(ValueError):probe.percentile(values,.5)

    def test_input_failure_does_not_claim_inference(self):
        from types import SimpleNamespace
        args=SimpleNamespace(model_lock=self.lockfile,model=self.root/'missing',native_started=True)
        with self.assertRaises(probe.Blocked):probe.run(args)
        self.assertFalse(args.native_started)

    def test_save_replaces_stale_pass(self):
        destination=self.root/'report.json'
        probe.save(destination,{'status':'PASS'})
        probe.save(destination,{'status':'BLOCKED','model_decode_executed':False})
        self.assertEqual(json.loads(destination.read_text())['status'],'BLOCKED')


if __name__=='__main__':unittest.main()
