// SPDX-License-Identifier: GPL-3.0-only
// The two synthetic hosts are reachable only through this CI job's adb reverse.
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { createInterface } from 'node:readline';
import { importJWK, jwtVerify } from 'jose';
import { clientId, createIssuer, finishConsent, subject } from './issuer.mjs';

const [cert, key, publicFingerprint, summaryPath, goProof, mode = 'probe', foregroundFile] = process.argv.slice(2);
assert.ok(['probe', 'main', 'foreground', 'queue'].includes(mode));
const queueWindow = mode === 'queue';
const foreground = mode === 'foreground' || queueWindow ? JSON.parse(readFileSync(foregroundFile, 'utf8')) : null;
if (foreground) assert.ok(Number.isInteger(foreground.port) && foreground.port > 1024 && foreground.port <= 65535);
const priorAccount = foreground?.bootstrap.device.account_id;
const issuer = 'https://a3-oidc.example.test';
const fallback = 'https://return-a3.example.test';
const resource = issuer + '/a3-device-enrollment';
const redirect = fallback + '/callback';
const { provider, publicKey } = createIssuer(issuer, redirect, [resource]);
const verifyKey = await importJWK(publicKey, 'RS256');
const delegate = provider.callback();
const interactions = new Map();
const totals = { assetlinks: 0, login: 0, consent: 0, tokenPosts: 0, goProofs: 0, accountLookups: 0,
  callbackOpenedInBrowser: 0, unexpected: 0, dataRequests: 0, failure: false, enrollmentBegins: 0,
  enrollmentClaims: 0, enrollmentSessions: 0, droppedClaimReplies: 0, enrollmentFinished: 0 };
let enrollmentSeed, backend;
let foregroundSeedServed = false;

function loginFingerprint(accountId, accountSubject) {
  const identityHash = createHash('sha256');
  for (const value of ['zapime-account-login-v1', issuer, accountSubject, resource, accountId]) {
    const data = Buffer.from(value);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    identityHash.update(length).update(data);
  }
  return identityHash.digest('hex');
}

function holdSyntheticAcknowledgement(res, payload, request) {
  const began = performance.now();
  totals.heldAckDigest = createHash('sha256').update(request).digest('hex');
  totals.heldAckLive = true;
  totals.heldAckDelivered = false;
  res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.write(' ');
  const pulse = setInterval(() => {
    if (performance.now() - began < 11_000) {
      // The prefix is valid JSON whitespace. The actual receipt stays held.
      res.write(' ');
    } else {
      clearInterval(pulse);
      totals.heldAckDelivered = true;
      record();
      res.end(payload);
    }
  }, 350);
  res.once('close', () => {
    clearInterval(pulse);
    totals.heldAckLive = false;
    totals.heldAckClosedAt = Date.now();
    totals.heldAckDurationMs = Math.round(performance.now() - began);
    record();
  });
  record();
}

function record() {
  writeFileSync(summaryPath + '.tmp', JSON.stringify({ port: server.address()?.port ?? 0, ...totals }), { mode: 0o600 });
  renameSync(summaryPath + '.tmp', summaryPath);
}

function reply(res, status, payload, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': contentType, 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' });
  res.end(payload);
}

async function read(req, limit, encoding = 'utf8') {
  let size = 0;
  const chunks = [];
  for await (const part of req) {
    size += part.length;
    if (size > limit) throw new Error('bounded_fixture_input');
    chunks.push(part);
  }
  const body = Buffer.concat(chunks);
  return encoding === null ? body : body.toString(encoding);
}

async function enrollmentBackend() {
  const child = spawn(goProof, ['-test.run=^TestProviderIssuedEnrollmentRoutes$', '-test.count=1'], {
    env: { ...process.env, ZAPIME_A3_ENROLLMENT_ROUTES: '1' },
    stdio: ['pipe', 'pipe', 'ignore'],
  });
  const exit = new Promise((resolve) => {
    child.once('exit', resolve);
    child.once('error', () => resolve(null));
  });
  child.stdin.on('error', () => {});
  const output = createInterface({ input: child.stdout })[Symbol.asyncIterator]();
  async function next() {
    const { value, done } = await output.next();
    assert.ok(!done && value.length <= 8192);
    return JSON.parse(value);
  }
  child.stdin.write(JSON.stringify({ keys: [publicKey] }) + '\n');
  assert.equal((await next()).status, 200);
  return async (request) => {
    child.stdin.write(JSON.stringify(request) + '\n');
    const result = await next();
    if (request.operation === 'finish') {
      child.stdin.end();
      assert.equal(await exit, 0);
    }
    return result;
  };
}

async function enrollment(req, res, path) {
  if (req.method === 'GET' && path === '/fixture-enrollment/access') {
    assert.ok(totals.accountLookups === 1 && enrollmentSeed !== undefined && backend === undefined);
    backend = await enrollmentBackend();
    const seed = enrollmentSeed;
    enrollmentSeed = undefined;
    reply(res, 200, JSON.stringify(seed), 'application/json');
    return true;
  }
  if (!backend) return false;
  const operation = {
    'POST /v1/device-enrollment': 'begin',
    'POST /v1/device-enrollment/claim': 'claim',
    'GET /v1/session': 'session',
    'POST /fixture-enrollment/approve': 'approve',
    'POST /fixture-enrollment/revoke': 'revoke',
    'POST /fixture-enrollment/finish': 'finish',
  }[req.method + ' ' + path];
  if (!operation) return false;
  let result;
  if (operation === 'begin' || operation === 'claim') {
    result = await backend({ operation, authorization: req.headers.authorization, body: JSON.parse(await read(req, 4096)) });
    if (operation === 'begin') {
      assert.ok(++totals.enrollmentBegins === 1 && result.status === 200);
    } else if (++totals.enrollmentClaims === 1) {
      assert.equal(result.status, 409);
    } else {
      assert.ok(totals.enrollmentClaims === 2 && result.status === 200);
      totals.droppedClaimReplies++;
      record();
      // Drop the reply only after the real Go/PostgreSQL claim has succeeded.
      res.destroy();
      return true;
    }
  } else if (operation === 'session') {
    result = await backend({ operation, authorization: req.headers.authorization });
    assert.equal(result.status, [401, 200, 401][totals.enrollmentSessions++]);
  } else {
    result = await backend({ operation });
    assert.equal(result.status, 204);
    if (operation === 'finish') {
      assert.ok(totals.enrollmentBegins === 1 && totals.enrollmentClaims === 2 && totals.enrollmentSessions === 3
        && totals.droppedClaimReplies === 1 && totals.dataRequests === 0);
      totals.enrollmentFinished++;
    }
  }
  record();
  reply(res, result.status, result.body === null ? '' : JSON.stringify(result.body), 'application/json');
  return true;
}

async function interact(req, res, path) {
  const details = await provider.interactionDetails(req, res);
  const phase = details.prompt.name;
  assert.ok(phase === 'login' || phase === 'consent');
  if (req.method === 'GET') {
    const challenge = randomBytes(24).toString('base64url');
    interactions.set(path, { challenge, phase });
    const label = phase === 'login' ? 'Use synthetic test account' : 'Approve synthetic access';
    reply(res, 200, `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">` +
      `<title>ZapIME CI login</title><form method="post" style="margin:80px 12px">` +
      `<input type="hidden" name="challenge" value="${challenge}">` +
      `<button style="font-size:24px;padding:16px" type="submit">${label}</button></form>`, 'text/html; charset=utf-8');
    return;
  }
  assert.equal(req.method, 'POST');
  const stored = interactions.get(path);
  interactions.delete(path);
  const submitted = new URLSearchParams(await read(req, 1_024));
  assert.ok(stored && stored.phase === phase && submitted.get('challenge') === stored.challenge);
  if (phase === 'login') {
    totals.login++;
    record();
    await provider.interactionFinished(req, res, { login: { accountId: subject } }, { mergeWithLastSubmission: false });
  } else {
    totals.consent++;
    record();
    await finishConsent(provider, req, res, details);
  }
}

async function handle(req, res) {
  const url = new URL(req.url, issuer);
  const host = req.headers.host;
  if (foreground && host === 'a3-oidc.example.test') {
    if (req.method === 'GET' && url.pathname === '/fixture-enrollment/foreground' && !url.search) {
      assert.equal(foregroundSeedServed, false);
      foregroundSeedServed = true;
      const subjectFingerprint = loginFingerprint(priorAccount, 'a3-synthetic-foreground-owner');
      reply(res, 200, JSON.stringify({ ...foreground.bootstrap, subject_fingerprint: subjectFingerprint }), 'application/json');
      delete foreground.bootstrap;
      return;
    }
    const method = req.method;
    const plainRead = method === 'GET' && !url.search && ['/v1/session', '/v1/key-descriptor'].includes(url.pathname);
    const dataRead = method === 'GET' && url.pathname === '/v1/changes' && /^\?after=\d+&limit=\d+$/.test(url.search);
    const dataWrite = method === 'POST' && url.pathname === '/v1/changes' && !url.search;
    if (plainRead || dataRead || dataWrite) {
      const authorization = req.headers.authorization;
      assert.ok(typeof authorization === 'string' && authorization.length <= 512 && authorization.startsWith('Bearer '));
      const request = dataWrite ? await read(req, 8192, null) : undefined;
      const upstream = await fetch('http://127.0.0.1:' + foreground.port + url.pathname + url.search, {
        method, headers: { authorization, 'content-type': 'application/json' },
        body: request, redirect: 'error', signal: AbortSignal.timeout(5000),
      });
      const payload = await upstream.text();
      assert.ok(Buffer.byteLength(payload) <= 16_384);
      if (dataRead || dataWrite) totals.dataRequests++;
      if (url.pathname === '/v1/session') totals.foregroundDeviceSessions = (totals.foregroundDeviceSessions ?? 0) + 1;
      if (url.pathname === '/v1/key-descriptor') totals.foregroundDescriptorRequests = (totals.foregroundDescriptorRequests ?? 0) + 1;
      if (dataWrite) {
        totals.uploads = (totals.uploads ?? 0) + 1;
        assert.ok(totals.uploads <= (queueWindow ? 18 : 4) && upstream.status === 200 && JSON.parse(payload).revision === totals.uploads);
        if (queueWindow) {
          const packet = JSON.parse(request);
          const deleted = totals.uploads === 17;
          const recordIndex = deleted ? 1 : totals.uploads;
          assert.equal(packet.record_id, '96abb4f1-8f2c-4bb3-8000-' + recordIndex.toString(16).padStart(12, '0'));
          assert.equal(packet.deleted, deleted);
          assert.equal(packet.base_revision, deleted ? 1 : 0);
          if (totals.uploads === 1) writeFileSync(foregroundFile + '.first-upload', request, { flag: 'wx', mode: 0o600 });
        } else if (totals.uploads === 4) {
          assert.equal(JSON.parse(request).operation_id, '8cbfd5d6-dc74-4fbd-b600-08da374eb8f2');
          holdSyntheticAcknowledgement(res, payload, request);
          return;
        }
      }
      record();
      reply(res, upstream.status, payload, 'application/json');
      return;
    }
  }
  if (url.pathname.startsWith('/v1/changes')) {
    totals.dataRequests++;
    record();
    reply(res, 404, 'fixture does not serve data');
    return;
  }
  if (host === 'return-a3.example.test') {
    if (req.method === 'GET' && url.pathname === '/.well-known/assetlinks.json') {
      totals.assetlinks++;
      record();
      reply(res, 200, JSON.stringify([{ relation: ['delegate_permission/common.handle_all_urls'], target: {
        namespace: 'android_app', package_name: mode === 'probe' ? 'org.zapime.android.a3loginprobe' : 'org.zapime.android',
        sha256_cert_fingerprints: [publicFingerprint],
      } }]), 'application/json');
    } else if (url.pathname === '/callback') {
      totals.callbackOpenedInBrowser++;
      record();
      reply(res, 200, 'The browser did not dispatch the App Link');
    } else {
      reply(res, 404, 'not found');
    }
    return;
  }
  assert.equal(host, 'a3-oidc.example.test');
  if (url.pathname.startsWith('/fixture-login/')) {
    await interact(req, res, url.pathname);
    return;
  }
  if ((mode === 'main' || mode === 'foreground') && req.method === 'GET' && url.pathname === '/v1/account-login') {
    assert.ok(!url.search && totals.accountLookups === 0);
    assert.ok(totals.login === 1 && totals.consent === 1 && totals.tokenPosts === 1);
    if (foreground) assert.ok(foregroundSeedServed && totals.uploads === 4);
    const authorization = req.headers.authorization;
    assert.ok(typeof authorization === 'string' && authorization.startsWith('Bearer '));
    const accessToken = authorization.slice('Bearer '.length);
    const output = execFileSync(goProof, ['-test.run=^TestProviderIssuedAccountLookup$', '-test.count=1'], {
      env: { ...process.env, ZAPIME_A3_ACCOUNT_LOOKUP: '1' }, timeout: 10_000,
      input: JSON.stringify({ accessToken, keys: { keys: [publicKey] }, priorAccount }), encoding: 'utf8',
    });
    const account = JSON.parse(output.split('\n', 1)[0]);
    const accountId = '98a01050-3185-4eed-bac4-127426c17372';
    assert.deepEqual(account, { protocol: 1, account_id: accountId, subject_fingerprint: loginFingerprint(accountId, subject) });
    if (mode === 'main') {
      enrollmentSeed = { access_token: accessToken, account_id: accountId, subject_fingerprint: account.subject_fingerprint };
    } else {
      totals.distinctAccountInForegroundDatabase = accountId !== priorAccount;
    }
    totals.accountLookups++;
    totals.goProofs++;
    record();
    reply(res, 200, JSON.stringify(account), 'application/json');
    return;
  }
  if (mode === 'main' && await enrollment(req, res, url.pathname)) return;
  if (url.pathname.startsWith('/v1/')) {
    totals.unexpected++;
    record();
    reply(res, 404, 'unexpected fixture endpoint');
    return;
  }
  if (req.method === 'POST' && url.pathname === '/android-proof') {
    assert.equal(totals.goProofs, 0);
    assert.ok(totals.login === 1 && totals.consent === 1 && totals.tokenPosts === 1);
    const { accessToken, idToken, nonce } = JSON.parse(await read(req, 35_000));
    const id = await jwtVerify(idToken, verifyKey, { issuer, audience: clientId, algorithms: ['RS256'] });
    assert.ok(id.payload.sub === subject && id.payload.nonce === nonce && typeof nonce === 'string');
    execFileSync(goProof, ['-test.run=^TestProviderIssuedAccessToken$', '-test.count=1'], {
      env: { ...process.env, ZAPIME_OIDC_FIXTURE_PROOF: '1' }, timeout: 7_000,
      input: JSON.stringify({ issuer, audience: resource, clientID: clientId, subject, accessToken, idToken,
        keys: { keys: [publicKey] } }),
    });
    totals.goProofs++;
    record();
    reply(res, 200, '{"ok":true}', 'application/json');
    return;
  }
  if (req.method === 'POST' && url.pathname === '/token') {
    totals.tokenPosts++;
    record();
  }
  delegate(req, res);
}

const server = createServer({ cert: readFileSync(cert), key: readFileSync(key), minVersion: 'TLSv1.2' }, (req, res) => {
  handle(req, res).catch(() => {
    totals.failure = true;
    record();
    if (!res.headersSent) reply(res, 500, 'synthetic fixture rejected');
    else res.end();
  });
});
server.listen(0, '127.0.0.1', record);
