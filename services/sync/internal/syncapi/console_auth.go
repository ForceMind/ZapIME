// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

const consoleCookie = "__Host-zapime-console"
const consoleStateCookie = "__Host-zapime-console-state"

type Console struct {
	Origin, AuthorizationEndpoint, TokenEndpoint, ClientID, ClientSecret, Audience string
	PasswordResetEndpoint, PasswordResetToken                                      string
	Admins                                                                         map[string]bool
	Verifier                                                                       *oidcauth.Verifier
	Client                                                                         *http.Client
}

type ConsoleActor struct {
	AccountID, Issuer, Subject string
	Admin                      bool
	Device                     Identity
	sessionHash                []byte
}

func consoleHTTPS(value string) bool {
	u, err := url.Parse(value)
	return err == nil && u.Scheme == "https" && u.Hostname() != "" && u.User == nil && u.RawQuery == "" && !u.ForceQuery && u.Fragment == ""
}

// Console is opt-in. The browser client must match the pinned verifier policy.
func NewConsoleFromEnv(_ *Store, verifier *oidcauth.Verifier) (*Console, error) {
	origin := os.Getenv("ZAPIME_CONSOLE_ORIGIN")
	if origin == "" {
		return nil, nil
	}
	c := &Console{Origin: origin, AuthorizationEndpoint: os.Getenv("ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT"), TokenEndpoint: os.Getenv("ZAPIME_CONSOLE_TOKEN_ENDPOINT"), ClientID: os.Getenv("ZAPIME_OIDC_CLIENT_ID"), ClientSecret: os.Getenv("ZAPIME_CONSOLE_CLIENT_SECRET"), Audience: os.Getenv("ZAPIME_OIDC_AUDIENCE"), Verifier: verifier, Admins: make(map[string]bool), PasswordResetEndpoint: os.Getenv("ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT"), PasswordResetToken: os.Getenv("ZAPIME_CONSOLE_PASSWORD_RESET_TOKEN")}
	u, err := url.Parse(origin)
	if err != nil || !consoleHTTPS(origin) || u.Path != "" || verifier == nil || c.ClientID == "" || !consoleHTTPS(c.Audience) || !consoleHTTPS(c.AuthorizationEndpoint) || !consoleHTTPS(c.TokenEndpoint) || (c.PasswordResetEndpoint == "") != (c.PasswordResetToken == "") || (c.PasswordResetEndpoint != "" && !consoleHTTPS(c.PasswordResetEndpoint)) {
		return nil, oidcauth.ErrConfiguration
	}
	// Exact issuer/subject pairs, never an email, browser claim, or account role.
	var admins []struct {
		Issuer  string `json:"issuer"`
		Subject string `json:"subject"`
	}
	if raw := os.Getenv("ZAPIME_CONSOLE_ADMINS"); raw != "" {
		d := json.NewDecoder(strings.NewReader(raw))
		d.DisallowUnknownFields()
		if d.Decode(&admins) != nil || d.Decode(new(any)) != io.EOF {
			return nil, oidcauth.ErrConfiguration
		}
	}
	for _, a := range admins {
		if !oidcauth.ValidSubject(a.Issuer, a.Subject) {
			return nil, oidcauth.ErrConfiguration
		}
		c.Admins[a.Issuer+"\x00"+a.Subject] = true
	}
	c.Client = &http.Client{Timeout: 8 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	return c, nil
}

func randomConsoleSecret() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
func consoleHash(value string) []byte { h := sha256.Sum256([]byte(value)); return h[:] }
func consoleCSRF(session string) string {
	h := enrollmentHash("zapime-console-csrf-v1", session)
	return base64.RawURLEncoding.EncodeToString(h[:])
}
func consoleSetCookie(w http.ResponseWriter, name, value string, seconds int) {
	http.SetCookie(w, &http.Cookie{Name: name, Value: value, Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode, MaxAge: seconds})
}
func consoleNoBody(r *http.Request) bool { return r.ContentLength == 0 && len(r.TransferEncoding) == 0 }

func migrateConsole(ctx context.Context, tx *sql.Tx) error {
	_, err := tx.ExecContext(ctx, `ALTER TABLE zapime_accounts ADD COLUMN IF NOT EXISTS disabled_at timestamptz;
	CREATE TABLE IF NOT EXISTS zapime_console_sessions(
	 session_hash bytea PRIMARY KEY CHECK(octet_length(session_hash)=32), csrf_hash bytea NOT NULL CHECK(octet_length(csrf_hash)=32),
	 account_id uuid NOT NULL REFERENCES zapime_accounts(id), device_id uuid NOT NULL REFERENCES zapime_devices(id), device_token_hash bytea NOT NULL,
	 issuer text NOT NULL,subject text NOT NULL,expires_at timestamptz NOT NULL);
	CREATE INDEX IF NOT EXISTS zapime_console_session_expiry ON zapime_console_sessions(expires_at);
	CREATE TABLE IF NOT EXISTS zapime_console_logins(
	 state_hash bytea PRIMARY KEY CHECK(octet_length(state_hash)=32),browser_hash bytea NOT NULL CHECK(octet_length(browser_hash)=32),verifier text NOT NULL,admin_return boolean NOT NULL DEFAULT false,expires_at timestamptz NOT NULL);
	ALTER TABLE zapime_console_logins ADD COLUMN IF NOT EXISTS admin_return boolean NOT NULL DEFAULT false;
	CREATE TABLE IF NOT EXISTS zapime_console_audit(
	 id bigserial PRIMARY KEY,actor_account_id uuid NOT NULL REFERENCES zapime_accounts(id),actor_issuer text NOT NULL,actor_subject text NOT NULL,
	 target_account_id uuid NOT NULL REFERENCES zapime_accounts(id),action text NOT NULL,request_id uuid,created_at timestamptz NOT NULL DEFAULT now());
	ALTER TABLE zapime_console_audit ADD COLUMN IF NOT EXISTS request_id uuid;
	CREATE INDEX IF NOT EXISTS zapime_console_audit_target ON zapime_console_audit(target_account_id,id);`)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, consoleRecordsSchema)
	return err
}

func (api *API) serveConsoleAuth(ctx context.Context, w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/console/auth/") && r.URL.Path != "/console/api/session" {
		return false
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	if api.Console == nil {
		write(w, 404, map[string]string{"error": "not_found"})
		return true
	}
	switch r.URL.Path {
	case "/console/auth/login":
		q, err := url.ParseQuery(r.URL.RawQuery)
		if r.Method != http.MethodGet || !consoleNoBody(r) || err != nil || len(q) > 1 || (len(q) == 1 && (len(q["return_to"]) != 1 || q.Get("return_to") != "admin")) {
			failure(w, ErrInvalid)
			break
		}
		if err := api.consoleBeginLogin(ctx, w, r); err != nil {
			failure(w, err)
		}
	case "/console/auth/callback":
		if r.Method != http.MethodGet || !consoleNoBody(r) {
			failure(w, ErrInvalid)
			break
		}
		if err := api.consoleFinishLogin(ctx, w, r); err != nil {
			failure(w, err)
		}
	case "/console/api/session":
		if r.Method != http.MethodGet || r.URL.RawQuery != "" || !consoleNoBody(r) {
			failure(w, ErrInvalid)
			break
		}
		actor, err := api.consoleActor(ctx, w, r, false)
		if err != nil {
			failure(w, err)
			break
		}
		// A session-stable, purpose-separated nonce keeps other open tabs valid.
		// Neither it nor the HttpOnly session credential is placed in a URL.
		cookie, _ := r.Cookie(consoleCookie)
		csrf := consoleCSRF(cookie.Value)
		reason := ""
		if api.Console.PasswordResetEndpoint == "" {
			reason = "password_reset_provider_not_configured"
		}
		write(w, 200, map[string]any{"account_id": actor.AccountID, "display_name": actor.AccountID, "admin": actor.Admin, "csrf_token": csrf, "password_reset_available": api.Console.PasswordResetEndpoint != "", "password_reset_reason": reason})
	case "/console/auth/logout":
		actor, err := api.consoleActor(ctx, w, r, true)
		if r.Method != http.MethodPost {
			err = ErrInvalid
		}
		if err != nil {
			failure(w, err)
			break
		}
		tx, _, err := api.consoleLock(ctx, actor, actor.AccountID, true)
		if err != nil {
			failure(w, err)
			break
		}
		defer tx.Rollback()
		_, err = tx.ExecContext(ctx, `UPDATE zapime_devices SET revoked_at=COALESCE(revoked_at,now()) WHERE id=$1 AND account_id=$2`, actor.Device.DeviceID, actor.AccountID)
		if err == nil {
			_, err = tx.ExecContext(ctx, `DELETE FROM zapime_console_sessions WHERE session_hash=$1`, actor.sessionHash)
		}
		if err == nil {
			err = tx.Commit()
		}
		if err != nil {
			failure(w, err)
			break
		}
		consoleSetCookie(w, consoleCookie, "", -1)
		write(w, 200, map[string]bool{"logged_out": true})
	default:
		write(w, 404, map[string]string{"error": "not_found"})
	}
	return true
}

func (api *API) consoleBeginLogin(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	state, err := randomConsoleSecret()
	if err != nil {
		return err
	}
	browser, err := randomConsoleSecret()
	if err != nil {
		return err
	}
	verifier, err := randomConsoleSecret()
	if err != nil {
		return err
	}
	tx, err := api.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(7442003); DELETE FROM zapime_console_logins WHERE expires_at<=now()`); err != nil {
		return err
	}
	var count int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM zapime_console_logins`).Scan(&count); err != nil {
		return err
	}
	if count >= 1024 {
		return ErrLimit
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_console_logins(state_hash,browser_hash,verifier,admin_return,expires_at) VALUES($1,$2,$3,$4,now()+interval '5 minutes')`, consoleHash(state), consoleHash(browser), verifier, r.URL.Query().Get("return_to") == "admin"); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	c := api.Console
	u, _ := url.Parse(c.AuthorizationEndpoint)
	q := u.Query()
	q.Set("response_type", "code")
	q.Set("client_id", c.ClientID)
	q.Set("redirect_uri", c.Origin+"/console/auth/callback")
	q.Set("scope", "openid zapime.device_enrollment")
	q.Set("resource", c.Audience)
	q.Set("state", state)
	q.Set("code_challenge_method", "S256")
	q.Set("code_challenge", base64.RawURLEncoding.EncodeToString(consoleHash(verifier)))
	u.RawQuery = q.Encode()
	consoleSetCookie(w, consoleStateCookie, browser, 300)
	http.Redirect(w, r, u.String(), http.StatusFound)
	return nil
}

func (api *API) consoleFinishLogin(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	if len(q["state"]) != 1 || len(q["code"]) != 1 || len(q["error"]) != 0 || len(q.Get("code")) > 2048 || q.Get("code") == "" {
		return ErrUnauthorized
	}
	browser, err := r.Cookie(consoleStateCookie)
	if err != nil || len(browser.Value) != 43 || len(q.Get("state")) != 43 {
		return ErrUnauthorized
	}
	var verifier string
	var adminReturn bool
	err = api.Store.DB.QueryRowContext(ctx, `DELETE FROM zapime_console_logins WHERE state_hash=$1 AND browser_hash=$2 AND expires_at>now() RETURNING verifier,admin_return`, consoleHash(q.Get("state")), consoleHash(browser.Value)).Scan(&verifier, &adminReturn)
	consoleSetCookie(w, consoleStateCookie, "", -1)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrUnauthorized
	}
	if err != nil {
		return err
	}
	c := api.Console
	form := url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {q.Get("code")},
		"redirect_uri":  {c.Origin + "/console/auth/callback"},
		"client_id":     {c.ClientID},
		"resource":      {c.Audience},
		"code_verifier": {verifier},
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.TokenEndpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return ErrUnauthorized
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if c.ClientSecret != "" {
		request.SetBasicAuth(c.ClientID, c.ClientSecret)
	}
	response, err := c.Client.Do(request)
	if err != nil {
		return ErrUnauthorized
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return ErrUnauthorized
	}
	var token struct {
		AccessToken string `json:"access_token"`
		TokenType   string `json:"token_type"`
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 32769))
	if err != nil || len(data) > 32768 || json.Unmarshal(data, &token) != nil || !strings.EqualFold(token.TokenType, "Bearer") {
		return ErrUnauthorized
	}
	proof, err := c.Verifier.Verify(token.AccessToken, time.Now())
	if err != nil {
		return ErrUnauthorized
	}
	session, err := api.Store.createConsoleSession(ctx, proof)
	if err != nil {
		return err
	}
	consoleSetCookie(w, consoleCookie, session, 1800)
	destination := "/console/"
	if adminReturn {
		destination = "/console/admin/"
	}
	http.Redirect(w, r, destination, http.StatusSeeOther)
	return nil
}

func (s *Store) createConsoleSession(ctx context.Context, proof oidcauth.Subject) (string, error) {
	account, err := s.ResolveOIDCAccount(ctx, proof)
	if err != nil {
		return "", err
	}
	tx, err := s.lockEnrollment(ctx, account, proof)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	session, err := randomConsoleSecret()
	if err != nil {
		return "", err
	}
	csrf := consoleCSRF(session)
	device, err := UUID()
	if err != nil {
		return "", err
	}
	token, err := randomConsoleSecret()
	if err != nil {
		return "", err
	}
	issuer, subject, _, _ := proof.Claims()
	// Expired browser identities cannot accumulate indefinitely per account.
	if _, err = tx.ExecContext(ctx, `DELETE FROM zapime_console_sessions WHERE account_id=$1 AND expires_at<=now()`, account); err != nil {
		return "", err
	}
	var count int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM zapime_console_sessions WHERE account_id=$1`, account).Scan(&count); err != nil {
		return "", err
	}
	if count >= 16 {
		return "", ErrLimit
	}
	if err = requireEnrollmentCapacity(ctx, tx, account); err != nil {
		return "", err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_devices(id,account_id,name,role,token_hash,expires_at) VALUES($1,$2,'Browser console','member',$3,now()+interval '30 minutes')`, device, account, consoleHash(token)); err != nil {
		return "", err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_console_sessions(session_hash,csrf_hash,account_id,device_id,device_token_hash,issuer,subject,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,now()+interval '30 minutes')`, consoleHash(session), consoleHash(csrf), account, device, consoleHash(token), issuer, subject); err != nil {
		return "", err
	}
	return session, tx.Commit()
}

func (api *API) consoleActor(ctx context.Context, _ http.ResponseWriter, r *http.Request, mutation bool) (ConsoleActor, error) {
	var actor ConsoleActor
	if api.Console == nil {
		return actor, ErrUnauthorized
	}
	cookie, err := r.Cookie(consoleCookie)
	if err != nil || len(cookie.Value) != 43 {
		return actor, ErrUnauthorized
	}
	actor.sessionHash = consoleHash(cookie.Value)
	var csrf []byte
	err = api.Store.DB.QueryRowContext(ctx, `SELECT s.account_id::text,s.issuer,s.subject,s.device_id::text,s.device_token_hash,s.expires_at,s.csrf_hash FROM zapime_console_sessions s JOIN zapime_accounts a ON a.id=s.account_id JOIN zapime_oidc_subjects b ON b.account_id=s.account_id AND b.issuer=s.issuer AND b.subject=s.subject JOIN zapime_devices d ON d.id=s.device_id AND d.account_id=s.account_id AND d.token_hash=s.device_token_hash AND d.role='member' WHERE s.session_hash=$1 AND s.expires_at>now() AND a.disabled_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>now()`, actor.sessionHash).Scan(&actor.AccountID, &actor.Issuer, &actor.Subject, &actor.Device.DeviceID, &actor.Device.tokenHash, &actor.Device.ExpiresAt, &csrf)
	if errors.Is(err, sql.ErrNoRows) {
		return actor, ErrUnauthorized
	}
	if err != nil {
		return actor, err
	}
	actor.Device.AccountID = actor.AccountID
	actor.Device.Role = "member"
	actor.Admin = api.Console.Admins[actor.Issuer+"\x00"+actor.Subject]
	if r.URL.Path != "/console/api/session" && r.Header.Get("X-Console-Account-ID") != actor.AccountID {
		return ConsoleActor{}, ErrUnauthorized
	}
	if mutation && (r.Header.Get("Origin") != api.Console.Origin || len(r.Header.Get("X-CSRF-Token")) != 43 || subtle.ConstantTimeCompare(csrf, consoleHash(r.Header.Get("X-CSRF-Token"))) != 1) {
		return ConsoleActor{}, ErrForbidden
	}
	return actor, nil
}

// requireConsoleActor rechecks the live session and binding inside a caller's
// account-locked transaction, so logout/ban cannot be bypassed by a stale actor.
func requireConsoleActor(ctx context.Context, tx *sql.Tx, actor ConsoleActor) error {
	if actor.Device.AccountID != actor.AccountID || actor.Device.Role != "member" {
		return ErrUnauthorized
	}
	var live bool
	err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM zapime_console_sessions s JOIN zapime_accounts a ON a.id=s.account_id JOIN zapime_oidc_subjects b ON b.account_id=s.account_id AND b.issuer=s.issuer AND b.subject=s.subject JOIN zapime_devices d ON d.id=s.device_id AND d.account_id=s.account_id AND d.token_hash=s.device_token_hash AND d.role='member' WHERE s.session_hash=$1 AND s.account_id=$2 AND s.issuer=$3 AND s.subject=$4 AND s.device_id=$5 AND s.device_token_hash=$6 AND s.expires_at>clock_timestamp() AND a.disabled_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>clock_timestamp())`, actor.sessionHash, actor.AccountID, actor.Issuer, actor.Subject, actor.Device.DeviceID, actor.Device.tokenHash).Scan(&live)
	if err != nil {
		return err
	}
	if !live {
		return ErrUnauthorized
	}
	return nil
}

func (api *API) consoleLock(ctx context.Context, actor ConsoleActor, target string, mutation bool) (*sql.Tx, int64, error) {
	if api.Console == nil || !validID(actor.AccountID) || !validID(target) {
		return nil, 0, ErrUnauthorized
	}
	admin := api.Console.Admins[actor.Issuer+"\x00"+actor.Subject]
	if target != actor.AccountID && (!admin || mutation) {
		return nil, 0, ErrForbidden
	}
	tx, err := api.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, 0, err
	}
	ids := []string{actor.AccountID}
	if target != actor.AccountID {
		ids = append(ids, target)
	}
	sort.Strings(ids)
	var head int64
	for _, id := range ids {
		var revision int64
		var disabled sql.NullTime
		err = tx.QueryRowContext(ctx, `SELECT revision,disabled_at FROM zapime_accounts WHERE id=$1 FOR UPDATE`, id).Scan(&revision, &disabled)
		if err == nil && disabled.Valid && (id == actor.AccountID || !admin) {
			err = ErrUnauthorized
		}
		if err != nil {
			tx.Rollback()
			if errors.Is(err, sql.ErrNoRows) {
				err = ErrInvalid
			}
			return nil, 0, err
		}
		if id == target {
			head = revision
		}
	}
	if err = requireConsoleActor(ctx, tx, actor); err != nil {
		tx.Rollback()
		return nil, 0, err
	}
	return tx, head, nil
}

func consoleAuditTx(ctx context.Context, tx *sql.Tx, actor ConsoleActor, target, action string) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO zapime_console_audit(actor_account_id,actor_issuer,actor_subject,target_account_id,action) VALUES($1,$2,$3,$4,$5)`, actor.AccountID, actor.Issuer, actor.Subject, target, action)
	return err
}
