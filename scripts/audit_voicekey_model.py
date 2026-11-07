#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Observe the fixed official model without publishing weights or invoking inference.

The observed SHA256 values are not a redistribution license. Review the report
before promoting an identity to the compiled application catalog. A failed
network/source observation exits nonzero and is never presented as a model test.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import stat
import urllib.parse
import urllib.request
import zipfile

URL = 'https://download.z.weixin.qq.com/publish_words/v1/ime_asr_decoder_model/20260710220526250_ime_asr_decoder_model.apk'
SIZE = 102_920_639
MD5 = '28386481eb94ff73b28ee3949c0cf690'
SOURCE_COMMIT = '3ba017826eef7fac405198510b03859eb5b6c8c4'
MAX_EXPANDED = 256 * 1024 * 1024
INNER = 'ime_asr_decoder_model.zip'

class SameOrigin(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).netloc != urllib.parse.urlsplit(URL).netloc or not newurl.startswith('https://'):
            raise ValueError('official_model_redirect_origin')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def members(archive):
    rows = []
    seen = set()
    total = 0
    for member in archive.infolist():
        name = member.filename
        if (not name or name in seen or name.startswith('/') or '\\' in name or
                any(p in ('', '.', '..') for p in name.rstrip('/').split('/')) or
                len(rows) >= 128 or stat.S_ISLNK(member.external_attr >> 16) or member.flag_bits & 1):
            raise ValueError('unsafe_or_duplicate_archive_member')
        seen.add(name)
        total += member.file_size
        if member.file_size > MAX_EXPANDED or total > MAX_EXPANDED:
            raise ValueError('oversized_archive_members')
        digest = hashlib.sha256()
        count = 0
        with archive.open(member) as stream:
            while chunk := stream.read(65536):
                count += len(chunk)
                if count > member.file_size:
                    raise ValueError('oversized_archive_member')
                digest.update(chunk)
        if count != member.file_size:
            raise ValueError('truncated_archive_member')
        rows.append({'path': name, 'bytes': count, 'sha256': digest.hexdigest(), 'directory': member.is_dir()})
    return rows

def audit(directory):
    directory.mkdir(parents=True, exist_ok=True)
    package = directory / 'official-package.apk'
    stage = directory / 'official-package.download'
    report = {'schema_version': 1, 'status': 'BLOCKED', 'source_url': URL,
              'source_repository': 'J3n5en/voicekey', 'source_commit': SOURCE_COMMIT,
              'model_id': 'wechat-offline-20260710220526250',
              'redistribution_permission': 'NOT ESTABLISHED; do not bundle weights in public artifacts',
              'scope': 'Official-source integrity metadata only; not inference, JNI, phone or model redistribution acceptance.'}
    h, m, count = hashlib.sha256(), hashlib.md5(), 0
    try:
        with urllib.request.build_opener(SameOrigin()).open(URL, timeout=45) as source, stage.open('wb') as out:
            report['http_status'] = source.status
            report['content_type'] = source.headers.get('Content-Type', '')[:128]
            while chunk := source.read(65536):
                count += len(chunk)
                if count > SIZE:
                    raise ValueError('official_package_oversized')
                h.update(chunk); m.update(chunk); out.write(chunk)
        report['observation'] = {'bytes': count, 'sha256': h.hexdigest(), 'md5': m.hexdigest()}
        if count != SIZE or m.hexdigest() != MD5:
            raise ValueError('official_package_identity_mismatch')
        stage.replace(package)
        with zipfile.ZipFile(package) as outer:
            report['outer_members'] = members(outer)
            matches = [x for x in outer.infolist() if x.filename == INNER]
            if len(matches) != 1 or matches[0].is_dir() or matches[0].file_size > MAX_EXPANDED:
                raise ValueError('official_inner_archive_missing_or_oversized')
            with outer.open(matches[0]) as incoming:
                inner_bytes = incoming.read(MAX_EXPANDED + 1)
            if len(inner_bytes) > MAX_EXPANDED:
                raise ValueError('official_inner_archive_oversized')
        with zipfile.ZipFile(io.BytesIO(inner_bytes)) as inner:
            report['inner_members'] = members(inner)
        imported = [x for x in report['inner_members'] if not x['directory'] and
                    (Path(x['path']).name == 'dict.decoder.utf8.txt' or x['path'].endswith('.xnet'))]
        names = [Path(x['path']).name for x in imported]
        if names.count('dict.decoder.utf8.txt') != 1 or not 1 <= sum(x.endswith('.xnet') for x in names) <= 8 or len(set(names)) != len(names):
            raise ValueError('official_model_members_invalid')
        report['imported_model_members'] = imported
        report['package'] = report.pop('observation')
        report['status'] = 'OBSERVED'
    except Exception as error:
        report['failure_type'] = type(error).__name__
        report['reason'] = str(error)[:160] if isinstance(error, ValueError) else 'official_source_unavailable'
    finally:
        stage.unlink(missing_ok=True)
        (directory / 'audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return report

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=Path('target/voicekey-model-audit'))
    args = parser.parse_args()
    result = audit(args.output_dir)
    print(json.dumps({'status': result['status'], 'report': str(args.output_dir / 'audit.json'),
                      'reason': result.get('reason'), 'package': result.get('package')}, ensure_ascii=False))
    raise SystemExit(0 if result['status'] == 'OBSERVED' else 2)
