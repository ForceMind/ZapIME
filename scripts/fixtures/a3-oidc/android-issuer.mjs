// SPDX-License-Identifier: GPL-3.0-only
// The fixture is private over adb reverse on API28, or reaches stock modern
// devices through two short-lived HTTPS origins with an explicit route allowlist.
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, createPrivateKey, randomBytes } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { createInterface } from 'node:readline';
import { importJWK, jwtVerify } from 'jose';
import { clientId, createIssuer, finishConsent, subject } from './issuer.mjs';

const [cert, key, publicFingerprint, summaryPath, goProof, mode = 'probe', foregroundFile] = process.argv.slice(2);
assert.ok(['probe', 'main', 'foreground', 'queue', 'archive', 'automatic', 'automatic-recovery'].includes(mode));
const queueWindow = mode === 'queue';
const automatic = mode === 'automatic' || mode === 'automatic-recovery';
const foreground = automatic || ['foreground', 'queue', 'archive'].includes(mode) ? JSON.parse(readFileSync(foregroundFile, 'utf8')) : null;
if (foreground) assert.ok(Number.isInteger(foreground.port) && foreground.port > 1024 && foreground.port <= 65535);
const priorAccount = foreground?.bootstrap?.device.account_id;
const publicPort = Number(process.env.ZAPIME_A3_PUBLIC_PORT || 0);
const issuer = process.env.ZAPIME_A3_PUBLIC_ISSUER || 'https://a3-oidc.example.test';
const fallback = process.env.ZAPIME_A3_PUBLIC_RETURN || 'https://return-a3.example.test';
const registeredClient = process.env.ZAPIME_A3_PUBLIC_CLIENT || clientId;
if (publicPort) {
  assert.equal(mode, 'automatic-recovery');
  assert.ok(Number.isInteger(publicPort) && publicPort > 1024 && publicPort <= 65535);
  for (const origin of [issuer, fallback]) assert.match(origin, /^https:\/\/[a-z0-9-]+\.trycloudflare\.com$/);
  assert.notEqual(issuer, fallback);
  assert.match(registeredClient, /^zapime-ci-[0-9a-f]{32}$/);
  assert.equal(foreground.recovery, undefined);
}
const loginHost = new URL(issuer).host;
const returnHost = new URL(fallback).host;
const resource = issuer + '/a3-device-enrollment';
const redirect = fallback + '/callback';
const loginSubject = automatic ? foreground.loginSubject : subject;
if (automatic) assert.match(loginSubject, /^a3-auto-[0-9a-f]{24}$/);
const { provider, publicKey } = createIssuer(issuer, redirect, [resource],
  automatic ? createPrivateKey(readFileSync(foreground.signingKeyPath)) : undefined, loginSubject, registeredClient);
if (publicPort) provider.proxy = true;
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

function publicRoute(req, host, url) {
  if (host === returnHost) {
    return req.method === 'GET' && (url.pathname === '/.well-known/assetlinks.json' || url.pathname === '/callback');
  }
  if (host !== loginHost) return false;
  if (req.method === 'GET' && ['/auth', '/v1/account-login', '/v1/session', '/v1/key-descriptor', '/v1/changes'].includes(url.pathname)) {
    return true;
  }
  if (req.method === 'POST' && ['/token', '/v1/device-enrollment/automatic', '/v1/changes'].includes(url.pathname)) return true;
  return ['GET', 'POST'].includes(req.method) && /^\/fixture-login\/[A-Za-z0-9_-]{12,128}$/.test(url.pathname);
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
    await provider.interactionFinished(req, res, { login: { accountId: loginSubject } }, { mergeWithLastSubmission: false });
  } else {
    totals.consent++;
    record();
    await finishConsent(provider, req, res, details, loginSubject);
  }
}

async function handle(req, res) {
  const url = new URL(req.url, issuer);
  const host = req.headers.host;
  if (publicPort) {
    if (!publicRoute(req, host, url)) {
      reply(res, 404, 'not found');
      return;
    }
    req.headers['x-forwarded-host'] = host;
    req.headers['x-forwarded-proto'] = 'https';
  }
  if (foreground && host === loginHost) {
    if (!publicPort && mode === 'automatic-recovery' && req.method === 'GET' && url.pathname === '/fixture-enrollment/automatic-recovery' && !url.search) {
      assert.ok(foreground.recovery && totals.automaticGrants === 1 && totals.dataRequests === 0);
      reply(res, 200, JSON.stringify(foreground.recovery), 'application/json');
      delete foreground.recovery;
      return;
    }
    if (!automatic && req.method === 'GET' && url.pathname === '/fixture-enrollment/foreground' && !url.search) {
      assert.equal(foregroundSeedServed, false);
      foregroundSeedServed = true;
      const subjectFingerprint = loginFingerprint(priorAccount, 'a3-synthetic-foreground-owner');
      reply(res, 200, JSON.stringify({ ...foreground.bootstrap, subject_fingerprint: subjectFingerprint }), 'application/json');
      delete foreground.bootstrap;
      return;
    }
    const method = req.method;
    const plainRead = method === 'GET' && !url.search && ['/v1/session', '/v1/key-descriptor'].includes(url.pathname);
    const lookup = automatic && method === 'GET' && !url.search && url.pathname === '/v1/account-login';
    const grant = automatic && method === 'POST' && !url.search && url.pathname === '/v1/device-enrollment/automatic';
    const initialize = automatic && method === 'PUT' && !url.search && url.pathname === '/v1/key-descriptor/initialize';
    const dataRead = method === 'GET' && url.pathname === '/v1/changes' && /^\?after=\d+&limit=\d+$/.test(url.search);
    const dataWrite = method === 'POST' && url.pathname === '/v1/changes' && !url.search;
    if (plainRead || dataRead || dataWrite || lookup || grant || initialize) {
      if (mode === 'archive' && dataWrite) {
        totals.uploads = (totals.uploads ?? 0) + 1;
        throw new Error('unexpected_archive_fixture_upload');
      }
      const authorization = req.headers.authorization;
      const tokenLimit = lookup || grant ? 16_391 : 512;
      assert.ok(typeof authorization === 'string' && authorization.length <= tokenLimit && authorization.startsWith('Bearer '));
      const request = dataWrite || grant || initialize ? await read(req, 8192, null) : undefined;
      const upstream = await fetch('http://127.0.0.1:' + foreground.port + url.pathname + url.search, {
        method, headers: { authorization, 'content-type': 'application/json' },
        body: request, redirect: 'error', signal: AbortSignal.timeout(5000),
      });
      const payload = await upstream.text();
      assert.ok(Buffer.byteLength(payload) <= 16_384);
      if (dataRead || dataWrite) totals.dataRequests++;
      if (lookup) {
        const identity = JSON.parse(payload);
        assert.equal(upstream.status, 200);
        assert.equal(identity.account_id, foreground.account);
        assert.equal(identity.subject_fingerprint, loginFingerprint(foreground.account, loginSubject));
        totals.accountLookups++;
      }
      if (grant) {
        const device = JSON.parse(payload);
        assert.equal(upstream.status, 200);
        assert.equal(device.account_id, foreground.account);
        assert.equal(device.device_id, JSON.parse(request).device_id);
        assert.equal(device.role, 'member');
        totals.automaticGrants = (totals.automaticGrants ?? 0) + 1;
        if (mode === 'automatic-recovery' && !publicPort) {
          assert.equal(totals.automaticGrants, 1);
          totals.droppedAutomaticGrantReceipt = true;
          record();
          res.destroy();
          return;
        }
      }
      if (initialize) {
        assert.equal(mode, 'automatic');
        assert.equal(upstream.status, 200);
        assert.deepEqual(JSON.parse(payload).descriptor, JSON.parse(request));
        totals.automaticInitializations = (totals.automaticInitializations ?? 0) + 1;
        assert.equal(totals.automaticInitializations, 1);
        totals.droppedAutomaticKeyReceipt = true;
        record();
        res.destroy();
        return;
      }
      if (url.pathname === '/v1/session') totals.foregroundDeviceSessions = (totals.foregroundDeviceSessions ?? 0) + 1;
      if (url.pathname === '/v1/key-descriptor') totals.foregroundDescriptorRequests = (totals.foregroundDescriptorRequests ?? 0) + 1;
      if (dataWrite) {
        totals.uploads = (totals.uploads ?? 0) + 1;
        assert.ok(totals.uploads <= (automatic ? 1 : queueWindow ? 18 : 4) && upstream.status === 200 && JSON.parse(payload).revision === totals.uploads);
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
  if (host === returnHost) {
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
  assert.equal(host, loginHost);
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

function handler(req, res) {
  handle(req, res).catch(() => {
    totals.failure = true;
    record();
    if (!res.headersSent) reply(res, 500, 'synthetic fixture rejected');
    else res.end();
  });
}
const server = publicPort ? createHttpServer(handler)
  : createHttpsServer({ cert: readFileSync(cert), key: readFileSync(key), minVersion: 'TLSv1.2' }, handler);
server.listen(publicPort, '127.0.0.1', record);
