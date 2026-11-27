// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type consoleAccount struct {
	AccountID   string    `json:"account_id"`
	DisplayName string    `json:"display_name"`
	Banned      bool      `json:"banned"`
	CreatedAt   time.Time `json:"created_at"`
	Revision    int64     `json:"revision"`
}

func (api *API) serveConsoleAdmin(ctx context.Context, w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/console/api/admin/") {
		return false
	}
	if api.Console == nil {
		write(w, 404, map[string]string{"error": "not_found"})
		return true
	}
	actor, err := api.consoleActor(ctx, w, r, r.Method != http.MethodGet)
	if err != nil {
		failure(w, err)
		return true
	}
	if !actor.Admin {
		failure(w, ErrForbidden)
		return true
	}
	if r.URL.Path == "/console/api/admin/accounts" && r.Method == http.MethodGet {
		api.consoleAccounts(ctx, w, r, actor)
		return true
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/console/api/admin/accounts/"), "/")
	if !strings.HasPrefix(r.URL.Path, "/console/api/admin/accounts/") || len(parts) != 2 || !validID(parts[0]) || r.Method != http.MethodPost {
		write(w, 404, map[string]string{"error": "not_found"})
		return true
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery {
		failure(w, ErrInvalid)
		return true
	}
	var empty struct{}
	if err = decode(w, r, &empty); err != nil {
		failure(w, err)
		return true
	}
	switch parts[1] {
	case "ban", "unban":
		if parts[0] == actor.AccountID {
			failure(w, ErrForbidden)
			return true
		}
		tx, _, err := api.consoleLock(ctx, actor, parts[0], false)
		if err != nil {
			failure(w, err)
			return true
		}
		defer tx.Rollback()
		banned := parts[1] == "ban"
		_, err = tx.ExecContext(ctx, `UPDATE zapime_accounts SET disabled_at=CASE WHEN $2 THEN COALESCE(disabled_at,now()) ELSE NULL END WHERE id=$1`, parts[0], banned)
		if err == nil {
			err = consoleAuditTx(ctx, tx, actor, parts[0], "account."+parts[1])
		}
		if err == nil && banned {
			_, err = tx.ExecContext(ctx, `UPDATE zapime_devices SET revoked_at=COALESCE(revoked_at,now()) WHERE account_id=$1 AND id IN (SELECT device_id FROM zapime_console_sessions WHERE account_id=$1)`, parts[0])
			if err == nil {
				_, err = tx.ExecContext(ctx, `DELETE FROM zapime_console_sessions WHERE account_id=$1`, parts[0])
			}
		}
		if err == nil {
			err = tx.Commit()
		}
		if err != nil {
			failure(w, err)
			return true
		}
		write(w, 200, map[string]any{"account_id": parts[0], "banned": banned})
	case "password-reset":
		api.consolePasswordReset(ctx, w, r, actor, parts[0])
	default:
		write(w, 404, map[string]string{"error": "not_found"})
	}
	return true
}

func (api *API) consoleAccounts(ctx context.Context, w http.ResponseWriter, r *http.Request, actor ConsoleActor) {
	q := r.URL.Query()
	for key, values := range q {
		if (key != "after" && key != "q" && key != "limit") || len(values) != 1 {
			failure(w, ErrInvalid)
			return
		}
	}
	if !consoleNoBody(r) || len(q.Get("q")) > 255 || (q.Get("after") != "" && !validID(q.Get("after"))) {
		failure(w, ErrInvalid)
		return
	}
	limit := 50
	if raw := q.Get("limit"); raw != "" {
		var err error
		limit, err = strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 50 {
			failure(w, ErrInvalid)
			return
		}
	}
	tx, _, err := api.consoleLock(ctx, actor, actor.AccountID, false)
	if err != nil {
		failure(w, err)
		return
	}
	defer tx.Rollback()
	// The directory exposes registered local accounts only. No arbitrary IdP
	// query, provider token, or user text is included in the response.
	rows, err := tx.QueryContext(ctx, `SELECT a.id::text,a.disabled_at IS NOT NULL,a.created_at,a.revision FROM zapime_accounts a
	WHERE ($1='' OR a.id::text>$1) AND ($2='' OR starts_with(a.id::text,lower($2)) OR EXISTS(SELECT 1 FROM zapime_oidc_subjects b WHERE b.account_id=a.id AND b.subject=$2)) ORDER BY a.id LIMIT $3`, q.Get("after"), q.Get("q"), limit+1)
	if err != nil {
		failure(w, err)
		return
	}
	accounts := make([]consoleAccount, 0, limit)
	next := ""
	for rows.Next() {
		var a consoleAccount
		if err = rows.Scan(&a.AccountID, &a.Banned, &a.CreatedAt, &a.Revision); err != nil {
			break
		}
		a.DisplayName = a.AccountID
		accounts = append(accounts, a)
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err == nil {
		err = consoleAuditTx(ctx, tx, actor, actor.AccountID, "accounts.list")
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		failure(w, err)
		return
	}
	if len(accounts) > limit {
		accounts = accounts[:limit]
		next = accounts[len(accounts)-1].AccountID
	}
	write(w, 200, map[string]any{"accounts": accounts, "next_after": next})
}

// Adapter contract: a fixed HTTPS provider endpoint accepts issuer/subject,
// account_id and a unique request_id, authenticates the server bearer token,
// and returns 200/202/204 only after accepting a reset notification. It must
// never return a password or reset URL to ZapIME. No user-selected URL is used.
func (api *API) consolePasswordReset(ctx context.Context, w http.ResponseWriter, _ *http.Request, actor ConsoleActor, target string) {
	c := api.Console
	if c.PasswordResetEndpoint == "" {
		write(w, 501, map[string]string{"error": "password_reset_unavailable"})
		return
	}
	tx, _, err := api.consoleLock(ctx, actor, target, false)
	if err != nil {
		failure(w, err)
		return
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, `SELECT issuer,subject FROM zapime_oidc_subjects WHERE account_id=$1 ORDER BY issuer,subject LIMIT 2`, target)
	if err != nil {
		failure(w, err)
		return
	}
	var issuer, subject string
	count := 0
	for rows.Next() {
		count++
		if err = rows.Scan(&issuer, &subject); err != nil {
			break
		}
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		failure(w, err)
		return
	}
	if count != 1 {
		write(w, 409, map[string]string{"error": "password_reset_identity_ambiguous"})
		return
	}
	id, err := UUID()
	if err == nil {
		err = consoleResetAuditTx(ctx, tx, actor, target, "password_reset.requested", id)
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		failure(w, err)
		return
	}
	data, _ := json.Marshal(map[string]string{"issuer": issuer, "subject": subject, "account_id": target, "request_id": id})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.PasswordResetEndpoint, bytes.NewReader(data))
	if err != nil {
		failure(w, err)
		return
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+c.PasswordResetToken)
	request.Header.Set("Idempotency-Key", id)
	response, err := c.Client.Do(request)
	accepted := false
	if err == nil {
		accepted = response.StatusCode == 200 || response.StatusCode == 202 || response.StatusCode == 204
		response.Body.Close()
	}
	action := "password_reset.result_unknown"
	if accepted {
		action = "password_reset.accepted"
	}
	// Retain the initial intent even if the provider accepted a request whose
	// response was lost; the UI never claims a password changed.
	tx, _, auditErr := api.consoleLock(ctx, actor, target, false)
	if auditErr == nil {
		auditErr = consoleResetAuditTx(ctx, tx, actor, target, action, id)
		if auditErr == nil {
			auditErr = tx.Commit()
		}
		tx.Rollback()
	}
	if auditErr != nil {
		// A provider side effect may already have happened, including when
		// the request context expired before its outcome could be recorded.
		write(w, 503, map[string]string{"error": "password_reset_result_unknown"})
		return
	}
	if !accepted {
		write(w, 503, map[string]string{"error": "password_reset_result_unknown"})
		return
	}
	write(w, 202, map[string]bool{"accepted": true})
}

func consoleResetAuditTx(ctx context.Context, tx *sql.Tx, actor ConsoleActor, target, action, requestID string) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO zapime_console_audit(actor_account_id,actor_issuer,actor_subject,target_account_id,action,request_id) VALUES($1,$2,$3,$4,$5,$6)`, actor.AccountID, actor.Issuer, actor.Subject, target, action, requestID)
	return err
}
