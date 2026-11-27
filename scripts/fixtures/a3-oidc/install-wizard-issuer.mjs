// SPDX-License-Identifier: GPL-3.0-only
// Real OAuth authorization-code/PKCE provider, synthetic account only. This
// fixture is private behind the isolated installer's HTTPS reverse proxy.
import assert from 'node:assert/strict';
import { createPrivateKey, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createIssuer, finishConsent } from './issuer.mjs';

const [folder, configuration] = process.argv.slice(2);
assert.equal(process.platform, 'linux');
assert.equal(process.env.GITHUB_ACTIONS, 'true');
assert.equal(process.getuid(), 0);
const selected = JSON.parse(readFileSync(configuration));
const { provider, publicKey } = createIssuer(selected.issuer, selected.origin + '/console/auth/callback',
  [selected.audience], createPrivateKey(readFileSync(folder + '/issuer.pem')), selected.subject, selected.client_id);
provider.proxy = true;
const delegate = provider.callback();
const interactions = new Map();
const stats = { authorization: 0, login: 0, consent: 0, token: 0, failed: false };
const record = () => writeFileSync(folder + '/provider-status.json', JSON.stringify({ ...stats, port: server.address()?.port }), { mode: 0o600 });
writeFileSync(folder + '/jwks.json', JSON.stringify({ keys: [publicKey] }), { mode: 0o644 });

const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, selected.issuer).pathname;
    if (path === '/auth') stats.authorization++;
    if (path === '/token') {
      assert.equal(req.method, 'POST');
      stats.token++;
    }
    if (/^\/fixture-login\/[A-Za-z0-9_-]{12,128}$/.test(path)) {
      const details = await provider.interactionDetails(req, res);
      assert.ok(['login', 'consent'].includes(details.prompt.name));
      if (req.method === 'GET') {
        const challenge = randomBytes(24).toString('base64url');
        interactions.set(path, { challenge, phase: details.prompt.name });
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(`<form method="post"><input name="challenge" value="${challenge}"><button>Approve synthetic account</button></form>`);
      } else {
        assert.equal(req.method, 'POST');
        let body = '';
        for await (const chunk of req) {
          body += chunk;
          assert.ok(body.length <= 1024);
        }
        const prior = interactions.get(path);
        interactions.delete(path);
        assert.ok(prior && prior.phase === details.prompt.name && new URLSearchParams(body).get('challenge') === prior.challenge);
        if (details.prompt.name === 'login') {
          stats.login++;
          await provider.interactionFinished(req, res, { login: { accountId: selected.subject } }, { mergeWithLastSubmission: false });
        } else {
          stats.consent++;
          await finishConsent(provider, req, res, details, selected.subject);
        }
      }
    } else {
      await delegate(req, res);
    }
    record();
  } catch {
    stats.failed = true;
    record();
    if (!res.headersSent) res.writeHead(500);
    res.end('synthetic authorization fixture failed');
  }
});
server.listen(0, '127.0.0.1', record);
for (const event of ['SIGINT', 'SIGTERM']) process.on(event, () => server.close(() => process.exit(0)));
