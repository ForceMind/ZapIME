// SPDX-License-Identifier: GPL-3.0-only
// Host-only OAuth/OIDC proof using a disposable issuer and synthetic credentials.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, generateKeyPairSync, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, rmdirSync, writeFileSync } from 'node:fs';
import { createServer, request } from 'node:https';
import { once } from 'node:events';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { importJWK, jwtVerify } from 'jose';
import { Provider } from 'oidc-provider';

const privateDirectory = mkdtempSync(join(dirname(fileURLToPath(import.meta.url)), 'tls-'));
const clientId = 'zapime-android-synthetic';
const fixtureHost = 'a3-oidc.example.test';
const subject = 'a3-synthetic-user';
const password = randomBytes(24).toString('base64url');
const scope = 'zapime.device_enrollment';
const deadline = AbortSignal.timeout(35_000);
let server;
let stage = 'certificates';

function client(ca) {
  const cookies = new Map();
  return (url, body) => new Promise((resolve, reject) => {
    const current = new URL(url);
    assert.equal(current.hostname, fixtureHost);
    const cookie = [...cookies.values()]
      .filter(({ path }) => current.pathname.startsWith(path))
      .map(({ value }) => value).join('; ');
    const encoded = body === undefined ? undefined : new URLSearchParams(body).toString();
    const outbound = request(current, {
      ca, family: 4, lookup: (_hostname, _options, done) => done(null, '127.0.0.1', 4),
      method: encoded === undefined ? 'GET' : 'POST',
      signal: deadline,
      headers: {
        ...(cookie && { Cookie: cookie }),
        ...(encoded !== undefined && {
          'Content-Type': 'application/x-www-form-urlencoded',
          'Content-Length': Buffer.byteLength(encoded),
        }),
      },
      timeout: 4_000,
    }, (response) => {
      for (const header of response.headers['set-cookie'] ?? []) {
        const [value, ...attributes] = header.split(/;\s*/);
        const name = value.split('=')[0];
        const path = attributes.find((entry) => entry.startsWith('path=') || entry.startsWith('Path='))?.slice(5) ?? '/';
        const key = `${name}:${path}`;
        if (value === `${name}=`) cookies.delete(key);
        else cookies.set(key, { path, value });
      }
      const chunks = [];
      response.on('data', (part) => chunks.push(part));
      response.on('end', () => resolve({ status: response.statusCode, redirect: response.headers.location,
        body: Buffer.concat(chunks).toString('utf8') }));
      response.on('error', reject);
    });
    outbound.on('timeout', () => outbound.destroy(new Error('fixture_timeout')));
    outbound.on('error', reject);
    outbound.end(encoded);
  });
}

async function fields(req) {
  let size = 0;
  const chunks = [];
  for await (const part of req) {
    size += part.length;
    if (size > 1_024) throw new Error('fixture_request_too_large');
    chunks.push(part);
  }
  return new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
}

async function interact(provider, req, res) {
  const { prompt, params, session } = await provider.interactionDetails(req, res);
  if (req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ prompt: prompt.name }));
    return;
  }
  assert.equal(req.method, 'POST');
  const form = await fields(req);
  if (prompt.name === 'login') {
    const supplied = Buffer.from(form.get('password') ?? '', 'utf8');
    const expected = Buffer.from(password, 'utf8');
    if (form.get('username') !== subject || supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      res.writeHead(403).end();
      return;
    }
    await provider.interactionFinished(req, res, { login: { accountId: subject } }, { mergeWithLastSubmission: false });
    return;
  }
  assert.equal(prompt.name, 'consent');
  assert.equal(session.accountId, subject);
  assert.equal(form.get('confirm'), 'yes');
  const grant = new provider.Grant({ accountId: session.accountId, clientId: params.client_id });
  if (prompt.details.missingOIDCScope) grant.addOIDCScope(prompt.details.missingOIDCScope.join(' '));
  for (const [resource, scopes] of Object.entries(prompt.details.missingResourceScopes ?? {})) {
    grant.addResourceScope(resource, scopes.join(' '));
  }
  await provider.interactionFinished(req, res, { consent: { grantId: await grant.save() } }, { mergeWithLastSubmission: true });
}

function verifyToken(key, jwt, issuer, audience) {
  return jwtVerify(jwt, key, { issuer, audience, typ: 'at+jwt', algorithms: ['RS256'],
    requiredClaims: ['exp', 'iat', 'sub', 'client_id', 'jti', 'scope'] });
}

try {
  const config = join(privateDirectory, 'openssl.cnf');
  const keyPath = join(privateDirectory, 'key.pem');
  const certPath = join(privateDirectory, 'cert.pem');
  writeFileSync(config, `[req]\nprompt = no\ndistinguished_name = dn\nx509_extensions = ext\n[dn]\nCN = ZapIME OIDC fixture\n[ext]\nsubjectAltName = DNS:${fixtureHost}\nbasicConstraints = critical,CA:TRUE\nkeyUsage = critical,keyCertSign,digitalSignature\nextendedKeyUsage = serverAuth\n`);
  execFileSync(process.argv[2] ?? 'openssl', ['req', '-new', '-x509', '-newkey', 'rsa:2048', '-nodes',
    '-days', '1', '-config', config, '-keyout', keyPath, '-out', certPath], { stdio: 'ignore', timeout: 10_000 });
  const ca = readFileSync(certPath);
  let provider;
  server = createServer({ cert: ca, key: readFileSync(keyPath), minVersion: 'TLSv1.2' }, (req, res) => {
    if (req.url?.startsWith('/fixture-login/')) {
      interact(provider, req, res).catch(() => { if (!res.headersSent) res.writeHead(500); res.end(); });
    } else {
      provider.callback()(req, res);
    }
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const issuer = `https://${fixtureHost}:${server.address().port}`;
  const redirectUri = issuer + '/native-callback';
  const targetResource = issuer + '/a3-device-enrollment';
  const otherResource = issuer + '/other-audience';
  const jwtKey = generateKeyPairSync('rsa', { modulusLength: 2048 });
  stage = 'provider_configuration';
  provider = new Provider(issuer, {
    clients: [{ client_id: clientId, application_type: 'native', grant_types: ['authorization_code'],
      response_types: ['code'], redirect_uris: [redirectUri], token_endpoint_auth_method: 'none' }],
    jwks: { keys: [{ ...jwtKey.privateKey.export({ format: 'jwk' }), kid: 'a3-fixture', alg: 'RS256', use: 'sig' }] },
    cookies: { keys: [randomBytes(32).toString('base64url'), randomBytes(32).toString('base64url')] },
    interactions: { url: (_ctx, interaction) => `/fixture-login/${interaction.uid}` },
    async renderError(ctx) {
      ctx.body = 'synthetic OIDC request rejected';
    },
    ttl: { AccessToken: 60, AuthorizationCode: 90, Grant: 120, IdToken: 60, Interaction: 90, Session: 120 },
    features: {
      devInteractions: { enabled: false },
      resourceIndicators: { enabled: true, async getResourceServerInfo(_ctx, resource) {
        assert.ok([targetResource, otherResource].includes(resource));
        return { scope, audience: resource, accessTokenTTL: 60, accessTokenFormat: 'jwt', jwt: { sign: { alg: 'RS256' } } };
      } },
    },
    pkce: { required: () => true },
    async findAccount(_ctx, accountId) {
      if (accountId === subject) return { accountId, async claims() { return { sub: accountId }; } };
      return undefined;
    },
  });
  stage = 'scoped_tls';
  await assert.rejects(client(undefined)(issuer + '/.well-known/openid-configuration'), { code: 'DEPTH_ZERO_SELF_SIGNED_CERT' });
  const metadataResponse = await client(ca)(issuer + '/.well-known/openid-configuration');
  assert.equal(metadataResponse.status, 200);
  const metadata = JSON.parse(metadataResponse.body);
  assert.equal(metadata.issuer, issuer);
  assert.ok(metadata.code_challenge_methods_supported.includes('S256'));
  for (const url of [metadata.authorization_endpoint, metadata.token_endpoint, metadata.jwks_uri]) {
    assert.ok(new URL(url).origin === issuer);
  }

  async function authorizationCode(resource, rejectPassword) {
    const visit = client(ca);
    const state = randomBytes(24).toString('base64url');
    const nonce = randomBytes(24).toString('base64url');
    const verifier = randomBytes(32).toString('base64url');
    const start = new URL(metadata.authorization_endpoint);
    start.search = new URLSearchParams({ client_id: clientId, redirect_uri: redirectUri, response_type: 'code',
      scope: `openid ${scope}`, state, nonce, resource, code_challenge_method: 'S256',
      code_challenge: createHash('sha256').update(verifier).digest('base64url') });
    let current = start;
    for (let step = 0; step < 12; step++) {
      assert.equal(current.origin, issuer);
      if (`${current.origin}${current.pathname}` === redirectUri) {
        assert.equal(current.searchParams.get('state'), state);
        assert.ok(!current.searchParams.has('error') && current.searchParams.has('code'));
        return { code: current.searchParams.get('code'), verifier, nonce, resource };
      }
      let response = await visit(current);
      if (response.status === 200 && current.pathname.startsWith('/fixture-login/')) {
        const { prompt } = JSON.parse(response.body);
        if (prompt === 'login') {
          if (rejectPassword) {
            const denied = await visit(current, { username: subject, password: 'synthetic-incorrect-input' });
            assert.equal(denied.status, 403);
          }
          response = await visit(current, { username: subject, password });
        } else {
          assert.equal(prompt, 'consent');
          response = await visit(current, { confirm: 'yes' });
        }
      }
      assert.ok(response.status === 302 || response.status === 303,
        'protocol response must redirect');
      assert.ok(response.redirect);
      current = new URL(response.redirect, current);
    }
    throw new Error('callback_not_reached');
  }

  function exchange(flow, verifier = flow.verifier) {
    return client(ca)(metadata.token_endpoint, { client_id: clientId, redirect_uri: redirectUri,
      grant_type: 'authorization_code', code: flow.code, code_verifier: verifier, resource: flow.resource });
  }

  stage = 'wrong_pkce';
  const rejectedFlow = await authorizationCode(targetResource, true);
  assert.equal((await exchange(rejectedFlow, randomBytes(32).toString('base64url'))).status, 400);
  stage = 'access_and_id_tokens';
  const flow = await authorizationCode(targetResource, false);
  const exchanged = await exchange(flow);
  assert.equal(exchanged.status, 200);
  const tokens = JSON.parse(exchanged.body);
  const publicKeys = JSON.parse((await client(ca)(metadata.jwks_uri)).body).keys;
  assert.ok(publicKeys.length === 1 && !('d' in publicKeys[0]));
  const publicKey = await importJWK(publicKeys[0], 'RS256');
  const validated = await verifyToken(publicKey, tokens.access_token, issuer, targetResource);
  assert.equal(validated.payload.sub, subject);
  assert.equal(validated.payload.client_id, clientId);
  assert.ok(validated.payload.scope.split(' ').includes(scope));
  const idResult = await jwtVerify(tokens.id_token, publicKey, { issuer, audience: clientId, algorithms: ['RS256'] });
  assert.equal(idResult.payload.nonce, flow.nonce);
  assert.equal(idResult.payload.sub, subject);
  await assert.rejects(verifyToken(publicKey, tokens.id_token, issuer, targetResource));
  assert.equal((await exchange(flow)).status, 400);

  stage = 'wrong_audience';
  const otherExchange = await exchange(await authorizationCode(otherResource, false));
  assert.equal(otherExchange.status, 200);
  const otherToken = JSON.parse(otherExchange.body).access_token;
  await verifyToken(publicKey, otherToken, issuer, otherResource);
  await assert.rejects(verifyToken(publicKey, otherToken, issuer, targetResource), { code: 'ERR_JWT_CLAIM_VALIDATION_FAILED' });
  console.log(JSON.stringify({ scope: 'host loopback TLS and real OIDC provider', runtime: process.version,
    standard: 'authorization_code + PKCE S256', accessTokenType: validated.protectedHeader.typ,
    correctCredential: 'PASS', incorrectCredential: 'PASS', untrustedTlsRejected: 'PASS',
    wrongVerifierRejected: 'PASS', wrongAudienceRejected: 'PASS', codeReplayRejected: 'PASS',
    idTokenRejectedAsAccessToken: 'PASS', callback: 'host HTTP client observed authenticated redirect',
    androidAppLink: 'NOT RUN', deviceEnrollment: 'NOT RUN' }));
} catch (error) {
  const location = error.stack?.split('\n').slice(1).find((line) => line.includes('probe.mjs'))?.match(/probe\.mjs:(\d+:\d+)/)?.[1];
  console.error(`A3 OIDC fixture failed at ${stage} (${error.name}, ${error.code ?? 'no_code'}, ${location ?? '-'})`);
  process.exitCode = 1;
} finally {
  if (server?.listening) {
    await new Promise((resolve) => {
      server.close(resolve);
      server.closeAllConnections();
    });
  }
  for (const file of ['openssl.cnf', 'key.pem', 'cert.pem']) rmSync(join(privateDirectory, file), { force: true });
  rmdirSync(privateDirectory);
}
