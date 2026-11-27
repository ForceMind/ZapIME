// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

func TestConsoleConfigurationAndSecureCookie(t *testing.T) {
	for _, name := range []string{"ZAPIME_CONSOLE_ORIGIN", "ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT", "ZAPIME_CONSOLE_TOKEN_ENDPOINT", "ZAPIME_CONSOLE_CLIENT_SECRET", "ZAPIME_CONSOLE_ADMINS", "ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT", "ZAPIME_CONSOLE_PASSWORD_RESET_TOKEN", "ZAPIME_OIDC_CLIENT_ID", "ZAPIME_OIDC_AUDIENCE"} {
		t.Setenv(name, "")
	}
	if c, err := NewConsoleFromEnv(nil, nil); c != nil || err != nil {
		t.Fatal("disabled console rejected", err)
	}
	t.Setenv("ZAPIME_CONSOLE_ORIGIN", "https://sync.example.test")
	t.Setenv("ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT", "https://login.example.test/authorize")
	t.Setenv("ZAPIME_CONSOLE_TOKEN_ENDPOINT", "https://login.example.test/token")
	t.Setenv("ZAPIME_OIDC_CLIENT_ID", "synthetic-browser")
	t.Setenv("ZAPIME_OIDC_AUDIENCE", "https://sync.example.test/device-enrollment")
	t.Setenv("ZAPIME_CONSOLE_ADMINS", `[{"issuer":"https://login.example.test","subject":"synthetic-admin"}]`)
	if _, err := NewConsoleFromEnv(nil, nil); err == nil {
		t.Fatal("enabled console without verifier accepted")
	}
	c, err := NewConsoleFromEnv(nil, new(oidcauth.Verifier))
	if err != nil || !c.Admins["https://login.example.test\x00synthetic-admin"] {
		t.Fatal("valid operator policy rejected", err)
	}
	t.Setenv("ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT", "https://login.example.test/reset")
	if _, err := NewConsoleFromEnv(nil, new(oidcauth.Verifier)); err == nil {
		t.Fatal("reset adapter without secret accepted")
	}
	t.Setenv("ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT", "")
	for _, origin := range []string{"http://sync.example.test", "https://sync.example.test/path", "https://sync.example.test?token=secret", "https://user:secret@sync.example.test"} {
		t.Setenv("ZAPIME_CONSOLE_ORIGIN", origin)
		if _, err := NewConsoleFromEnv(nil, new(oidcauth.Verifier)); err == nil {
			t.Fatal("unsafe origin accepted")
		}
	}
	w := httptest.NewRecorder()
	consoleSetCookie(w, consoleCookie, "synthetic-session", 1800)
	cookies := w.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || !cookies[0].Secure || cookies[0].SameSite != http.SameSiteLaxMode || cookies[0].Path != "/" || cookies[0].Domain != "" {
		t.Fatal("session cookie is not host-bound and protected")
	}
	api := &API{Console: c}
	for _, path := range []string{"/console/auth/login?return_to=https://other.example.test", "/console/auth/login?return_to=admin&return_to=admin", "/console/auth/login?return_to=admin&other=value", "/console/auth/login?return_to=user"} {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		reply := httptest.NewRecorder()
		api.serveConsoleAuth(context.Background(), reply, r)
		if reply.Code != 400 {
			t.Fatal("unapproved login destination accepted", reply.Code)
		}
	}
}

func TestPostgresConsoleSchema5MigrationPreservesHistory(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	_, actor := provision(t, s)
	before, err := s.Push(ctx, actor, operation(t))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.DB.Exec(`DROP TABLE zapime_console_sessions,zapime_console_logins,zapime_console_audit,zapime_console_keys; ALTER TABLE zapime_accounts DROP COLUMN disabled_at; UPDATE zapime_schema SET version=5`); err != nil {
		t.Fatal(err)
	}
	if err = s.Migrate(ctx); err != nil {
		t.Fatal("schema5 upgrade failed", err)
	}
	if err = s.Migrate(ctx); err != nil {
		t.Fatal("schema6 migration is not idempotent", err)
	}
	page, err := s.Pull(ctx, actor, 0, 1)
	if err != nil || len(page.Changes) != 1 || page.Changes[0] != before {
		t.Fatal("schema6 changed immutable history", err)
	}
}

func TestPostgresConsoleAuditFailureRollsBackBan(t *testing.T) {
	api, sign := consoleTestAPI(t)
	_, cookie, csrf, _ := consoleTestLogin(t, api, sign, "audit-admin", true)
	target, _, _, _ := consoleTestLogin(t, api, sign, "audit-target", false)
	if _, err := api.Store.DB.Exec(`DROP TABLE zapime_console_audit`); err != nil {
		t.Fatal(err)
	}
	w := consoleAdminCall(api, cookie, csrf, "/console/api/admin/accounts/"+target.AccountID+"/ban")
	if w.Code != 503 {
		t.Fatal("audit failure did not reject ban", w.Code)
	}
	if _, err := api.Store.Authenticate(context.Background(), target.Token); err != nil {
		t.Fatal("unaudited ban was committed", err)
	}
}

func consoleTestLogin(t *testing.T, api *API, sign func(string) string, subject string, admin bool) (Credential, *http.Cookie, string, ConsoleActor) {
	t.Helper()
	ctx := context.Background()
	credential, _ := provision(t, api.Store)
	if err := api.Store.BindOIDCSubject(ctx, credential.AccountID, enrollmentIssuer, subject); err != nil {
		t.Fatal(err)
	}
	proof, err := api.oidc.Verify(sign(subject), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	session, err := api.Store.createConsoleSession(ctx, proof)
	if err != nil {
		t.Fatal(err)
	}
	if admin {
		api.Console.Admins[enrollmentIssuer+"\x00"+subject] = true
	}
	cookie := &http.Cookie{Name: consoleCookie, Value: session}
	r := httptest.NewRequest(http.MethodGet, "/console/api/session", nil)
	r.AddCookie(cookie)
	w := httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	var body struct {
		CSRF string `json:"csrf_token"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &body) != nil || body.CSRF == "" {
		t.Fatal("session unavailable", w.Code, w.Body.String())
	}
	actor, err := api.consoleActor(ctx, w, r, false)
	if err != nil {
		t.Fatal(err)
	}
	return credential, cookie, body.CSRF, actor
}
func consoleTestAPI(t *testing.T) (*API, func(string) string) {
	t.Helper()
	api, sign := enrollmentTestAPI(t, migrationDatabase(t))
	api.Console = &Console{Origin: "https://sync.example.test", ClientID: enrollmentClient, Audience: enrollmentResource, Verifier: api.oidc, Admins: map[string]bool{}, Client: &http.Client{Timeout: time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	return api, sign
}
func consoleAdminCall(api *API, cookie *http.Cookie, csrf, path string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, path, strings.NewReader("{}"))
	r.AddCookie(cookie)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Origin", api.Console.Origin)
	r.Header.Set("X-CSRF-Token", csrf)
	var account string
	_ = api.Store.DB.QueryRow(`SELECT account_id::text FROM zapime_console_sessions WHERE session_hash=$1`, consoleHash(cookie.Value)).Scan(&account)
	r.Header.Set("X-Console-Account-ID", account)
	w := httptest.NewRecorder()
	api.serveConsoleAdmin(context.Background(), w, r)
	return w
}

func TestPostgresConsoleBanAllPathsAndUnban(t *testing.T) {
	api, sign := consoleTestAPI(t)
	ctx := context.Background()
	_, adminCookie, csrf, _ := consoleTestLogin(t, api, sign, "console-admin", true)
	user, userCookie, _, userActor := consoleTestLogin(t, api, sign, "console-user", false)
	owner, err := api.Store.Authenticate(ctx, user.Token)
	if err != nil {
		t.Fatal(err)
	}
	proof, err := api.oidc.Verify(sign("console-user"), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	w := consoleAdminCall(api, adminCookie, "wrong", "/console/api/admin/accounts/"+user.AccountID+"/ban")
	if w.Code != 403 {
		t.Fatal("missing CSRF accepted", w.Code)
	}
	w = consoleAdminCall(api, adminCookie, csrf, "/console/api/admin/accounts/"+user.AccountID+"/ban")
	if w.Code != 200 {
		t.Fatal("ban failed", w.Code, w.Body.String())
	}
	checks := map[string]func() error{
		"authenticate":        func() error { _, e := api.Store.Authenticate(ctx, user.Token); return e },
		"push stale identity": func() error { _, e := api.Store.Push(ctx, owner, operation(t)); return e },
		"pull stale identity": func() error { _, e := api.Store.Pull(ctx, owner, 0, 1); return e },
		"devices":             func() error { _, e := api.Store.Devices(ctx, owner); return e },
		"add device":          func() error { _, e := api.Store.AddDevice(ctx, owner, "synthetic"); return e },
		"revoke":              func() error { return api.Store.Revoke(ctx, owner, owner.DeviceID) },
		"descriptor":          func() error { _, e := api.Store.KeyDescriptor(ctx, owner); return e },
		"OIDC resolve":        func() error { _, e := api.Store.ResolveOIDCAccount(ctx, proof); return e },
		"OIDC enrollment": func() error {
			tx, e := api.Store.lockEnrollment(ctx, user.AccountID, proof)
			if tx != nil {
				tx.Rollback()
			}
			return e
		},
		"stale browser": func() error {
			tx, _, e := api.consoleLock(ctx, userActor, user.AccountID, true)
			if tx != nil {
				tx.Rollback()
			}
			return e
		},
	}
	for name, check := range checks {
		t.Run(name, func(t *testing.T) {
			if err := check(); !errors.Is(err, ErrUnauthorized) {
				t.Fatal("disabled account not denied", err)
			}
		})
	}
	r := httptest.NewRequest(http.MethodGet, "/console/api/session", nil)
	r.AddCookie(userCookie)
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 401 {
		t.Fatal("old browser session survived ban")
	}
	w = consoleAdminCall(api, adminCookie, csrf, "/console/api/admin/accounts/"+user.AccountID+"/unban")
	if w.Code != 200 {
		t.Fatal("unban failed", w.Code)
	}
	if _, err = api.Store.Authenticate(ctx, user.Token); err != nil {
		t.Fatal("unban did not restore device eligibility", err)
	}
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 401 {
		t.Fatal("unban silently restored old browser session")
	}
	var audit int
	if err = api.Store.DB.QueryRow(`SELECT count(*) FROM zapime_console_audit WHERE target_account_id=$1 AND action IN ('account.ban','account.unban')`, user.AccountID).Scan(&audit); err != nil || audit != 2 {
		t.Fatal("missing transaction audit", audit, err)
	}
}

func TestPostgresConsoleAccountIsolationAndSessionRevocation(t *testing.T) {
	api, sign := consoleTestAPI(t)
	_, cookie, csrf, actor := consoleTestLogin(t, api, sign, "console-ordinary", false)
	other, _ := provision(t, api.Store)
	ctx := context.Background()
	stale := httptest.NewRequest(http.MethodGet, "/console/api/admin/accounts", nil)
	stale.AddCookie(cookie)
	stale.Header.Set("X-Console-Account-ID", other.AccountID)
	if _, err := api.consoleActor(ctx, httptest.NewRecorder(), stale, false); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("stale tab account selector accepted", err)
	}
	actor.Admin = true
	tx, _, err := api.consoleLock(ctx, actor, other.AccountID, false)
	if tx != nil {
		tx.Rollback()
	}
	if !errors.Is(err, ErrForbidden) {
		t.Fatal("caller-forged admin role accepted", err)
	}
	r := httptest.NewRequest(http.MethodGet, "/console/api/admin/accounts", nil)
	r.AddCookie(cookie)
	r.Header.Set("X-Console-Account-ID", actor.AccountID)
	w := httptest.NewRecorder()
	api.serveConsoleAdmin(ctx, w, r)
	if w.Code != 403 {
		t.Fatal("ordinary user listed directory", w.Code)
	}
	actor.Device.DeviceID = id(t)
	tx, _, err = api.consoleLock(ctx, actor, actor.AccountID, true)
	if tx != nil {
		tx.Rollback()
	}
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatal("substituted browser device accepted", err)
	}
	r = httptest.NewRequest(http.MethodPost, "/console/auth/logout", nil)
	r.AddCookie(cookie)
	r.Header.Set("Origin", api.Console.Origin)
	r.Header.Set("X-CSRF-Token", csrf)
	r.Header.Set("X-Console-Account-ID", actor.AccountID)
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 200 {
		t.Fatal("logout failed", w.Code)
	}
	r = httptest.NewRequest(http.MethodGet, "/console/api/session", nil)
	r.AddCookie(cookie)
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 401 {
		t.Fatal("logout did not revoke server session")
	}
}

func TestPostgresConsoleOAuthStatePKCEAndCodeReplay(t *testing.T) {
	api, sign := consoleTestAPI(t)
	ctx := context.Background()
	c, _ := provision(t, api.Store)
	if err := api.Store.BindOIDCSubject(ctx, c.AccountID, enrollmentIssuer, "browser-login"); err != nil {
		t.Fatal(err)
	}
	var challenge string
	calls := 0
	access := sign("browser-login")
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Method != "POST" || r.ParseForm() != nil || r.Form.Get("grant_type") != "authorization_code" || r.Form.Get("redirect_uri") != api.Console.Origin+"/console/auth/callback" || r.Form.Get("client_id") != enrollmentClient || r.Form.Get("code") != "synthetic-code" || base64URLHash(r.Form.Get("code_verifier")) != challenge {
			t.Error("invalid PKCE exchange")
			w.WriteHeader(400)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"access_token": access, "token_type": "Bearer"})
	}))
	defer provider.Close()
	api.Console.Client = provider.Client()
	api.Console.TokenEndpoint = provider.URL
	api.Console.AuthorizationEndpoint = "https://login.example.test/authorize"
	r := httptest.NewRequest(http.MethodGet, "/console/auth/login?return_to=admin", nil)
	w := httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 302 {
		t.Fatal("login not redirected", w.Code)
	}
	u, err := url.Parse(w.Header().Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	state := u.Query().Get("state")
	challenge = u.Query().Get("code_challenge")
	if challenge == "" || u.Query().Get("code_challenge_method") != "S256" {
		t.Fatal("missing PKCE")
	}
	cookies := w.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].Secure || !cookies[0].HttpOnly {
		t.Fatal("state cookie insecure")
	}
	path := "/console/auth/callback?state=" + state + "&code=synthetic-code"
	r = httptest.NewRequest(http.MethodGet, path, nil)
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 401 || calls != 0 {
		t.Fatal("callback without browser state accepted")
	}
	r = httptest.NewRequest(http.MethodGet, path, nil)
	r.AddCookie(cookies[0])
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 303 || calls != 1 || w.Header().Get("Location") != "/console/admin/" {
		t.Fatal("valid code rejected", w.Code, w.Body.String())
	}
	if strings.Contains(w.Body.String(), access) || strings.Contains(w.Header().Get("Location"), access) {
		t.Fatal("access token leaked to browser")
	}
	r = httptest.NewRequest(http.MethodGet, path, nil)
	r.AddCookie(cookies[0])
	w = httptest.NewRecorder()
	api.serveConsoleAuth(ctx, w, r)
	if w.Code != 401 || calls != 1 {
		t.Fatal("authorization code/state replay accepted")
	}
}

func base64URLHash(value string) string {
	return base64.RawURLEncoding.EncodeToString(consoleHash(value))
}

func TestPostgresConsolePasswordResetAdapter(t *testing.T) {
	api, sign := consoleTestAPI(t)
	_, cookie, csrf, _ := consoleTestLogin(t, api, sign, "reset-admin", true)
	target, _, _, _ := consoleTestLogin(t, api, sign, "reset-target", false)
	path := "/console/api/admin/accounts/" + target.AccountID + "/password-reset"
	if w := consoleAdminCall(api, cookie, csrf, path); w.Code != 501 {
		t.Fatal("unconfigured provider presented as supported", w.Code)
	}
	calls := 0
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		var input map[string]string
		if r.Header.Get("Authorization") != "Bearer synthetic-provider-secret" || json.NewDecoder(r.Body).Decode(&input) != nil || input["account_id"] != target.AccountID || input["subject"] != "reset-target" || !validID(input["request_id"]) || input["request_id"] != r.Header.Get("Idempotency-Key") {
			t.Error("incorrect provider scope")
		}
		w.WriteHeader(202)
	}))
	defer provider.Close()
	api.Console.Client = provider.Client()
	api.Console.PasswordResetEndpoint = provider.URL
	api.Console.PasswordResetToken = "synthetic-provider-secret"
	w := consoleAdminCall(api, cookie, csrf, path)
	if w.Code != 202 || calls != 1 || strings.Contains(w.Body.String(), "secret") {
		t.Fatal("reset adapter did not accept exactly once", w.Code, calls)
	}
	var count int
	if err := api.Store.DB.QueryRow(`SELECT count(*) FROM zapime_console_audit WHERE target_account_id=$1 AND action LIKE 'password_reset.%'`, target.AccountID).Scan(&count); err != nil || count != 2 {
		t.Fatal("reset intent/result audit missing", count, err)
	}
	if err := api.Store.DB.QueryRow(`SELECT count(DISTINCT request_id) FROM zapime_console_audit WHERE target_account_id=$1 AND action LIKE 'password_reset.%'`, target.AccountID).Scan(&count); err != nil || count != 1 {
		t.Fatal("reset outcome cannot be correlated to its provider request", count, err)
	}
}
