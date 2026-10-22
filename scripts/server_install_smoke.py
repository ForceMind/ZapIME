#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Destructive only to this job's ephemeral synthetic /opt/zapime and Compose project.
No Cloudflare credentials, real domain, user host, or production storage are used.
"""
import json
import os
from pathlib import Path
import shutil
import secrets
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
INSTALL = Path('/opt/zapime')


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.geteuid() != 0:
        raise SystemExit('Dedicated root CI runner required; refuses local installs/data deletion')
    sha = os.environ.get('GITHUB_SHA', '')
    if len(sha) != 40 or any(c not in '0123456789abcdef' for c in sha):
        raise SystemExit('Exact CI commit required')
    if INSTALL.exists() or Path('/usr/local/bin/zapime').exists():
        raise SystemExit('Installer acceptance requires a fresh dedicated runner')
    env = dict(os.environ, COMPOSE_PROJECT_NAME='zapime')
    def command(*args, capture=False, check=True):
        result = subprocess.run(args, cwd=ROOT, env=env, check=check,
                                stdout=subprocess.PIPE if capture else None, text=True)
        return result.stdout if capture else result
    def compose(*args):
        return command('docker', 'compose', '--env-file', str(INSTALL/'config.env'),
                       '-f', str(INSTALL/'current/services/sync/compose.yml'), *args)
    def setup(bucket):
        INSTALL.mkdir(mode=0o700)
        (INSTALL/'installation-v1').write_text('zapime-installer-v1\n')
        config = ('ZAPIME_INSTALLATION_ID='+secrets.token_hex(16)+'\n'
                  'POSTGRES_PASSWORD=zapime-ci-only-password\n'
                  'ZAPIME_HOST=zapime-ci.invalid\nZAPIME_HTTPS_MODE=existing\n'
                  'ZAPIME_OBJECT_BACKEND=s3\nZAPIME_S3_ENDPOINT=http://zapime-object-test:9000\n'
                  f'ZAPIME_S3_BUCKET={bucket}\nZAPIME_S3_ACCESS_KEY_ID=zapime-ci-key\n'
                  'ZAPIME_S3_SECRET_ACCESS_KEY=zapime-ci-synthetic-secret\n'
                  'ZAPIME_S3_REGION=us-east-1\nZAPIME_S3_ALLOW_HTTP=1\n')
        (INSTALL/'config.env').write_text(config)
        (INSTALL/'config.env').chmod(0o600)
        # Connect the test object server to the installer's private project network.
        command('docker', 'network', 'create', '--label', 'com.docker.compose.project=zapime',
                '--label', 'com.docker.compose.network=default', 'zapime_default')
        command('docker', 'network', 'connect', 'zapime_default', 'zapime-object-test')
    def install(restore=None):
        args=['bash',str(ROOT/'deploy/install.sh'),'--ref',sha,'--existing-proxy']
        if restore is not None: args += ['--restore',str(restore)]
        command(*args)
    def api(method, path, token, body=None):
        request=urllib.request.Request('http://127.0.0.1:8787'+path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method=method)
        with urllib.request.urlopen(request,timeout=15) as response:
            return json.load(response)
    def cleanup():
        command('systemctl','disable','--now','zapime-backup.timer',check=False)
        command('systemctl','stop','zapime-backup.service',check=False)
        for name in ('zapime-backup.timer','zapime-backup.service'):
            Path('/etc/systemd/system',name).unlink(missing_ok=True)
        command('systemctl','daemon-reload',check=False)
        if (INSTALL/'current').exists():
            # Disconnect the separate test object server before removing this network.
            command('docker','network','disconnect','zapime_default','zapime-object-test',check=False)
            compose('down','--volumes')
        if INSTALL.exists():shutil.rmtree(INSTALL)
        Path('/usr/local/bin/zapime').unlink(missing_ok=True)
    try:
        setup('zapime-ci-install')
        install()
        owner=json.loads((INSTALL/'owner.json').read_text())
        assert (INSTALL/'owner.json').stat().st_mode & 0o077 == 0
        assert (INSTALL/'config.env').stat().st_mode & 0o077 == 0
        command('/usr/local/bin/zapime','status')
        command('/usr/local/bin/zapime','check-storage')
        with tempfile.TemporaryDirectory(prefix='zapime-installer-recovery-') as directory:
            directory=Path(directory)
            fixture,packet,change_file=[directory/name for name in ('private.json','ciphertext.json','change.json')]
            probe=ROOT/'target/debug/examples/sync_fixture'
            command(str(probe),'seal',owner['account_id'],str(fixture),str(packet))
            operation=json.loads(packet.read_text())
            first=api('POST','/v1/changes',owner['token'],operation)
            assert first['revision']==1
            assert api('GET','/v1/changes',owner['token'])['changes']==[first]
            change_file.write_text(json.dumps(first))
            command(str(probe),'verify',str(fixture),str(change_file))
            install()  # same command: preserves credentials, records and configuration
            assert json.loads((INSTALL/'owner.json').read_text())==owner
            assert api('GET','/v1/changes',owner['token'])['changes']==[first]
            backup=directory/'server.dump'
            command('/usr/local/bin/zapime','backup',str(backup))
            assert backup.is_file() and Path(str(backup)+'.objects.tar').is_file()
            cleanup()  # Destroy the first server's synthetic DB/config; retain client keys and backup.
            setup('zapime-ci-install-restored')  # A DIFFERENT empty object bucket.
            install(backup)
            assert not (INSTALL/'owner.json').exists()  # no spurious new account on restore
            restored=api('GET','/v1/changes',owner['token'])
            assert restored['changes']==[first]
            change_file.write_text(json.dumps(restored['changes'][0]))
            command(str(probe),'verify',str(fixture),str(change_file))
            command('/usr/local/bin/zapime','verify-objects')
        print('PASS installer: one entry command, repeat install preserves data, private credentials, fresh DB + alternate bucket restore, real client AEAD recovery; HTTPS/Cloudflare live credentials not tested',flush=True)
    finally:
        cleanup()


if __name__=='__main__':main()
