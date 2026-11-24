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
	"strings"
	"testing"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
)

const (
	a3FixtureIssuer     = "https://a3-oidc.example.test"
	a3FixtureResource   = a3FixtureIssuer + "/a3-device-enrollment"
	a3FixtureClient     = "zapime-android-synthetic"
	a3FixtureSubject    = "a3-synthetic-user"
	a3FixtureAccount    = "98a01050-3185-4eed-bac4-127426c17372"
	a3ForegroundSubject = "a3-synthetic-foreground-owner"
)

// The isolated Android fixture uses the real account handler with a fresh
// Postgres schema, an actual provider-issued token and no data-transfer routes.
func TestProviderIssuedAccountLookup(t *testing.T) {
	if os.Getenv("ZAPIME_A3_ACCOUNT_LOOKUP") != "1" {
		t.Skip("driven by the isolated Android OIDC fixture")
	}
	var proof struct {
		AccessToken  string
		Keys         json.RawMessage
		PriorAccount string
	}
	reader := json.NewDecoder(io.LimitReader(os.Stdin, 32*1024))
	reader.DisallowUnknownFields()
	if reader.Decode(&proof) != nil || reader.Decode(new(any)) != io.EOF {
		t.Fatal("invalid synthetic account lookup input")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	var store *Store
	var api *API
	if proof.PriorAccount == "" {
		store, api = a3FixtureAccountAPI(t, ctx, proof.Keys)
	} else {
		store, api = a3FixtureSwitchAPI(t, ctx, proof.Keys, proof.PriorAccount)
	}
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
	var head, devices, keys int
	if err := store.DB.QueryRowContext(ctx, `SELECT revision,
		(SELECT count(*) FROM zapime_devices WHERE account_id=$1),
		(SELECT count(*) FROM zapime_account_keys WHERE account_id=$1)
		FROM zapime_accounts WHERE id=$1`, a3FixtureAccount).Scan(&head, &devices, &keys); err != nil || head != 0 || devices != 0 || keys != 0 {
		t.Fatal("read-only login changed the isolated account")
	}
	if proof.PriorAccount != "" {
		checkA3PriorAccount(t, ctx, store, proof.PriorAccount)
	}
	if _, err := os.Stdout.Write(response.Body.Bytes()); err != nil {
		t.Fatal("cannot return the public synthetic account")
	}
}

func a3FixtureAccountAPI(t *testing.T, ctx context.Context, keys []byte) (*Store, *API) {
	t.Helper()
	store := migrationDatabase(t)
	return bindA3FixtureAccount(t, ctx, store, keys)
}

func a3FixtureSwitchAPI(t *testing.T, ctx context.Context, keys []byte, prior string) (*Store, *API) {
	t.Helper()
	config, err := pgx.ParseConfig(os.Getenv("ZAPIME_TEST_DATABASE_URL"))
	repository := os.Getenv("GITHUB_REPOSITORY")
	if err != nil || config.Host != "localhost" || config.Port != 5432 || config.Database != "zapime_a3_test" ||
		len(config.Fallbacks) != 0 || repository == "" || os.Getenv("GITHUB_ACTIONS") != "true" ||
		os.Getenv("GITHUB_EVENT_NAME") != "pull_request" ||
		!strings.HasPrefix(os.Getenv("GITHUB_WORKFLOW_REF"), repository+"/.github/workflows/a3-android-oidc.yml@refs/pull/") ||
		!validID(prior) || prior == a3FixtureAccount {
		t.Fatal("account switch requires the dedicated A3 database and a distinct synthetic account")
	}
	db := stdlib.OpenDB(*config)
	t.Cleanup(func() { _ = db.Close() })
	store := &Store{DB: db}
	checkA3PriorAccount(t, ctx, store, prior)
	if err := store.BindOIDCSubject(ctx, prior, a3FixtureIssuer, a3ForegroundSubject); err != nil {
		t.Fatal("bind first subject in the isolated foreground database")
	}
	return bindA3FixtureAccount(t, ctx, store, keys)
}

func checkA3PriorAccount(t *testing.T, ctx context.Context, store *Store, account string) {
	t.Helper()
	var head, devices, keys int
	if err := store.DB.QueryRowContext(ctx, `SELECT revision,
		(SELECT count(*) FROM zapime_devices WHERE account_id=$1),
		(SELECT count(*) FROM zapime_account_keys WHERE account_id=$1)
		FROM zapime_accounts WHERE id=$1`, account).Scan(&head, &devices, &keys); err != nil || head != 5 || devices != 2 || keys != 1 {
		t.Fatal("the original synthetic account did not retain its data and device state")
	}
}

func bindA3FixtureAccount(t *testing.T, ctx context.Context, store *Store, keys []byte) (*Store, *API) {
	t.Helper()
	policy := oidcauth.AccessPolicy{Issuer: a3FixtureIssuer, Audience: a3FixtureResource, ClientID: a3FixtureClient,
		Scope: "zapime.device_enrollment", MaxLifetime: 2 * time.Minute}
	verifier, err := oidcauth.NewVerifier(policy, bytes.NewReader(keys))
	if err != nil {
		t.Fatal("invalid synthetic issuer keys")
	}
	if _, err := store.DB.ExecContext(ctx, "INSERT INTO zapime_accounts(id) VALUES($1)", a3FixtureAccount); err != nil {
		t.Fatal("create isolated synthetic account")
	}
	if err := store.BindOIDCSubject(ctx, a3FixtureAccount, a3FixtureIssuer, a3FixtureSubject); err != nil {
		t.Fatal("bind the isolated synthetic account")
	}
	return store, NewAPIWithOIDC(store, verifier)
}
