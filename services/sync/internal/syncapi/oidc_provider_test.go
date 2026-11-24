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

const (
	a3FixtureIssuer   = "https://a3-oidc.example.test"
	a3FixtureResource = a3FixtureIssuer + "/a3-device-enrollment"
	a3FixtureClient   = "zapime-android-synthetic"
	a3FixtureSubject  = "a3-synthetic-user"
	a3FixtureAccount  = "98a01050-3185-4eed-bac4-127426c17372"
)

// The isolated Android fixture uses the real account handler with a fresh
// Postgres schema, an actual provider-issued token and no data-transfer routes.
func TestProviderIssuedAccountLookup(t *testing.T) {
	if os.Getenv("ZAPIME_A3_ACCOUNT_LOOKUP") != "1" {
		t.Skip("driven by the isolated Android OIDC fixture")
	}
	var proof struct {
		AccessToken string
		Keys        json.RawMessage
	}
	reader := json.NewDecoder(io.LimitReader(os.Stdin, 32*1024))
	reader.DisallowUnknownFields()
	if reader.Decode(&proof) != nil || reader.Decode(new(any)) != io.EOF {
		t.Fatal("invalid synthetic account lookup input")
	}
	ctx := context.Background()
	store, api := a3FixtureAccountAPI(t, ctx, proof.Keys)
	request := httptest.NewRequest(http.MethodGet, "/v1/account-login", nil)
	request.Header.Set("Authorization", "Bearer "+proof.AccessToken)
	response := httptest.NewRecorder()
	api.ServeHTTP(response, request)
	var session accountLoginIdentity
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &session) != nil ||
		session.Protocol != 1 || session.AccountID != a3FixtureAccount ||
		session.SubjectFingerprint != accountLoginFingerprint(a3FixtureIssuer, a3FixtureSubject, a3FixtureResource, a3FixtureAccount) {
		t.Fatal("provider-issued identity did not resolve to the bound account")
	}
	var head, devices int
	if err := store.DB.QueryRowContext(ctx, `SELECT revision, (SELECT count(*) FROM zapime_devices WHERE account_id=$1) FROM zapime_accounts WHERE id=$1`,
		a3FixtureAccount).Scan(&head, &devices); err != nil || head != 0 || devices != 0 {
		t.Fatal("read-only login changed the isolated account")
	}
	if _, err := os.Stdout.Write(response.Body.Bytes()); err != nil {
		t.Fatal("cannot return the public synthetic account")
	}
}

func a3FixtureAccountAPI(t *testing.T, ctx context.Context, keys []byte) (*Store, *API) {
	t.Helper()
	policy := oidcauth.AccessPolicy{Issuer: a3FixtureIssuer, Audience: a3FixtureResource, ClientID: a3FixtureClient,
		Scope: "zapime.device_enrollment", MaxLifetime: 2 * time.Minute}
	verifier, err := oidcauth.NewVerifier(policy, bytes.NewReader(keys))
	if err != nil {
		t.Fatal("invalid synthetic issuer keys")
	}
	store := migrationDatabase(t)
	if _, err := store.DB.ExecContext(ctx, "INSERT INTO zapime_accounts(id) VALUES($1)", a3FixtureAccount); err != nil {
		t.Fatal("create isolated synthetic account")
	}
	if err := store.BindOIDCSubject(ctx, a3FixtureAccount, a3FixtureIssuer, a3FixtureSubject); err != nil {
		t.Fatal("bind the isolated synthetic account")
	}
	return store, NewAPIWithOIDC(store, verifier)
}
