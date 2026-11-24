// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

// The isolated Android fixture uses the real account handler with a fresh
// Postgres schema, an actual provider-issued token and no data-transfer routes.
func TestProviderIssuedAccountLookup(t *testing.T) {
	if os.Getenv("ZAPIME_A3_ACCOUNT_LOOKUP") != "1" {
		t.Skip("driven by the isolated Android OIDC fixture")
	}
	const issuer = "https://a3-oidc.example.test"
	const resource = issuer + "/a3-device-enrollment"
	const client = "zapime-android-synthetic"
	const subject = "a3-synthetic-user"
	const account = "98a01050-3185-4eed-bac4-127426c17372"
	var proof struct {
		AccessToken string
		Keys        json.RawMessage
	}
	reader := json.NewDecoder(io.LimitReader(os.Stdin, 32*1024))
	reader.DisallowUnknownFields()
	if reader.Decode(&proof) != nil || reader.Decode(new(any)) != io.EOF {
		t.Fatal("invalid synthetic account lookup input")
	}
	policy := oidcauth.AccessPolicy{Issuer: issuer, Audience: resource, ClientID: client,
		Scope: "zapime.device_enrollment", MaxLifetime: 2 * time.Minute}
	verifier, err := oidcauth.NewVerifier(policy, bytes.NewReader(proof.Keys))
	if err != nil {
		t.Fatal("invalid synthetic issuer keys")
	}
	store := migrationDatabase(t)
	ctx := context.Background()
	if _, err := store.DB.ExecContext(ctx, "INSERT INTO zapime_accounts(id) VALUES($1)", account); err != nil {
		t.Fatal("create isolated synthetic account")
	}
	if err := store.BindOIDCSubject(ctx, account, issuer, subject); err != nil {
		t.Fatal("bind the isolated synthetic account")
	}
	request := httptest.NewRequest(http.MethodGet, "/v1/account-login", nil)
	request.Header.Set("Authorization", "Bearer "+proof.AccessToken)
	response := httptest.NewRecorder()
	NewAPIWithOIDC(store, verifier).ServeHTTP(response, request)
	var session accountLoginIdentity
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &session) != nil ||
		session.Protocol != 1 || session.AccountID != account ||
		session.SubjectFingerprint != accountLoginFingerprint(issuer, subject, resource, account) {
		t.Fatal("provider-issued identity did not resolve to the bound account")
	}
	var head, devices int
	if err := store.DB.QueryRowContext(ctx, `SELECT revision, (SELECT count(*) FROM zapime_devices WHERE account_id=$1) FROM zapime_accounts WHERE id=$1`,
		account).Scan(&head, &devices); err != nil || head != 0 || devices != 0 {
		t.Fatal("read-only login changed the isolated account")
	}
	if _, err := os.Stdout.Write(response.Body.Bytes()); err != nil {
		t.Fatal("cannot return the public synthetic account")
	}
}
