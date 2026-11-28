#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Private synthetic PTY/HTTPS fixture, callable only by installer acceptance.

This never installs on developer machines. The storage adapter deliberately
replaces entered synthetic R2 settings with the job's existing MinIO backend;
the generated production configuration is checked before that substitution.
It is not evidence of a live Cloudflare R2 account or Tunnel connector.
"""
from contextlib import contextmanager
from hashlib import sha256
from http.cookiejar import CookieJar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import datetime
import hmac
import http.client
import json
import ipaddress
import os
from pathlib import Path
import re
import select
import signal
import shlex
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


def require_ci(install):
    if (sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.geteuid() != 0 or install != Path('/opt/zapime')):
        raise RuntimeError('wizard fixture requires the protected root Linux CI')


def create_bucket(bucket):
    """SigV4 setup request to the existing isolated CI MinIO, never R2."""
    now = datetime.datetime.now(datetime.timezone.utc)
    stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    payload = sha256(b'').hexdigest()
    headers = {'host': '127.0.0.1:9000', 'x-amz-content-sha256': payload, 'x-amz-date': stamp}
    names = ';'.join(sorted(headers))
    canonical = '\n'.join(('PUT', '/' + bucket, '',
                            ''.join(f'{name}:{headers[name]}\n' for name in sorted(headers)), names, payload))
    scope = day + '/us-east-1/s3/aws4_request'
    signing = b'AWS4zapime-ci-synthetic-secret'
    for item in (day, 'us-east-1', 's3', 'aws4_request'):
        signing = hmac.digest(signing, item.encode(), 'sha256')
    signature = hmac.new(signing, ('AWS4-HMAC-SHA256\n' + stamp + '\n' + scope + '\n'
                                 + sha256(canonical.encode()).hexdigest()).encode(), 'sha256').hexdigest()
    headers['Authorization'] = f'AWS4-HMAC-SHA256 Credential=zapime-ci-key/{scope}, SignedHeaders={names}, Signature={signature}'
    request = urllib.request.Request('http://127.0.0.1:9000/' + bucket, data=b'', headers=headers, method='PUT')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=10) as reply:
        assert reply.status == 200


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def browser(cert):
    context = ssl.create_default_context(cafile=str(cert))

    class LocalConnection(http.client.HTTPSConnection):
        def connect(self):
            raw = socket.create_connection(('127.0.0.1', 443), timeout=self.timeout)
            self.sock = context.wrap_socket(raw, server_hostname=self.host)

    class LocalHTTPS(urllib.request.HTTPSHandler):
        def https_open(self, request):
            return self.do_open(LocalConnection, request, context=context)

    return urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                      urllib.request.HTTPCookieProcessor(CookieJar()), LocalHTTPS(), NoRedirect())


def browser_request(opener, url, data=None, headers=None):
    request = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        reply = opener.open(request, timeout=15)
    except urllib.error.HTTPError as failure:
        reply = failure
    with reply:
        body = reply.read(128 * 1024 + 1)
        assert len(body) <= 128 * 1024
        return reply.status, reply.headers, body


def admin_login(cert, issuer, origin, expected_account):
    opener = browser(cert)
    url = origin + '/console/auth/login?return_to=admin'
    authorization = token_callback = False
    events = []
    known_paths = {'/console/auth/login': 'console_login', '/auth': 'oidc_authorize',
                   '/console/auth/callback': 'console_callback', '/console/admin/': 'admin_page'}
    for _ in range(24):
        status, headers, body = browser_request(opener, url)
        route = urllib.parse.urlsplit(url).path
        phase = 'oidc_interaction' if route.startswith('/fixture-login/') else known_paths.get(route, 'other')
        events.append((phase, status))
        if status in (302, 303):
            target = urllib.parse.urljoin(url, headers['Location'])
            parts = urllib.parse.urlsplit(target)
            assert parts.scheme == 'https' and parts.netloc in (
                urllib.parse.urlsplit(issuer).netloc, urllib.parse.urlsplit(origin).netloc)
            if target.startswith(issuer + '/auth?'):
                values = urllib.parse.parse_qs(parts.query)
                assert values['code_challenge_method'] == ['S256'] and len(values['code_challenge'][0]) == 43
                assert values['redirect_uri'] == [origin + '/console/auth/callback']
                authorization = True
            if target.startswith(origin + '/console/auth/callback?'):
                values = urllib.parse.parse_qs(parts.query)
                assert values.get('code') and values.get('state')
                token_callback = True
            url = target
            continue
        if status == 200 and url.startswith(issuer + '/fixture-login/'):
            match = re.search(rb'name="challenge" value="([A-Za-z0-9_-]+)"', body)
            assert match
            status, headers, _ = browser_request(opener, url,
                urllib.parse.urlencode({'challenge': match[1].decode()}).encode(),
                {'Content-Type': 'application/x-www-form-urlencoded'})
            events.append(('oidc_interaction_post', status))
            assert status in (302, 303), f'authorization steps {events}'
            url = urllib.parse.urljoin(url, headers['Location'])
            continue
        assert status == 200 and url == origin + '/console/admin/', f'authorization steps {events}'
        break
    else:
        raise AssertionError(f'bounded OAuth redirect chain did not finish: {events}')
    assert authorization and token_callback
    status, _, body = browser_request(opener, origin + '/console/api/session')
    session = json.loads(body)
    assert status == 200 and session['account_id'] == expected_account and session['admin'] is True
    status, _, body = browser_request(opener, origin + '/console/api/admin/accounts',
        headers={'X-Console-Account-ID': expected_account})
    accounts = json.loads(body)
    assert status == 200 and len(accounts['accounts']) == 1
    assert accounts['accounts'][0]['account_id'] == expected_account
    return opener


def run_pty(command, env, prompts, ready, timeout=600):
    """One controlling terminal; transcript stays bounded/private, never printed."""
    import pty
    import termios
    pid, descriptor = pty.fork()
    if pid == 0:
        attributes = termios.tcgetattr(0)
        attributes[3] &= ~termios.ECHO
        termios.tcsetattr(0, termios.TCSANOW, attributes)
        os.execvpe('bash', ['bash', '-c', command], env)
    deadline = time.monotonic() + timeout
    buffer = b''
    observed = set()
    exited = False
    try:
        while time.monotonic() < deadline:
            if select.select([descriptor], [], [], 0.2)[0]:
                try:
                    chunk = os.read(descriptor, 8192)
                except OSError:
                    chunk = b''
                buffer = (buffer + chunk)[-128 * 1024:]
            for index, (prompt, answer) in enumerate(prompts):
                if index not in observed and prompt.encode() in buffer:
                    observed.add(index)
                    os.write(descriptor, answer.encode() + b'\n')
            if b'Console [READY]' in buffer and 'ready' not in observed:
                observed.add('ready')
                ready()
            if b'Cloudflare Tunnel token (Enter to reuse an existing connector): ' in buffer and 'tunnel' not in observed:
                observed.add('tunnel')
                os.write(descriptor, b'\n')
            if b'Press Enter after administrator login (q to exit): ' in buffer and 'login' not in observed:
                assert 'ready' in observed
                observed.add('login')
                os.write(descriptor, b'\n')
            finished, state = os.waitpid(pid, os.WNOHANG)
            if finished:
                exited = True
                assert os.waitstatus_to_exitcode(state) == 0, 'wizard process failed; transcript withheld'
                assert b'Console [READY]' in buffer and b'Console login [PASS]' in buffer
                return observed
        raise AssertionError('bounded wizard PTY timed out; transcript withheld')
    finally:
        if not exited:
            # forkpty creates this child's own session/process group. Never
            # signal an inherited parent group or any unrelated job process.
            try:
                if os.getpgid(pid) != pid or os.getsid(pid) != pid:
                    raise RuntimeError('unexpected PTY process group')
                os.killpg(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            stop_deadline = time.monotonic() + 5
            while time.monotonic() < stop_deadline:
                finished, _ = os.waitpid(pid, os.WNOHANG)
                if finished:
                    break
                time.sleep(0.05)
            else:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                os.waitpid(pid, 0)
        os.close(descriptor)


@contextmanager
def proxy(cert, key, issuer_host, oidc_port, release_files, gateway):
    address = ipaddress.ip_address(gateway)
    private_ranges = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')
    if address.version != 4 or not any(address in ipaddress.ip_network(block) for block in private_ranges):
        raise RuntimeError('explicit Docker private IPv4 gateway required')
    upstream = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def handle_request(self):
            host = self.headers.get('Host', '')
            if host not in ('127.0.0.1', issuer_host):
                self.send_error(404)
                return
            if host == '127.0.0.1' and self.path in release_files and self.command == 'GET':
                self.send_response(200)
                self.send_header('Content-Length', str(len(release_files[self.path])))
                self.end_headers()
                self.wfile.write(release_files[self.path])
                return
            if host == issuer_host:
                target = f'http://127.0.0.1:{oidc_port}'
            elif self.path.startswith(('/console/', '/.well-known/zapime-deploy/')) or self.path == '/healthz':
                target = 'http://127.0.0.1:8787'
            else:
                self.send_error(404)
                return
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > 65536:
                self.send_error(413)
                return
            data = self.rfile.read(length) if self.command == 'POST' else None
            headers = {name: value for name, value in self.headers.items()
                       if name.lower() not in ('connection', 'transfer-encoding', 'content-length')}
            headers.update({'X-Forwarded-Proto': 'https', 'X-Forwarded-Host': host})
            request = urllib.request.Request(target + self.path, data=data, headers=headers, method=self.command)
            try:
                response = upstream.open(request, timeout=15)
            except urllib.error.HTTPError as failure:
                response = failure
            except OSError:
                self.send_error(502)
                return
            with response:
                body = response.read(256 * 1024 + 1)
                assert len(body) <= 256 * 1024
                self.send_response(response.status)
                for name, value in response.headers.items():
                    if name.lower() not in ('connection', 'transfer-encoding', 'content-length'):
                        self.send_header(name, value)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        do_GET = do_POST = handle_request

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    servers, workers = [], []
    try:
        for bind_address in ('127.0.0.1', gateway):
            server = ThreadingHTTPServer((bind_address, 443), Handler)
            try:
                server.socket = context.wrap_socket(server.socket, server_side=True)
                worker = threading.Thread(target=server.serve_forever, daemon=True)
                worker.start()
            except BaseException:
                server.server_close()
                raise
            servers.append(server)
            workers.append(worker)
        yield
    finally:
        for server in servers:
            server.shutdown()
        for worker in workers:
            worker.join(timeout=5)
        for server in servers:
            server.server_close()


@contextmanager
def private_dns(install, hostname):
    require_ci(install)
    assert re.fullmatch(r'[a-z0-9-]+\.example\.test', hostname)
    hosts = Path('/etc/hosts')
    original = hosts.read_bytes()
    for line in original.decode().splitlines():
        assert hostname not in line.split('#', 1)[0].split()[1:]
    marker = b'\n127.0.0.1 ' + hostname.encode() + b' # zapime-isolated-wizard-fixture\n'
    with hosts.open('ab') as target:
        target.write(marker)
    try:
        yield
    finally:
        current = hosts.read_bytes()
        assert current.count(marker) == 1, 'dedicated test DNS cleanup cannot be verified'
        hosts.write_bytes(current.replace(marker, b'', 1))


DOCKER_SHIM = r'''#!/usr/bin/env python3
import hashlib,json,os,pathlib,subprocess,sys
settings=json.loads(pathlib.Path(__file__).with_name('settings.json').read_text())
root=pathlib.Path('/opt/zapime')
if sys.platform!='linux' or os.environ.get('GITHUB_ACTIONS')!='true' or os.geteuid()!=0:
 raise SystemExit('isolated test Docker adapter requires root GitHub runner')
arguments=sys.argv[1:]
if arguments and arguments[0]=='compose' and '-f' in arguments:
 position=arguments.index('-f')+2
 arguments[position:position]=['-f',settings['override']]
 if 'check-storage' in arguments and not pathlib.Path(settings['marker']).exists():
  config=root/'config.env'
  if (root/'installation-v1').read_text()!='zapime-installer-v1\n' or config.stat().st_mode&0o077:
   raise SystemExit('protected empty-install configuration not found')
  raw=config.read_bytes()
  values=dict(line.split('=',1) for line in raw.decode().splitlines() if '=' in line)
  expected={'ZAPIME_HOST':'127.0.0.1','ZAPIME_S3_ENDPOINT':'https://'+'1'*32+'.r2.cloudflarestorage.com',
   'ZAPIME_S3_ACCESS_KEY_ID':'2'*32,'ZAPIME_S3_SECRET_ACCESS_KEY':'3'*64,
   'ZAPIME_S3_BUCKET':settings['bucket'],'ZAPIME_S3_REGION':'auto','ZAPIME_HTTPS_MODE':'existing'}
  if any(values.get(name)!=value for name,value in expected.items()):
   raise SystemExit('wizard did not create the expected real-shape R2 configuration')
  if len(values.get('POSTGRES_PASSWORD',''))!=64 or len(values.get('ZAPIME_INSTALLATION_ID',''))!=32:
   raise SystemExit('installer did not generate its own secrets/identity')
  replacement={'ZAPIME_S3_ENDPOINT':'http://zapime-object-test:9000','ZAPIME_S3_ACCESS_KEY_ID':'zapime-ci-key',
   'ZAPIME_S3_SECRET_ACCESS_KEY':'zapime-ci-synthetic-secret','ZAPIME_S3_REGION':'us-east-1','ZAPIME_S3_ALLOW_HTTP':'1'}
  lines=raw.decode().splitlines()
  rewritten=[line for line in lines if line.split('=',1)[0] not in replacement]
  rewritten += [name+'='+value for name,value in replacement.items()]
  with config.open('w') as target:target.write('\n'.join(rewritten)+'\n')
  pathlib.Path(settings['marker']).write_text(json.dumps({'original_r2_fields_verified':True,
   'original_config_sha256':hashlib.sha256(raw).hexdigest(),'adapter':'isolated_real_minio_not_cloudflare_r2'}))
os.execv(settings['docker'],[settings['docker'],*arguments])
'''


def _exercise(root, install, parent_work, env, command, cleanup):
    require_ci(install)
    assert not install.exists() and not Path('/usr/local/bin/zapime').exists()
    from sync_service_smoke import oidc_restore_probe
    work = parent_work / 'wizard'
    work.mkdir(mode=0o700)
    oidc = oidc_restore_probe('prepare', work, transport='loopback-https')
    issuer_host = urllib.parse.urlsplit(oidc['issuer']).hostname
    origin = 'https://127.0.0.1'
    selected = dict(oidc, origin=origin)
    selected_file = work / 'selected.json'
    selected_file.write_text(json.dumps(selected))
    oidc_folder = work / 'provider'
    oidc_folder.mkdir()
    for name in ('package.json', 'package-lock.json', 'issuer.mjs', 'install-wizard-issuer.mjs'):
        shutil.copy2(root / 'scripts/fixtures/a3-oidc' / name, oidc_folder / name)
    subprocess.run(['npm', 'ci', '--prefix', str(oidc_folder), '--ignore-scripts', '--no-audit', '--no-fund'],
                   check=True, stdout=subprocess.DEVNULL)
    cert, key, ca_bundle = [work / name for name in ('proxy-cert.pem', 'proxy-key.pem', 'test-ca-bundle.pem')]
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=ZapIME isolated wizard', '-addext',
                    f'subjectAltName=IP:127.0.0.1,DNS:{issuer_host}',
                    '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
    system_ca = ssl.get_default_verify_paths().cafile
    assert system_ca and Path(system_ca).is_file()
    ca_bundle.write_bytes(Path(system_ca).read_bytes() + b'\n' + cert.read_bytes())
    command('docker', 'network', 'create', '--label', 'com.docker.compose.project=zapime',
            '--label', 'com.docker.compose.network=default', 'zapime_default')
    command('docker', 'network', 'connect', 'zapime_default', 'zapime-object-test')
    network = json.loads(command('docker', 'network', 'inspect', 'zapime_default', capture=True))[0]
    gateways = [item['Gateway'] for item in network['IPAM']['Config'] if item.get('Gateway')]
    assert len(gateways) == 1
    gateway = gateways[0]
    bucket = 'zapime-ci-wizard-' + os.urandom(6).hex()
    create_bucket(bucket)
    override = work / 'compose-fixture.json'
    override.write_text(json.dumps({'services': {'sync': {
        'environment': {'SSL_CERT_FILE': '/run/zapime-isolated-test-ca.pem'},
        'extra_hosts': [f'{issuer_host}:{gateway}'],
        'volumes': [{'type': 'bind', 'source': str(cert), 'target': '/run/zapime-isolated-test-ca.pem',
                     'read_only': True, 'bind': {'create_host_path': False}}],
    }}}))
    shim = work / 'bin'
    shim.mkdir()
    (shim / 'settings.json').write_text(json.dumps({'docker': shutil.which('docker'), 'override': str(override),
                                                  'marker': str(work / 'storage-adapter.json'), 'bucket': bucket}))
    (shim / 'docker').write_text(DOCKER_SHIM)
    (shim / 'docker').chmod(0o700)
    test_env = dict(env, PATH=str(shim) + os.pathsep + env.get('PATH', os.defpath),
                    SSL_CERT_FILE=str(ca_bundle), CURL_CA_BUNDLE=str(ca_bundle),
                    NO_PROXY='127.0.0.1,' + issuer_host, no_proxy='127.0.0.1,' + issuer_host)
    checkout = os.environ['GITHUB_SHA']
    assert re.fullmatch(r'[0-9a-f]{40}', checkout)
    source = checkout
    # rev-list hides parents at a shallow boundary. Read the actual commit
    # header, then fetch only the public PR parent needed for the tree proof.
    commit_header = subprocess.check_output(['git', 'cat-file', '-p', checkout], cwd=root).split(b'\n\n', 1)[0]
    parents = re.findall(rb'^parent ([0-9a-f]{40})$', commit_header, re.MULTILINE)
    trees = re.findall(rb'^tree ([0-9a-f]{40})$', commit_header, re.MULTILINE)
    assert len(trees) == 1
    if len(parents) == 2:
        candidate = parents[1].decode('ascii')
        command('git', 'fetch', '--no-tags', '--depth=1', '--recurse-submodules=no', 'origin', candidate, capture=True)
        parent_header = subprocess.check_output(['git', 'cat-file', '-p', candidate], cwd=root).split(b'\n\n', 1)[0]
        parent_trees = re.findall(rb'^tree ([0-9a-f]{40})$', parent_header, re.MULTILINE)
        assert len(parent_trees) == 1
        checkout_tree, head_tree = trees[0], parent_trees[0]
        if checkout_tree == head_tree:
            source = candidate
    installer_blob = subprocess.check_output(['git', 'show', source + ':deploy/install.sh'], cwd=root)
    assert 0 < len(installer_blob) <= 256 * 1024
    bootstrap_sha = sha256(installer_blob).hexdigest()
    invocation = ['bash', '-c',
        'set -e; t=$(mktemp); trap \'rm -f "$t"\' EXIT; '
        'curl -fsSL --proto =https --proto-redir =https '
        '"https://raw.githubusercontent.com/ForceMind/ZapIME/$1/deploy/install.sh" -o "$t"; '
        'printf "%s  %s\\n" "$2" "$t" | sha256sum -c - >/dev/null; '
        'bash "$t" --wizard --ref "$1"', 'zapime-ci-wizard', source, bootstrap_sha]
    one_line = shlex.join(invocation)
    prompts = [
        ('HTTPS domain (hostname only): ', '127.0.0.1'),
        ('HTTPS mode [caddy/tunnel] (tunnel): ', ''),
        ('Cloudflare Account ID (32 hex): ', '1' * 32),
        ('R2 bucket name: ', bucket),
        ('R2 jurisdiction [default/eu/us/fedramp] (default): ', ''),
        ('R2 Access Key ID: ', '2' * 32),
        ('R2 Secret Access Key: ', '3' * 64),
        ('OIDC issuer HTTPS URL: ', oidc['issuer']),
        ('OIDC authorization HTTPS endpoint: ', oidc['issuer'] + '/auth'),
        ('OIDC token HTTPS endpoint: ', oidc['issuer'] + '/token'),
        ('OIDC resource/audience HTTPS URL: ', oidc['audience']),
        ('OIDC client ID: ', oidc['client_id']),
        ('Public JWKS HTTPS URL: ', oidc['issuer'] + '/jwks'),
        ('Verified administrator subject: ', oidc['subject']),
        ('OIDC client secret (Enter for public PKCE client): ', ''),
        ('Configure password reset adapter? [y/N]: ', ''),
    ]
    provider = subprocess.Popen(['node', str(oidc_folder / 'install-wizard-issuer.mjs'), str(work), str(selected_file)],
                                env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 20
        while not (work / 'provider-status.json').exists():
            assert provider.poll() is None and time.monotonic() < deadline, 'synthetic provider startup failed'
            time.sleep(0.05)
        provider_status = json.loads((work / 'provider-status.json').read_text())
        with private_dns(install, issuer_host), proxy(cert, key, issuer_host, provider_status['port'], {}, gateway):
            opened = []
            def ready():
                binding = json.loads((install / 'oidc/binding.json').read_text())
                opened.append(admin_login(cert, oidc['issuer'], origin, binding['account']))
            observed = run_pty(one_line, test_env, prompts, ready)
            assert all(index in observed for index in range(len(prompts)))
            assert (work / 'storage-adapter.json').is_file() and opened
            preserved = [install / name for name in ('config.env', 'oidc/binding.json', 'oidc/android.json',
                                                    'console-key.json')]
            # The actual key filenames are taken from the generated protected
            # configuration below, never guessed or copied into the transcript.
            receipt = json.loads((install / 'console-key.json').read_text())
            secret_dir = Path(receipt['source'])
            assert secret_dir == install / 'console-secrets'
            preserved += sorted(path for path in secret_dir.iterdir() if path.is_file())
            assert len(preserved) >= 5
            master = secret_dir / Path(receipt['file']).name
            assert sha256(master.read_bytes()).hexdigest() == receipt['sha256']
            original = {str(path): sha256(path.read_bytes()).hexdigest() for path in preserved}
            installed_source = command('git', '-C', str(install / 'current'), 'rev-parse', 'HEAD', capture=True).strip()
            assert installed_source == source
            def database_identity():
                return command('docker', 'compose', '--env-file', str(install / 'config.env'), '-f',
                    str(install / 'current/services/sync/compose.yml'), 'exec', '-T', 'db', 'psql', '-U', 'zapime',
                    '-Atc', 'SELECT (SELECT count(*) FROM zapime_accounts),'
                    '(SELECT count(*) FROM zapime_oidc_subjects),'
                    '(SELECT count(*) FROM zapime_console_sessions),'
                    '(SELECT count(*) FROM zapime_devices)', capture=True).strip()
            identities = database_identity()
            assert identities == '1|1|1|1'
            repeated = run_pty(one_line, test_env, [], lambda: None)
            assert 'ready' in repeated and 'login' not in repeated
            assert all(sha256(Path(path).read_bytes()).hexdigest() == digest for path, digest in original.items())
            assert database_identity() == identities
            config_names = [line.split('=', 1)[0] for line in (install / 'config.env').read_text().splitlines() if '=' in line]
            assert len(config_names) == len(set(config_names))
            status = json.loads((work / 'provider-status.json').read_text())
            assert not status['failed'] and all(status[name] >= 1 for name in ('authorization', 'login', 'consent', 'token'))
            print(f'PASS wizard source: {source}; installer SHA256 {bootstrap_sha}; checked-out source {checkout}', flush=True)
            print('PASS wizard: empty configuration, one actual raw.githubusercontent.com digest-checked source command in PTY, real OAuth S256/code/token/callback/admin HTTP, stable key/config/account on repeat', flush=True)
            print('SCOPE wizard fixture: synthetic R2 inputs verified before protected MinIO adapter; local CA and private Docker-gateway HTTPS only; no Cloudflare R2/Tunnel account tested', flush=True)
    finally:
        provider.terminate()
        try:
            provider.wait(timeout=5)
        except subprocess.TimeoutExpired:
            provider.kill()
            provider.wait(timeout=5)
        cleanup()


def exercise(root, install, parent_work, env, command, cleanup):
    require_ci(install)
    inspect = ['docker', 'network', 'inspect', 'zapime_default']
    before = subprocess.run(inspect, env=env, capture_output=True)
    if before.returncode == 0:
        raise RuntimeError('wizard requires its own absent Compose network')
    failure = None
    try:
        return _exercise(root, install, parent_work, env, command, cleanup)
    except BaseException as problem:
        failure = problem
        raise
    finally:
        try:
            # Also cover errors before the installer creates a Compose file.
            # Only this invocation can own this formerly absent job network.
            cleanup()
            current = subprocess.run(inspect, env=env, capture_output=True)
            if current.returncode == 0:
                metadata, = json.loads(current.stdout)
                assert metadata['Labels'].get('com.docker.compose.project') == 'zapime'
                assert metadata['Labels'].get('com.docker.compose.network') == 'default'
                command('docker', 'network', 'disconnect', 'zapime_default', 'zapime-object-test', check=False)
                command('docker', 'network', 'rm', 'zapime_default')
                assert subprocess.run(inspect, env=env, capture_output=True).returncode != 0
        except Exception:
            if failure is None:
                raise
            failure.add_note('The isolated wizard resource cleanup also failed; original error retained.')
