// SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import { createPublicKey, generateKeyPairSync, randomBytes } from 'node:crypto';
import { Provider } from 'oidc-provider';

export const clientId = 'zapime-android-synthetic';
export const subject = 'a3-synthetic-user';
export const scope = 'zapime.device_enrollment';

export function createIssuer(issuer, redirectUri, resources, privateKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey, accountSubject = subject, registeredClient = clientId) {
  const attributes = { kid: 'a3-fixture', alg: 'RS256', use: 'sig' };
  const publicKey = { ...createPublicKey(privateKey).export({ format: 'jwk' }), ...attributes };
  const provider = new Provider(issuer, {
    clients: [{ client_id: registeredClient, application_type: 'native', grant_types: ['authorization_code'],
      response_types: ['code'], redirect_uris: [redirectUri], token_endpoint_auth_method: 'none' }],
    jwks: { keys: [{ ...privateKey.export({ format: 'jwk' }), ...attributes }] },
    cookies: { keys: [randomBytes(32).toString('base64url'), randomBytes(32).toString('base64url')] },
    interactions: { url: (_ctx, interaction) => `/fixture-login/${interaction.uid}` },
    async renderError(ctx) { ctx.body = 'synthetic OIDC request rejected'; },
    ttl: { AccessToken: 60, AuthorizationCode: 90, Grant: 120, IdToken: 60, Interaction: 90, Session: 120 },
    features: {
      devInteractions: { enabled: false },
      resourceIndicators: { enabled: true, async getResourceServerInfo(_ctx, resource) {
        assert.ok(resources.includes(resource));
        return { scope, audience: resource, accessTokenTTL: 60, accessTokenFormat: 'jwt', jwt: { sign: { alg: 'RS256' } } };
      } },
    },
    pkce: { required: () => true },
    async findAccount(_ctx, accountId) {
      if (accountId === accountSubject) return { accountId, async claims() { return { sub: accountId }; } };
      return undefined;
    },
  });
  return { provider, publicKey };
}

export async function finishConsent(provider, req, res, details, accountSubject = subject) {
  const { prompt, session, params } = details;
  assert.equal(prompt.name, 'consent');
  assert.equal(session.accountId, accountSubject);
  const grant = new provider.Grant({ accountId: session.accountId, clientId: params.client_id });
  if (prompt.details.missingOIDCScope) grant.addOIDCScope(prompt.details.missingOIDCScope.join(' '));
  for (const [resource, scopes] of Object.entries(prompt.details.missingResourceScopes ?? {})) {
    grant.addResourceScope(resource, scopes.join(' '));
  }
  await provider.interactionFinished(req, res, { consent: { grantId: await grant.save() } }, { mergeWithLastSubmission: true });
}
