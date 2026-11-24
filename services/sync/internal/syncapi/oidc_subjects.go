// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

var ErrOIDCSubjectBound = errors.New("oidc_subject_already_bound")

// BindOIDCSubject is a local administrator operation. The identity must come
// from the operator's configured issuer, not a user-supplied email address.
func (s *Store) BindOIDCSubject(ctx context.Context, accountID, issuer, subject string) error {
	if !validID(accountID) || !oidcauth.ValidSubject(issuer, subject) {
		return ErrInvalid
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `INSERT INTO zapime_oidc_subjects(issuer,subject,account_id)
        SELECT $1,$2,id FROM zapime_accounts WHERE id=$3 ON CONFLICT(issuer,subject) DO NOTHING`, issuer, subject, accountID)
	if err != nil {
		return err
	}
	var existing string
	err = tx.QueryRowContext(ctx, `SELECT account_id::text FROM zapime_oidc_subjects WHERE issuer=$1 AND subject=$2`, issuer, subject).Scan(&existing)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrInvalid
	}
	if err != nil {
		return err
	}
	if existing != accountID {
		return ErrOIDCSubjectBound
	}
	return tx.Commit()
}

// ResolveOIDCAccount only resolves the administrator's binding. It cannot
// issue a device credential or establish a role on the account.
func (s *Store) ResolveOIDCAccount(ctx context.Context, proof oidcauth.Subject) (string, error) {
	issuer, subject, _, expiresAt := proof.Claims()
	if !oidcauth.ValidSubject(issuer, subject) || !time.Now().Before(expiresAt) {
		return "", ErrUnauthorized
	}
	var account string
	err := s.DB.QueryRowContext(ctx, `SELECT account_id::text FROM zapime_oidc_subjects WHERE issuer=$1 AND subject=$2`, issuer, subject).Scan(&account)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrUnauthorized
	}
	return account, err
}
