// SPDX-License-Identifier: GPL-3.0-only
// Synthetic credentials for the isolated sync-service backup/restore exercise.
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [stage, folder] = process.argv.slice(2);
const issuer = 'https://restore-login.example.test';
const subject = 'a3-restore-bound-subject';
const audience = 'https://restore-sync.example.test/device-enrollment';
const clientId = 'zapime-a3-restore-fixture';
const keyId = 'a3-restore-rsa';
const endpoint = '/v1/device-enrollment';

function check(value, reason) {
  if (!value) throw new Error(reason);
}

function framed(...fields) {
  return Buffer.concat(fields.flatMap((field) => {
    const bytes = Buffer.from(field);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    return [length, bytes];
  }));
}

function digest(...fields) {
  return createHash('sha256').update(framed(...fields)).digest();
}

function signature(purpose, fingerprint, access, key) {
  const accessHash = createHash('sha256').update(access).digest();
  return sign('sha256', framed(purpose, fingerprint, accessHash), key).toString('base64url');
}

function accessToken(forSubject) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'at+jwt', kid: keyId };
  const claims = { iss: issuer, sub: forSubject, aud: [audience], client_id: clientId,
    scope: 'zapime.device_enrollment', iat: now, exp: now + 120, jti: randomUUID() };
  const input = [header, claims].map((part) => Buffer.from(JSON.stringify(part)).toString('base64url')).join('.');
  return `${input}.${sign('sha256', Buffer.from(input), readFileSync(join(folder, 'issuer.pem'))).toString('base64url')}`;
}

async function call(path, token, expected, reason, body) {
  const response = await fetch(`http://127.0.0.1:8787${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error', signal: AbortSignal.timeout(10000),
  }).catch(() => { throw new Error(`${reason}_request`); });
  check(response.status === expected, `${reason}_status_${response.status}`);
  return expected === 204 ? null : response.json();
}

function prepare() {
  const signing = generateKeyPairSync('rsa', { modulusLength: 2048 });
  writeFileSync(join(folder, 'issuer.pem'), signing.privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600, flag: 'wx' });
  const publicKey = { ...signing.publicKey.export({ format: 'jwk' }), kid: keyId, alg: 'RS256', use: 'sig' };
  writeFileSync(join(folder, 'jwks.json'), JSON.stringify({ keys: [publicKey] }), { mode: 0o644, flag: 'wx' });
  console.log(JSON.stringify({ issuer, subject, audience, client_id: clientId }));
}

async function identity(owner, access) {
  const session = await call('/v1/account-login', access, 200, 'bound_login');
  check(session.protocol === 1 && session.account_id === owner.account_id && session.subject_fingerprint ===
    digest('zapime-account-login-v1', issuer, subject, audience, owner.account_id).toString('hex'), 'bound_login_identity');
}

async function listDevices(owner) {
  const result = await call('/v1/devices', owner.token, 200, 'owner_device_list');
  check(Array.isArray(result.devices), 'owner_device_list_shape');
  return result.devices.map((device) => device.id).sort();
}

async function pending(owner) {
  const access = accessToken(subject);
  await identity(owner, access);
  const device = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicKey = device.publicKey.export({ format: 'der', type: 'spki' });
  const privateKey = device.privateKey.export({ format: 'pem', type: 'pkcs8' });
  const token = `zap1_${randomBytes(32).toString('base64url')}`;
  const tokenHash = createHash('sha256').update(token).digest();
  const id = randomUUID();
  const name = 'A3 restore synthetic device';
  const deadline = Math.floor(Date.now() / 1000) + 9 * 60;
  const fingerprint = digest('zapime-enrollment-v1', issuer, audience, owner.account_id, id, name, String(deadline), publicKey, tokenHash);
  const receipt = await call(endpoint, access, 200, 'begin_enrollment', {
    enrollment_id: id, account_id: owner.account_id, name, deadline, public_key: publicKey.toString('base64url'),
    token_sha256: tokenHash.toString('base64url'), signature: signature('zapime-enrollment-begin-v1', fingerprint, access, privateKey),
  });
  check(receipt.enrollment_id === id && receipt.fingerprint === fingerprint.toString('hex') && receipt.deadline === deadline,
    'begin_enrollment_receipt');
  await call('/v1/session', token, 401, 'device_denied_before_backup');
  const devices = await listDevices(owner);
  check(devices.includes(owner.device_id) && !devices.includes(id), 'pending_device_list');
  writeFileSync(join(folder, 'pending.json'), JSON.stringify({ receipt, privateKey, token, devices }), { mode: 0o600, flag: 'wx' });
}

async function verify(owner) {
  const access = accessToken(subject);
  await identity(owner, access);
  await call('/v1/account-login', accessToken('a3-restore-unbound-subject'), 401, 'unbound_login');
  const { receipt, privateKey, token, devices } = JSON.parse(readFileSync(join(folder, 'pending.json'), 'utf8'));
  const id = receipt.enrollment_id;
  check(JSON.stringify(await listDevices(owner)) === JSON.stringify(devices), 'restored_device_list');
  await call('/v1/session', token, 401, 'restored_device_not_authorized');
  const claim = { enrollment_id: id, account_id: owner.account_id,
    signature: signature('zapime-enrollment-claim-v1', Buffer.from(receipt.fingerprint, 'hex'), access, privateKey) };
  const denied = await call(`${endpoint}/claim`, access, 409, 'restored_device_pending', claim);
  check(denied.error === 'enrollment_pending', 'restored_pending_reason');
  await call(`${endpoint}/approve`, owner.token, 204, 'approve_original_request', { enrollment_id: id, fingerprint: receipt.fingerprint });
  const claimed = await call(`${endpoint}/claim`, access, 200, 'claim_original_request', claim);
  const session = await call('/v1/session', token, 200, 'restored_device_session');
  check(claimed.protocol === 1 && claimed.account_id === owner.account_id && claimed.device_id === id && claimed.role === 'member'
    && session.protocol === 1 && session.account_id === claimed.account_id && session.device_id === claimed.device_id
    && session.role === claimed.role && session.expires_at === claimed.expires_at && Date.parse(session.expires_at) > Date.now(),
  'restored_device_session_identity');
  check(JSON.stringify(await listDevices(owner)) === JSON.stringify([...devices, id].sort()), 'exactly_one_enrolled_device');
  console.log(JSON.stringify({ status: 'PASS', binding_restored: true, unbound_rejected: true, pending_restored: true, new_devices: 1 }));
}

async function automatic(owner) {
  const access = accessToken(subject);
  await identity(owner, access);
  await call('/v1/account-login', accessToken('a3-restore-unbound-subject'), 401, 'unbound_login');
  const installation = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicKey = installation.publicKey.export({ format: 'der', type: 'spki' });
  const device = randomUUID();
  const token = `zap1_${randomBytes(32).toString('base64url')}`;
  const tokenHash = createHash('sha256').update(token).digest();
  const name = 'installer automatic synthetic test';
  const subjectHash = digest('zapime-account-login-v1', issuer, subject, audience, owner.account_id).toString('hex');
  const fingerprint = digest('zapime-auto-device-v1', issuer, audience, owner.account_id, device, subjectHash, name, publicKey, tokenHash);
  const request = { device_id: device, account_id: owner.account_id, name, public_key: publicKey.toString('base64url'),
    token_sha256: tokenHash.toString('base64url'), signature: signature('zapime-auto-device-sign-v1', fingerprint, access, installation.privateKey) };
  const session = await call(`${endpoint}/automatic`, access, 200, 'automatic_device', request);
  const retry = await call(`${endpoint}/automatic`, access, 200, 'automatic_retry', request);
  check(JSON.stringify(session) === JSON.stringify(retry) && session.account_id === owner.account_id &&
    session.device_id === device && session.role === 'member', 'automatic_identity');
  check((await call('/v1/session', token, 200, 'automatic_session')).device_id === device, 'automatic_device_session');
  const credential = join(folder, 'automatic-device.json');
  if (owner.save_credential === true) {
    writeFileSync(credential, JSON.stringify({ account_id: owner.account_id, device_id: device, token }),
      { mode: 0o600, flag: 'wx' });
  }
  console.log(JSON.stringify({ status: 'PASS', bound_login: true, automatic_member: true, repeated_grant: true }));
}

try {
  if (stage === 'prepare') {
    prepare();
  } else {
    const owner = JSON.parse(readFileSync(0, 'utf8'));
    if (stage === 'pending') await pending(owner);
    else if (stage === 'verify') await verify(owner);
    else if (stage === 'automatic') await automatic(owner);
    else throw new Error('unknown_stage');
  }
} catch (error) {
  const reason = typeof error.message === 'string' && /^[a-z][a-z_]{2,64}(?:_status_\d{3})?$/.test(error.message)
    ? error.message : 'fixture_execution_failed';
  console.error(`A3 restore probe: ${reason}`);
  process.exitCode = 1;
}
