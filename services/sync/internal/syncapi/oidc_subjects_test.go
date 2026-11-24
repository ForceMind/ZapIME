// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
	"github.com/go-jose/go-jose/v4"
	"github.com/go-jose/go-jose/v4/jwt"
)

func TestPostgresOIDCSubjectBinding(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	const issuer = "https://login.example.test"
	const otherIssuer = "https://other-login.example.test"
	const subject = "synthetic-login-subject"
	const resource = "https://sync.example.test/device-enrollment"
	const scope = "zapime.device_enrollment"
	a, owner := provision(t, s)
	b, _ := provision(t, s)
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal("generate synthetic signing key")
	}
	keys := jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{Key: &key.PublicKey, KeyID: "fixture"}}}
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.RS256, Key: key},
		(&jose.SignerOptions{}).WithType("at+jwt").WithHeader("kid", "fixture"))
	if err != nil {
		t.Fatal("create synthetic signer")
	}
	signToken := func(from, id string) string {
		t.Helper()
		now := time.Now().UTC()
		wire, err := jwt.Signed(signer).Claims(jwt.Claims{Issuer: from, Subject: id, Audience: jwt.Audience{resource},
			ID: "synthetic-token-id", IssuedAt: jwt.NewNumericDate(now), Expiry: jwt.NewNumericDate(now.Add(time.Minute))}).
			Claims(map[string]any{"client_id": "synthetic-device", "scope": scope}).Serialize()
		if err != nil {
			t.Fatal("sign synthetic test identity")
		}
		return wire
	}
	policy := func(from string) oidcauth.AccessPolicy {
		return oidcauth.AccessPolicy{Issuer: from, Audience: resource, ClientID: "synthetic-device", Scope: scope, MaxLifetime: time.Minute}
	}
	proof := func(from, id string) oidcauth.Subject {
		t.Helper()
		wire := signToken(from, id)
		verified, err := oidcauth.VerifyAccessToken(policy(from), keys, wire, time.Now())
		if err != nil {
			t.Fatal("validate synthetic test identity")
		}
		return verified
	}
	if _, err = s.ResolveOIDCAccount(ctx, oidcauth.Subject{}); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("unverified identity accepted")
	}
	if _, err = s.ResolveOIDCAccount(ctx, proof(issuer, subject)); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("unbound proof accepted")
	}
	if err = s.BindOIDCSubject(ctx, id(t), issuer, subject); !errors.Is(err, ErrInvalid) {
		t.Fatal("created a binding for a nonexisting account")
	}
	for range 2 {
		if err = s.BindOIDCSubject(ctx, a.AccountID, issuer, subject); err != nil {
			t.Fatal("initial or identical repeat binding failed")
		}
	}
	if err = s.BindOIDCSubject(ctx, b.AccountID, issuer, subject); !errors.Is(err, ErrOIDCSubjectBound) {
		t.Fatal("previous identity was reassigned")
	}
	if err = s.BindOIDCSubject(ctx, b.AccountID, otherIssuer, subject); err != nil {
		t.Fatal("issuer namespace was not independent")
	}
	for from, expected := range map[string]string{issuer: a.AccountID, otherIssuer: b.AccountID} {
		if got, err := s.ResolveOIDCAccount(ctx, proof(from, subject)); err != nil || got != expected {
			t.Fatal("verified identity resolved to the wrong account")
		}
	}
	public, err := json.Marshal(keys)
	if err != nil {
		t.Fatal("encode synthetic public key")
	}
	verifier, err := oidcauth.NewVerifier(policy(issuer), bytes.NewReader(public))
	if err != nil {
		t.Fatal("configure synthetic issuer")
	}
	api := NewAPIWithOIDC(s, verifier)
	call := func(handler *API, endpoint, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, endpoint, nil)
		r.Header.Set("Authorization", "Bearer "+token)
		out := httptest.NewRecorder()
		handler.ServeHTTP(out, r)
		return out
	}
	validToken := signToken(issuer, subject)
	const login = "/v1/account-login"
	valid := call(api, login, validToken)
	var session struct {
		Protocol  int    `json:"protocol"`
		AccountID string `json:"account_id"`
	}
	if valid.Code != http.StatusOK || json.Unmarshal(valid.Body.Bytes(), &session) != nil || session.Protocol != 1 || session.AccountID != a.AccountID {
		t.Fatal("read-only OIDC lookup did not identify the bound account")
	}
	for _, token := range []string{signToken(issuer, "not-bound"), signToken(otherIssuer, subject), a.Token} {
		if call(api, login, token).Code != http.StatusUnauthorized {
			t.Fatal("unbound subject, other issuer or device token used as a login proof")
		}
	}
	if call(api, "/v1/session", validToken).Code != http.StatusUnauthorized ||
		call(api, "/v1/session", a.Token).Code != http.StatusOK ||
		call(api, login+"?account_id=other", validToken).Code != http.StatusBadRequest ||
		call(NewAPI(s), login, validToken).Code != http.StatusNotFound {
		t.Fatal("login identity and device authorization are not independent")
	}
	start, results := make(chan struct{}), make(chan error, 2)
	for _, account := range []string{a.AccountID, b.AccountID} {
		go func() {
			<-start
			results <- s.BindOIDCSubject(ctx, account, issuer, "simultaneous-test-subject")
		}()
	}
	close(start)
	first, second := <-results, <-results
	if !(first == nil && errors.Is(second, ErrOIDCSubjectBound) || second == nil && errors.Is(first, ErrOIDCSubjectBound)) {
		t.Fatal("concurrent reassignment was not rejected")
	}
	devices, err := s.Devices(ctx, owner)
	if err != nil || len(devices) != 1 || devices[0].ID != owner.DeviceID {
		t.Fatal("binding or lookup issued a device")
	}
	page, err := s.Pull(ctx, owner, 0, 1)
	if err != nil || page.Head != 0 || len(page.Changes) != 0 {
		t.Fatal("identity binding changed account data")
	}
}

func TestPostgresOIDCSubjectsMigration(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	_, owner := provision(t, s)
	registered, err := s.RegisterKeyDescriptor(ctx, owner, descriptor(t))
	if err != nil {
		t.Fatal("seed existing schema3 key descriptor")
	}
	if _, err = s.DB.Exec(`DROP TABLE zapime_oidc_subjects; UPDATE zapime_schema SET version=3 WHERE id=1`); err != nil {
		t.Fatal("prepare isolated schema3 database")
	}
	if err = s.Migrate(ctx); err != nil {
		t.Fatal("schema3 migration failed")
	}
	if s.BindOIDCSubject(ctx, owner.AccountID, "https://login.example.test", "migration-test-subject") != nil {
		t.Fatal("migrated database cannot bind an OIDC subject")
	}
	current, err := s.KeyDescriptor(ctx, owner)
	if err != nil || current.Descriptor == nil || current.RegisteredBy == nil || current.RegisteredAt == nil ||
		*current.Descriptor != *registered.Descriptor || *current.RegisteredBy != *registered.RegisteredBy ||
		!current.RegisteredAt.Equal(*registered.RegisteredAt) {
		t.Fatal("schema migration changed the existing key descriptor")
	}
}
