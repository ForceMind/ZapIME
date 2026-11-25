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
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

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
    def setup(bucket, host='zapime-ci.invalid'):
        INSTALL.mkdir(mode=0o700)
        (INSTALL/'installation-v1').write_text('zapime-installer-v1\n')
        config = ('ZAPIME_INSTALLATION_ID='+secrets.token_hex(16)+'\n'
                  'POSTGRES_PASSWORD=zapime-ci-only-password\n'
                  f'ZAPIME_HOST={host}\nZAPIME_HTTPS_MODE=existing\n'
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
    def rejected_api(method, path, token, body, status, error):
        try:
            api(method, path, token, body)
        except urllib.error.HTTPError as failure:
            assert failure.code == status and json.load(failure) == {'error': error}
        else:
            raise AssertionError('Expected explicit descriptor rejection')
    def cleanup():
        command('systemctl','disable','--now','zapime-backup.timer',check=False)
        command('systemctl','stop','zapime-backup.service',check=False)
        for name in ('zapime-backup.timer','zapime-backup.service'):
            Path('/etc/systemd/system',name).unlink(missing_ok=True)
        command('systemctl','daemon-reload',check=False)
        active = INSTALL/'current/services/sync/compose.yml'
        if not active.is_file():
            candidates = sorted(INSTALL.glob('releases/*/services/sync/compose.yml'))
            active = candidates[-1] if candidates else active
        if active.is_file():
            # Disconnect the separate test object server before removing this network.
            command('docker','network','disconnect','zapime_default','zapime-object-test',check=False)
            command('docker', 'compose', '--env-file', str(INSTALL/'config.env'), '-f', str(active),
                    '--profile', 'https', 'down', '--volumes')
        if INSTALL.exists():shutil.rmtree(INSTALL)
        Path('/usr/local/bin/zapime').unlink(missing_ok=True)
    def signed_runtime_test():
        from sync_service_smoke import oidc_restore_probe

        with tempfile.TemporaryDirectory(prefix='zapime-signed-install-') as path:
            work = Path(path)
            oidc = oidc_restore_probe('prepare', work)
            host = urllib.parse.urlsplit(oidc['audience']).hostname
            setup('zapime-ci-signed-runtime', host)
            config = work / 'android.json'
            subject = work / 'subject.txt'
            config.write_text(json.dumps({
                'issuer': oidc['issuer'], 'authorization': oidc['issuer'] + '/auth', 'token': oidc['issuer'] + '/token',
                'resource': oidc['audience'], 'client_id': oidc['client_id'], 'sync_server': 'https://' + host,
                'callback': 'https://' + host + '/callback',
            }))
            subject.write_text(oidc['subject'])
            key = work / 'release-private.pem'
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(key)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            images = {'db': 'zapime-release/db:ci', 'sync': 'zapime-release/sync:ci', 'https': 'zapime-release/https:ci'}
            command('docker', 'build', '-t', images['sync'], str(ROOT / 'services/sync'))
            command('docker', 'pull', 'caddy:2')
            command('docker', 'tag', 'caddy:2', images['https'])
            command('docker', 'tag', 'postgres:16', images['db'])
            archive = work / 'images.tar'
            command('docker', 'save', '-o', str(archive), *images.values())
            bundle = work / 'runtime'
            version = '0.1.0-runtime.1'
            image_args = [argument for name, reference in images.items() for argument in ('--' + name, reference)]
            pin = command(sys.executable, str(ROOT / 'deploy/build_runtime_bundle.py'), '--version', version,
                          '--architecture', 'amd64', '--output', str(bundle), '--private-key', str(key),
                          '--archive', str(archive), *image_args, capture=True).strip()
            # Drop the release aliases to prove the offline archive supplied them.
            for image in images.values():
                command('docker', 'image', 'rm', image)
            args = ['bash', str(ROOT / 'deploy/bootstrap.sh'), '--install', '--bundle', str(bundle),
                    '--version', version, '--key-sha256', pin, '--', '--existing-proxy', '--image-archive', str(archive),
                    '--oidc-config', str(config), '--oidc-jwks', str(work / 'jwks.json'), '--oidc-subject-file', str(subject),
                    '--android-signer', 'ab' * 32]
            command(*args)
            current = INSTALL / 'current'
            assert (current / 'manifest.json').is_file() and not (INSTALL / 'owner.json').exists()
            binding_path = INSTALL / 'oidc/binding.json'
            original = binding_path.read_bytes()
            account = json.loads(original)['account']
            assert oidc_restore_probe('automatic', work, {'account_id': account})['automatic_member']
            assert json.loads((INSTALL / 'oidc/android.json').read_text()) == json.loads(config.read_text())
            assets = json.loads((INSTALL / 'oidc/assetlinks.json').read_text())
            assert assets[0]['target']['sha256_cert_fingerprints'] == [':'.join(['AB'] * 32)]
            assert (binding_path.stat().st_mode & 0o077) == 0
            command(*args)
            assert binding_path.read_bytes() == original
            assert oidc_restore_probe('automatic', work, {'account_id': account, 'save_credential': True})['automatic_member']
            report = command('docker', 'compose', '--env-file', str(INSTALL/'config.env'), '-f',
                             str(current/'services/sync/compose.yml'), 'exec', '-T', 'db',
                             'psql', '-U', 'zapime', '-Atc', 'SELECT count(*) FROM zapime_accounts', capture=True)
            assert report.strip() == '1'
            print('PASS signed runtime: offline verified images, real S3, repeat OIDC bootstrap, container public JWKS, automatic enrollment and Android configuration', flush=True)
            device = json.loads((work / 'automatic-device.json').read_text())
            fixture, packet, identity, page = [work / name for name in ('e2ee.json', 'packet.json', 'key.json', 'page.json')]
            probe = ROOT / 'target/debug/examples/sync_fixture'
            command(str(probe), 'seal', account, str(fixture), str(packet))
            command(str(probe), 'descriptor', account, str(fixture), str(identity))
            api('PUT', '/v1/key-descriptor/initialize', device['token'], json.loads(identity.read_text()))
            first = api('POST', '/v1/changes', device['token'], json.loads(packet.read_text()))
            assert first['revision'] == 1
            later = work / 'later-version'
            later_version = '0.1.0-runtime.2'
            later_pin = command(sys.executable, str(ROOT / 'deploy/build_runtime_bundle.py'), '--version', later_version,
                                '--architecture', 'amd64', '--output', str(later), '--private-key', str(key),
                                '--archive', str(archive), *image_args, capture=True).strip()
            assert pin == later_pin
            original_release = current.resolve()
            public = INSTALL / 'public-https.verified'
            public.touch()
            update = ['/usr/local/bin/zapime', 'update', str(later), later_version, str(archive)]
            assert command(*update, check=False).returncode != 0
            pending = INSTALL / 'upgrade.pending'
            frozen = INSTALL / 'deployment-state/frozen'
            assert current.resolve() == original_release and pending.is_file() and frozen.is_file()
            before = pending.read_text().splitlines()
            assert before[3] == 'backup_complete' and Path(before[2]).is_file() and Path(before[2] + '.objects.tar').is_file()
            rejected_api('POST', '/v1/changes', device['token'], json.loads(packet.read_text()), 503, 'maintenance')
            assert 'Upgrade pending' in command('/usr/local/bin/zapime', 'status', capture=True)
            assert command('/usr/local/bin/zapime', 'start', check=False).returncode != 0
            public.unlink()  # This mock existing proxy was never actually HTTPS-enabled.
            command(*update)
            assert current.resolve() != original_release and not pending.exists() and not frozen.exists()
            assert (INSTALL / 'upgrade.last').read_text().splitlines()[2] == before[2]
            restored = api('GET', '/v1/changes?after=0', device['token'])
            assert restored['changes'] == [first]
            page.write_text(json.dumps(first))
            command(str(probe), 'verify', str(fixture), str(page))
            assert oidc_restore_probe('automatic', work, {'account_id': account})['automatic_member']
            print('PASS signed upgrade: isolated migration, stopped writer backup, failed public check freezes data, same-bundle resume, original ciphertext and OIDC', flush=True)
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
            fixture,packet,change_file,descriptor_file=[directory/name for name in ('private.json','ciphertext.json','change.json','public-key-descriptor.json')]
            probe=ROOT/'target/debug/examples/sync_fixture'
            command(str(probe),'seal',owner['account_id'],str(fixture),str(packet))
            command(str(probe),'descriptor',owner['account_id'],str(fixture),str(descriptor_file))
            operation=json.loads(packet.read_text())
            descriptor=json.loads(descriptor_file.read_text())
            assert descriptor['key_id']==operation['key_id']
            assert api('GET','/v1/key-descriptor',owner['token'])['state']=='empty'
            registered=api('PUT','/v1/key-descriptor',owner['token'],descriptor)
            assert registered['account_id']==owner['account_id'] and registered['state']=='registered' and registered['descriptor']==descriptor
            assert registered['registered_by']==owner['device_id']
            assert api('PUT','/v1/key-descriptor',owner['token'],descriptor)==registered
            changed_descriptor=dict(descriptor,key_id=str(uuid.uuid4()))
            rejected_api('PUT','/v1/key-descriptor',owner['token'],changed_descriptor,409,'key_descriptor_conflict')
            first=api('POST','/v1/changes',owner['token'],operation)
            assert first['revision']==1
            assert api('GET','/v1/changes',owner['token'])['changes']==[first]
            change_file.write_text(json.dumps(first))
            command(str(probe),'verify',str(fixture),str(change_file))
            install()  # same command: preserves credentials, records and configuration
            assert json.loads((INSTALL/'owner.json').read_text())==owner
            assert api('GET','/v1/changes',owner['token'])['changes']==[first]
            assert api('GET','/v1/key-descriptor',owner['token'])==registered
            backup=directory/'server.dump'
            command('/usr/local/bin/zapime','backup',str(backup))
            assert backup.is_file() and Path(str(backup)+'.objects.tar').is_file()
            cleanup()  # Destroy the first server's synthetic DB/config; retain client keys and backup.
            setup('zapime-ci-install-restored')  # A DIFFERENT empty object bucket.
            install(backup)
            assert not (INSTALL/'owner.json').exists()  # no spurious new account on restore
            restored=api('GET','/v1/changes',owner['token'])
            assert restored['changes']==[first]
            assert api('GET','/v1/key-descriptor',owner['token'])==registered
            assert api('PUT','/v1/key-descriptor',owner['token'],descriptor)==registered
            rejected_api('PUT','/v1/key-descriptor',owner['token'],changed_descriptor,409,'key_descriptor_conflict')
            changed_operation=dict(operation,operation_id=str(uuid.uuid4()),record_id=str(uuid.uuid4()),key_id=str(uuid.uuid4()))
            rejected_api('POST','/v1/changes',owner['token'],changed_operation,409,'key_descriptor_mismatch')
            assert api('GET','/v1/changes',owner['token'])['head']==1
            change_file.write_text(json.dumps(restored['changes'][0]))
            command(str(probe),'verify',str(fixture),str(change_file))
            command('/usr/local/bin/zapime','verify-objects')
        print('PASS installer: one entry command, repeat install preserves data/key descriptor, private credentials, fresh DB + alternate bucket descriptor restore, real client AEAD recovery; HTTPS/Cloudflare live credentials not tested',flush=True)
        cleanup()
        signed_runtime_test()
    finally:
        cleanup()


if __name__=='__main__':main()
