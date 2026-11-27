// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"strconv"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

const enrollmentLifetime = 10 * time.Minute

var ErrEnrollmentPending = errors.New("enrollment_pending")

type enrollmentRequest struct {
	ID        string `json:"enrollment_id"`
	AccountID string `json:"account_id"`
	Name      string `json:"name"`
	Deadline  int64  `json:"deadline"`
	PublicKey string `json:"public_key"`
	TokenHash string `json:"token_sha256"`
	Signature string `json:"signature"`
}

type enrollmentReceipt struct {
	ID          string `json:"enrollment_id"`
	Fingerprint string `json:"fingerprint"`
	Deadline    int64  `json:"deadline"`
}

type enrollmentApproval struct {
	ID          string `json:"enrollment_id"`
	Fingerprint string `json:"fingerprint"`
}

type enrollmentClaim struct {
	ID        string `json:"enrollment_id"`
	AccountID string `json:"account_id"`
	Signature string `json:"signature"`
}

func enrollmentHash(fields ...string) [32]byte {
	var message []byte
	for _, field := range fields {
		message = binary.BigEndian.AppendUint32(message, uint32(len(field)))
		message = append(message, field...)
	}
	return sha256.Sum256(message)
}

func enrollmentBytes(wire string, limit int) ([]byte, error) {
	if wire == "" || len(wire) > base64.RawURLEncoding.EncodedLen(limit) {
		return nil, ErrInvalid
	}
	data, err := base64.RawURLEncoding.Strict().DecodeString(wire)
	if err != nil || base64.RawURLEncoding.EncodeToString(data) != wire {
		return nil, ErrInvalid
	}
	return data, nil
}

func verifyEnrollmentSignature(keyDER, fingerprint []byte, wireSignature string, accessTokenHash [32]byte, purpose string) bool {
	parsed, err := x509.ParsePKIXPublicKey(keyDER)
	key, ok := parsed.(*ecdsa.PublicKey)
	if err != nil || !ok || key.Curve != elliptic.P256() {
		return false
	}
	signature, err := enrollmentBytes(wireSignature, 72)
	digest := enrollmentHash(purpose, string(fingerprint), string(accessTokenHash[:]))
	return err == nil && ecdsa.VerifyASN1(key, digest[:], signature)
}

func (s *Store) lockEnrollment(ctx context.Context, account string, proof oidcauth.Subject) (*sql.Tx, error) {
	if !validID(account) {
		return nil, ErrInvalid
	}
	issuer, subject, _, expiresAt := proof.Claims()
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	err = tx.QueryRowContext(ctx, `SELECT a.id::text FROM zapime_accounts a
        JOIN zapime_oidc_subjects b ON b.account_id=a.id
        WHERE a.id=$1 AND b.issuer=$2 AND b.subject=$3 AND a.disabled_at IS NULL FOR UPDATE OF a`, account, issuer, subject).Scan(&account)
	if err == nil && time.Now().Before(expiresAt) {
		return tx, nil
	}
	tx.Rollback()
	if err == nil || errors.Is(err, sql.ErrNoRows) {
		return nil, ErrUnauthorized
	}
	return nil, err
}

func (s *Store) beginEnrollment(ctx context.Context, proof oidcauth.Subject, accessHash [32]byte, req enrollmentRequest) (enrollmentReceipt, error) {
	var receipt enrollmentReceipt
	deadline := time.Unix(req.Deadline, 0)
	now := time.Now()
	if !validID(req.ID) || !validID(req.AccountID) || !validName(req.Name) ||
		!deadline.After(now) || deadline.After(now.Add(enrollmentLifetime)) {
		return receipt, ErrInvalid
	}
	key, err := enrollmentBytes(req.PublicKey, 128)
	if err != nil {
		return receipt, err
	}
	tokenHash, err := enrollmentBytes(req.TokenHash, sha256.Size)
	if err != nil || len(tokenHash) != sha256.Size {
		return receipt, ErrInvalid
	}
	issuer, subject, tokenID, expiresAt := proof.Claims()
	resource := proof.Audience()
	fingerprint := enrollmentHash("zapime-enrollment-v1", issuer, resource, req.AccountID, req.ID,
		req.Name, strconv.FormatInt(req.Deadline, 10), string(key), string(tokenHash))
	if !verifyEnrollmentSignature(key, fingerprint[:], req.Signature, accessHash, "zapime-enrollment-begin-v1") {
		return receipt, ErrUnauthorized
	}
	tx, err := s.lockEnrollment(ctx, req.AccountID, proof)
	if err != nil {
		return receipt, err
	}
	defer tx.Rollback()
	if err = lockEnrollmentToken(ctx, tx, issuer, tokenID); err != nil {
		return receipt, err
	}
	if !time.Now().Before(expiresAt) {
		return receipt, ErrUnauthorized
	}
	var usedAutomatically bool
	err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM zapime_automatic_enrollment_tokens
        WHERE issuer=$1 AND token_id=$2 AND expires_at>clock_timestamp())`, issuer, tokenID).Scan(&usedAutomatically)
	if err != nil || usedAutomatically {
		if err == nil {
			err = ErrUnauthorized
		}
		return receipt, err
	}
	receipt = enrollmentReceipt{req.ID, hex.EncodeToString(fingerprint[:]), req.Deadline}
	var existing []byte
	err = tx.QueryRowContext(ctx, `SELECT fingerprint FROM zapime_device_enrollments
        WHERE issuer=$1 AND token_id=$2 AND subject=$3 AND account_id=$4`, issuer, tokenID, subject, req.AccountID).Scan(&existing)
	if err == nil {
		if !deadline.After(time.Now()) || !bytes.Equal(existing, fingerprint[:]) {
			return receipt, ErrUnauthorized
		}
		return receipt, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return receipt, err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM zapime_device_enrollments
        WHERE account_id=$1 AND expires_at<clock_timestamp()-interval '10 minutes'`, req.AccountID); err != nil {
		return receipt, err
	}
	var count int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM zapime_device_enrollments WHERE account_id=$1
        AND claimed_at IS NULL AND expires_at>clock_timestamp()`, req.AccountID).Scan(&count); err != nil {
		return receipt, err
	}
	if count >= 8 {
		return receipt, ErrLimit
	}
	var id string
	err = tx.QueryRowContext(ctx, `INSERT INTO zapime_device_enrollments
        (id,account_id,issuer,subject,token_id,resource,name,public_key,new_token_hash,fingerprint,expires_at)
        SELECT $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11 WHERE $11::timestamptz>clock_timestamp()
        AND NOT EXISTS(SELECT 1 FROM zapime_devices WHERE id=$1)
        ON CONFLICT DO NOTHING RETURNING id::text`, req.ID, req.AccountID, issuer, subject, tokenID, resource,
		req.Name, key, tokenHash, fingerprint[:], deadline).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return receipt, ErrUnauthorized
	}
	if err != nil {
		return receipt, err
	}
	return receipt, tx.Commit()
}

func (s *Store) approveEnrollment(ctx context.Context, actor Identity, req enrollmentApproval) error {
	fingerprint, err := hex.DecodeString(req.Fingerprint)
	if !validID(req.ID) || err != nil || len(fingerprint) != sha256.Size || hex.EncodeToString(fingerprint) != req.Fingerprint {
		return ErrInvalid
	}
	tx, _, err := s.lock(ctx, actor)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if actor.Role != "owner" {
		return ErrForbidden
	}
	var id string
	err = tx.QueryRowContext(ctx, `UPDATE zapime_device_enrollments SET approved_by=$4,approval_token_hash=$5
        WHERE id=$1 AND account_id=$2 AND fingerprint=$3 AND claimed_at IS NULL AND expires_at>clock_timestamp()
        AND (approved_by IS NULL OR (approved_by=$4 AND approval_token_hash=$5)) RETURNING id::text`,
		req.ID, actor.AccountID, fingerprint, actor.DeviceID, actor.tokenHash).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrUnauthorized
	}
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) claimEnrollment(ctx context.Context, proof oidcauth.Subject, accessHash [32]byte, req enrollmentClaim) (Session, error) {
	var session Session
	if !validID(req.ID) {
		return session, ErrInvalid
	}
	tx, err := s.lockEnrollment(ctx, req.AccountID, proof)
	if err != nil {
		return session, err
	}
	defer tx.Rollback()
	issuer, subject, _, _ := proof.Claims()
	var key, fingerprint, tokenHash, approvalHash []byte
	var name string
	var approver sql.NullString
	var claimed sql.NullTime
	err = tx.QueryRowContext(ctx, `SELECT name,public_key,fingerprint,new_token_hash,approved_by::text,approval_token_hash,claimed_at
        FROM zapime_device_enrollments WHERE id=$1 AND account_id=$2 AND issuer=$3
        AND subject=$4 AND resource=$5 AND expires_at>clock_timestamp()`,
		req.ID, req.AccountID, issuer, subject, proof.Audience()).Scan(&name, &key, &fingerprint, &tokenHash, &approver, &approvalHash, &claimed)
	if errors.Is(err, sql.ErrNoRows) {
		return session, ErrUnauthorized
	}
	if err != nil {
		return session, err
	}
	if !verifyEnrollmentSignature(key, fingerprint, req.Signature, accessHash, "zapime-enrollment-claim-v1") {
		return session, ErrUnauthorized
	}
	if !approver.Valid {
		return session, ErrEnrollmentPending
	}
	session = Session{Protocol: 1, AccountID: req.AccountID, DeviceID: req.ID, Role: "member"}
	if claimed.Valid {
		err = tx.QueryRowContext(ctx, `SELECT expires_at FROM zapime_devices WHERE id=$1 AND account_id=$2 AND role='member'
            AND token_hash=$3 AND revoked_at IS NULL AND expires_at>clock_timestamp()`, req.ID, req.AccountID, tokenHash).Scan(&session.ExpiresAt)
	} else {
		err = activateEnrolledDevice(ctx, tx, &session, name, tokenHash, approver.String, approvalHash)
	}
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrUnauthorized
	}
	if err != nil {
		return Session{}, err
	}
	return session, tx.Commit()
}

func activateEnrolledDevice(ctx context.Context, tx *sql.Tx, session *Session, name string, tokenHash []byte, approver string, approvalHash []byte) error {
	var owner bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM zapime_devices
        WHERE id=$1 AND account_id=$2 AND role='owner' AND token_hash=$3 AND revoked_at IS NULL
        AND expires_at>clock_timestamp())`, approver, session.AccountID, approvalHash).Scan(&owner); err != nil {
		return err
	}
	if !owner {
		return ErrUnauthorized
	}
	if err := createEnrolledMember(ctx, tx, session, name, tokenHash); err != nil {
		return err
	}
	var id string
	return tx.QueryRowContext(ctx, `UPDATE zapime_device_enrollments SET claimed_at=clock_timestamp()
        WHERE id=$1 AND claimed_at IS NULL AND expires_at>clock_timestamp() RETURNING id::text`, session.DeviceID).Scan(&id)
}

func createEnrolledMember(ctx context.Context, tx *sql.Tx, session *Session, name string, tokenHash []byte) error {
	if err := requireEnrollmentCapacity(ctx, tx, session.AccountID); err != nil {
		return err
	}
	return tx.QueryRowContext(ctx, `INSERT INTO zapime_devices (id,account_id,name,role,token_hash,expires_at)
        VALUES($1,$2,$3,'member',$4,clock_timestamp()+interval '30 days')
        ON CONFLICT DO NOTHING RETURNING expires_at`, session.DeviceID, session.AccountID, name, tokenHash).Scan(&session.ExpiresAt)
}

func requireEnrollmentCapacity(ctx context.Context, tx *sql.Tx, account string) error {
	var count int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM zapime_devices WHERE account_id=$1
        AND revoked_at IS NULL AND expires_at>clock_timestamp()`, account).Scan(&count); err != nil {
		return err
	}
	if count >= 32 {
		return ErrLimit
	}
	return nil
}
