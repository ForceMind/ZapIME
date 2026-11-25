// SPDX-License-Identifier: GPL-3.0-only
package oidcauth

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/go-jose/go-jose/v4"
	"github.com/go-jose/go-jose/v4/jwt"
)

const testScope = "zapime.device_enrollment"

func TestAccessTokenPolicy(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal("generate test signing key")
	}
	policy := AccessPolicy{Issuer: "https://issuer.example.test", Audience: "https://sync.example.test/login",
		ClientID: "zapime-synthetic-device", Scope: testScope, MaxLifetime: 2 * time.Minute}
	keySet := jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{Key: &key.PublicKey, KeyID: "source", Use: "sig", Algorithm: "RS256"}}}
	now := time.Unix(1_800_000_000, 0)
	base := accessClaims{Claims: jwt.Claims{Issuer: policy.Issuer, Subject: "synthetic-subject",
		Audience: jwt.Audience{policy.Audience}, ID: "synthetic-id", IssuedAt: jwt.NewNumericDate(now),
		Expiry: jwt.NewNumericDate(now.Add(time.Minute))}, ClientID: policy.ClientID, Scope: policy.Scope}
	sign := func(claims accessClaims, kind, keyID string) string {
		t.Helper()
		opts := (&jose.SignerOptions{}).WithType(jose.ContentType(kind)).WithHeader("kid", keyID)
		signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.RS256, Key: key}, opts)
		if err != nil {
			t.Fatal("create synthetic signer")
		}
		wire, err := jwt.Signed(signer).Claims(claims).Serialize()
		if err != nil {
			t.Fatal("sign synthetic claims")
		}
		return wire
	}
	valid := sign(base, "at+jwt", "source")
	got, err := VerifyAccessToken(policy, keySet, valid, now)
	issuer, id, tokenID, expiresAt := got.Claims()
	if err != nil || issuer != base.Issuer || id != base.Subject || tokenID != base.ID || !expiresAt.Equal(base.Expiry.Time()) {
		t.Fatal("verified subject did not match")
	}
	if _, err := VerifyAccessToken(policy, keySet, sign(base, "application/at+jwt", "source"), now); err != nil {
		t.Fatal("explicit media type must be accepted")
	}
	public, err := json.Marshal(keySet)
	if err != nil {
		t.Fatal("encode public key snapshot")
	}
	verifier, err := NewVerifier(policy, bytes.NewReader(public))
	if err != nil {
		t.Fatal("configure verification keys")
	}
	if _, err := verifier.Verify(valid, now); err != nil {
		t.Fatal("configured issuer failed to verify access token")
	}
	private, err := json.Marshal(jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{Key: key, KeyID: "source"}}})
	if err != nil {
		t.Fatal("prepare isolated private test key")
	}
	for _, wrong := range [][]byte{private, bytes.Repeat([]byte("x"), maxJWKSBytes+1), []byte(`{"keys":[]}`)} {
		if _, err := NewVerifier(policy, bytes.NewReader(wrong)); !errors.Is(err, ErrConfiguration) {
			t.Fatal("invalid public key configuration accepted")
		}
	}

	claimChanges := map[string]func(*accessClaims){
		"wrong issuer":       func(c *accessClaims) { c.Issuer = "https://other.example.test" },
		"wrong audience":     func(c *accessClaims) { c.Audience = jwt.Audience{policy.Issuer} },
		"added audience":     func(c *accessClaims) { c.Audience = jwt.Audience{policy.Audience, policy.Issuer} },
		"wrong client":       func(c *accessClaims) { c.ClientID = "other" },
		"scope prefix only":  func(c *accessClaims) { c.Scope = testScope + ".extra" },
		"missing subject":    func(c *accessClaims) { c.Subject = "" },
		"missing token ID":   func(c *accessClaims) { c.ID = "" },
		"missing expiration": func(c *accessClaims) { c.Expiry = nil },
		"at expiration":      func(c *accessClaims) { c.Expiry = jwt.NewNumericDate(now) },
		"missing issued at":  func(c *accessClaims) { c.IssuedAt = nil },
		"future issued at":   func(c *accessClaims) { c.IssuedAt = jwt.NewNumericDate(now.Add(time.Second)) },
		"too long":           func(c *accessClaims) { c.Expiry = jwt.NewNumericDate(now.Add(time.Hour)) },
		"future not before":  func(c *accessClaims) { c.NotBefore = jwt.NewNumericDate(now.Add(time.Second)) },
	}
	for name, change := range claimChanges {
		t.Run(name, func(t *testing.T) {
			modified := base
			change(&modified)
			if _, err := VerifyAccessToken(policy, keySet, sign(modified, "at+jwt", "source"), now); !errors.Is(err, ErrInvalidAccessToken) {
				t.Fatal("invalid claims accepted")
			}
		})
	}
	for name, wire := range map[string]string{
		"id token type": sign(base, "JWT", "source"), "unknown key": sign(base, "at+jwt", "other"),
		"new line": "\n" + valid, "oversized": strings.Repeat("a", maxAccessTokenBytes+1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := VerifyAccessToken(policy, keySet, wire, now); !errors.Is(err, ErrInvalidAccessToken) {
				t.Fatal("invalid access token accepted")
			}
		})
	}
	second := *keySet.Keys[0].Key.(*rsa.PublicKey)
	second.E = 3
	for name, keys := range map[string][]jose.JSONWebKey{
		"duplicate key ID": {keySet.Keys[0], keySet.Keys[0]}, "unrelated key": {{Key: &second, KeyID: "source"}},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := VerifyAccessToken(policy, jose.JSONWebKeySet{Keys: keys}, valid, now); !errors.Is(err, ErrInvalidAccessToken) {
				t.Fatal("untrusted key set accepted")
			}
		})
	}
}

// Driven by the local Node OIDC fixture. All token bytes travel over stdin
// and take the same verification path as the signed policy tests.
func TestProviderIssuedAccessToken(t *testing.T) {
	if os.Getenv("ZAPIME_OIDC_FIXTURE_PROOF") != "1" {
		t.Skip("real OIDC issuer proof runs through scripts/fixtures/a3-oidc")
	}
	var input struct {
		Issuer, Audience, ClientID, Subject, AccessToken, OtherAudienceToken, IDToken string
		Keys                                                                          jose.JSONWebKeySet
	}
	reader := json.NewDecoder(io.LimitReader(os.Stdin, 64*1024))
	reader.DisallowUnknownFields()
	if reader.Decode(&input) != nil || reader.Decode(new(any)) != io.EOF ||
		(input.Issuer != "https://a3-oidc.example.test" && !strings.HasPrefix(input.Issuer, "https://a3-oidc.example.test:")) ||
		input.Subject != "a3-synthetic-user" {
		t.Fatal("unexpected synthetic provider proof")
	}
	policy := AccessPolicy{Issuer: input.Issuer, Audience: input.Audience, ClientID: input.ClientID,
		Scope: testScope, MaxLifetime: 2 * time.Minute}
	verified, err := VerifyAccessToken(policy, input.Keys, input.AccessToken, time.Now())
	issuer, id, tokenID, _ := verified.Claims()
	if err != nil || id != input.Subject || issuer != input.Issuer || tokenID == "" {
		t.Fatal("real provider access token not verified")
	}
	rejectedTokens := []string{input.IDToken}
	if input.OtherAudienceToken != "" {
		rejectedTokens = append(rejectedTokens, input.OtherAudienceToken)
	}
	for _, rejected := range rejectedTokens {
		if _, err := VerifyAccessToken(policy, input.Keys, rejected, time.Now()); !errors.Is(err, ErrInvalidAccessToken) {
			t.Fatal("wrong audience or ID token was accepted")
		}
	}
}
