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
	return s.bindOIDCSubject(ctx, accountID, issuer, subject, false)
}

// BootstrapOIDCAccount binds the first account without creating an owner
// credential. The installer persists its selected ID before the transaction.
func (s *Store) BootstrapOIDCAccount(ctx context.Context, accountID, issuer, subject string) error {
	return s.bindOIDCSubject(ctx, accountID, issuer, subject, true)
}

func (s *Store) bindOIDCSubject(ctx context.Context, accountID, issuer, subject string, bootstrap bool) error {
	if !validID(accountID) || !oidcauth.ValidSubject(issuer, subject) {
		return ErrInvalid
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if bootstrap {
		if _, err = tx.ExecContext(ctx, `LOCK TABLE zapime_accounts IN SHARE ROW EXCLUSIVE MODE`); err != nil {
			return err
		}
		var already string
		err = tx.QueryRowContext(ctx, `SELECT account_id::text FROM zapime_oidc_subjects WHERE issuer=$1 AND subject=$2`, issuer, subject).Scan(&already)
		if err == nil {
			if already != accountID {
				return ErrOIDCSubjectBound
			}
			return tx.Commit()
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		// An existing installation must use the explicit binding operation.
		result, err := tx.ExecContext(ctx, `INSERT INTO zapime_accounts(id)
			SELECT $1::uuid WHERE NOT EXISTS (SELECT 1 FROM zapime_accounts)`, accountID)
		if err != nil {
			return err
		}
		if count, err := result.RowsAffected(); err != nil || count != 1 {
			return ErrInvalid
		}
	}
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
	err := s.DB.QueryRowContext(ctx, `SELECT b.account_id::text FROM zapime_oidc_subjects b JOIN zapime_accounts a ON a.id=b.account_id WHERE b.issuer=$1 AND b.subject=$2 AND a.disabled_at IS NULL`, issuer, subject).Scan(&account)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrUnauthorized
	}
	return account, err
}
