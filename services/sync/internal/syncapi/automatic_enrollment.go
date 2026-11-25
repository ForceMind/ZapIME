// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net/http"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

type automaticEnrollmentRequest struct {
	DeviceID  string `json:"device_id"`
	AccountID string `json:"account_id"`
	Name      string `json:"name"`
	PublicKey string `json:"public_key"`
	TokenHash string `json:"token_sha256"`
	Signature string `json:"signature"`
}

func (api *API) automaticDeviceEnrollment(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if api.oidc == nil || !api.AllowAutomaticEnrollment || r.Method != http.MethodPost {
		write(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery {
		failure(w, ErrInvalid)
		return
	}
	token, err := loginBearer(r)
	if err != nil {
		failure(w, err)
		return
	}
	proof, err := api.oidc.Verify(token, time.Now())
	if err != nil {
		failure(w, ErrUnauthorized)
		return
	}
	var req automaticEnrollmentRequest
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if err = decode(w, r, &req); err != nil {
		failure(w, err)
		return
	}
	session, err := api.Store.automaticEnrollment(ctx, proof, sha256.Sum256([]byte(token)), req)
	if err != nil {
		failure(w, err)
		return
	}
	write(w, http.StatusOK, session)
}

// Both enrollment routes take the account lock before this issuer/JTI lock.
// A collision only serializes two transactions; the SQL keys check the full values.
func lockEnrollmentToken(ctx context.Context, tx *sql.Tx, issuer, tokenID string) error {
	digest := enrollmentHash("zapime-enrollment-token-lock-v1", issuer, tokenID)
	_, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(7442002,$1::integer)`, int32(binary.BigEndian.Uint32(digest[:])))
	return err
}

func reserveAutomaticToken(ctx context.Context, tx *sql.Tx, account string, proof oidcauth.Subject, hash [32]byte) error {
	issuer, _, tokenID, expiresAt := proof.Claims()
	if err := lockEnrollmentToken(ctx, tx, issuer, tokenID); err != nil {
		return err
	}
	if !time.Now().Before(expiresAt) {
		return ErrUnauthorized
	}
	var legacy bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM zapime_device_enrollments
        WHERE issuer=$1 AND token_id=$2)`, issuer, tokenID).Scan(&legacy); err != nil {
		return err
	}
	if legacy {
		return ErrUnauthorized
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM zapime_automatic_enrollment_tokens
        WHERE account_id=$1 AND expires_at<=clock_timestamp()`, account); err != nil {
		return err
	}
	var existing []byte
	err := tx.QueryRowContext(ctx, `SELECT request_hash FROM zapime_automatic_enrollment_tokens
        WHERE issuer=$1 AND token_id=$2`, issuer, tokenID).Scan(&existing)
	if err == nil {
		if !bytes.Equal(existing, hash[:]) {
			return ErrUnauthorized
		}
		return nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	var count int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM zapime_automatic_enrollment_tokens
        WHERE account_id=$1`, account).Scan(&count); err != nil {
		return err
	}
	if count >= 128 {
		return ErrLimit
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO zapime_automatic_enrollment_tokens
        (issuer,token_id,account_id,request_hash,expires_at) VALUES($1,$2,$3,$4,$5)`, issuer, tokenID, account, hash[:], expiresAt)
	return err
}

func (s *Store) automaticEnrollment(ctx context.Context, proof oidcauth.Subject, accessHash [32]byte, req automaticEnrollmentRequest) (Session, error) {
	if !validID(req.DeviceID) || !validID(req.AccountID) || !validName(req.Name) {
		return Session{}, ErrInvalid
	}
	publicKey, err := enrollmentBytes(req.PublicKey, 128)
	if err != nil {
		return Session{}, err
	}
	tokenHash, err := enrollmentBytes(req.TokenHash, sha256.Size)
	if err != nil || len(tokenHash) != sha256.Size {
		return Session{}, ErrInvalid
	}
	parsed, err := x509.ParsePKIXPublicKey(publicKey)
	if err != nil {
		return Session{}, ErrInvalid
	}
	canonical, err := x509.MarshalPKIXPublicKey(parsed)
	if err != nil || !bytes.Equal(canonical, publicKey) {
		return Session{}, ErrInvalid
	}
	issuer, subject, _, expiresAt := proof.Claims()
	subjectFingerprint := enrollmentHash("zapime-account-login-v1", issuer, subject, proof.Audience(), req.AccountID)
	fingerprint := enrollmentHash("zapime-auto-device-v1", issuer, proof.Audience(), req.AccountID, req.DeviceID,
		hex.EncodeToString(subjectFingerprint[:]), req.Name, string(publicKey), string(tokenHash))
	if !verifyEnrollmentSignature(publicKey, fingerprint[:], req.Signature, accessHash, "zapime-auto-device-sign-v1") {
		return Session{}, ErrUnauthorized
	}
	tx, err := s.lockEnrollment(ctx, req.AccountID, proof)
	if err != nil {
		return Session{}, err
	}
	defer tx.Rollback()
	requestHash := enrollmentHash("zapime-auto-redemption-v1", string(fingerprint[:]), string(accessHash[:]))
	if err = reserveAutomaticToken(ctx, tx, req.AccountID, proof, requestHash); err != nil {
		return Session{}, err
	}
	session := Session{Protocol: 1, AccountID: req.AccountID, DeviceID: req.DeviceID, Role: "member"}
	if err = authorizeInstallation(ctx, tx, &session, req.Name, publicKey, subjectFingerprint[:], tokenHash); err != nil {
		return Session{}, err
	}
	if !time.Now().Before(expiresAt) {
		return Session{}, ErrUnauthorized
	}
	session.ExpiresAt = session.ExpiresAt.UTC()
	return session, tx.Commit()
}

func authorizeInstallation(ctx context.Context, tx *sql.Tx, session *Session, name string, publicKey, subject, tokenHash []byte) error {
	var device, role string
	var boundSubject, storedToken []byte
	var revoked, live bool
	err := tx.QueryRowContext(ctx, `SELECT d.id::text,d.role,a.subject_fingerprint,d.token_hash,
        d.revoked_at IS NOT NULL,d.expires_at>clock_timestamp(),d.expires_at FROM zapime_automatic_devices a
        JOIN zapime_devices d ON d.id=a.device_id AND d.account_id=a.account_id
        WHERE a.account_id=$1 AND a.public_key=$2`, session.AccountID, publicKey).Scan(
		&device, &role, &boundSubject, &storedToken, &revoked, &live, &session.ExpiresAt)
	if err == nil {
		if revoked || device != session.DeviceID || role != session.Role || !bytes.Equal(boundSubject, subject) || !bytes.Equal(storedToken, tokenHash) {
			return ErrUnauthorized
		}
		if live {
			return nil
		}
		if err = requireEnrollmentCapacity(ctx, tx, session.AccountID); err != nil {
			return err
		}
		return tx.QueryRowContext(ctx, `UPDATE zapime_devices SET expires_at=clock_timestamp()+interval '30 days'
            WHERE id=$1 RETURNING expires_at`, device).Scan(&session.ExpiresAt)
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err = createEnrolledMember(ctx, tx, session, name, tokenHash); errors.Is(err, sql.ErrNoRows) {
		return ErrUnauthorized
	} else if err != nil {
		return err
	}
	var head int64
	if err = tx.QueryRowContext(ctx, `SELECT revision FROM zapime_accounts WHERE id=$1`, session.AccountID).Scan(&head); err != nil {
		return err
	}
	hasHistory, err := accountHasKeyHistory(ctx, tx, session.AccountID, head)
	if err != nil {
		return err
	}
	key, err := readKeyDescriptor(ctx, tx, session.AccountID)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO zapime_automatic_devices(device_id,account_id,public_key,subject_fingerprint,may_initialize)
        VALUES($1,$2,$3,$4,$5)`, session.DeviceID, session.AccountID, publicKey, subject, !hasHistory && key == nil)
	return err
}
