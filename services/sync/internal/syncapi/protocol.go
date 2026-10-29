// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

var (
	ErrUnauthorized    = errors.New("unauthorized")
	ErrForbidden       = errors.New("forbidden")
	ErrInvalid         = errors.New("invalid_request")
	ErrOperationReused = errors.New("operation_id_reused")
	ErrCursor          = errors.New("invalid_cursor")
	ErrLimit           = errors.New("limit_reached")
)

// 密文格式直接对应 zapime-crypto v1；服务端不持有根密钥、不尝试解密。
type Envelope struct {
	Version    int    `json:"version"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}
type Operation struct {
	OperationID  string   `json:"operation_id"`
	RecordID     string   `json:"record_id"`
	KeyID        string   `json:"key_id"`
	Kind         int      `json:"kind"`
	BaseRevision int64    `json:"base_revision"`
	Deleted      bool     `json:"deleted"`
	Envelope     Envelope `json:"envelope"`
}
type Change struct {
	AccountID string    `json:"account_id"`
	DeviceID  string    `json:"device_id"`
	Revision  int64     `json:"revision"`
	Operation Operation `json:"operation"`
}
type Conflict struct {
	Current *Change `json:"current"`
}

func (*Conflict) Error() string { return "revision_conflict" }

type Page struct {
	Changes    []Change `json:"changes"`
	NextCursor int64    `json:"next_cursor"`
	Head       int64    `json:"head"`
}
type Identity struct {
	AccountID, DeviceID, Role string
	ExpiresAt                 time.Time
	tokenHash                 []byte
}
type Session struct {
	Protocol  int       `json:"protocol"`
	AccountID string    `json:"account_id"`
	DeviceID  string    `json:"device_id"`
	Role      string    `json:"role"`
	ExpiresAt time.Time `json:"expires_at"`
}
type Device struct {
	ID        string     `json:"id"`
	Name      string     `json:"name"`
	Role      string     `json:"role"`
	ExpiresAt time.Time  `json:"expires_at"`
	RevokedAt *time.Time `json:"revoked_at"`
}
type Credential struct {
	AccountID string    `json:"account_id"`
	DeviceID  string    `json:"device_id"`
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
}

func UUID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", err
	}
	value[6] = (value[6] & 15) | 64
	value[8] = (value[8] & 63) | 128
	text := hex.EncodeToString(value[:])
	return fmt.Sprintf("%s-%s-%s-%s-%s", text[:8], text[8:12], text[12:16], text[16:20], text[20:]), nil
}
func validID(id string) bool {
	if len(id) != 36 || id == "00000000-0000-0000-0000-000000000000" {
		return false
	}
	for i, c := range []byte(id) {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
		} else if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}
func newToken() (string, []byte, error) {
	var value [32]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", nil, err
	}
	token := "zap1_" + base64.RawURLEncoding.EncodeToString(value[:])
	hash, _ := tokenHash(token)
	return token, hash, nil
}
func tokenHash(token string) ([]byte, error) {
	if len(token) != 48 || !strings.HasPrefix(token, "zap1_") {
		return nil, ErrUnauthorized
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(token[5:])
	if err != nil || len(raw) != 32 {
		return nil, ErrUnauthorized
	}
	sum := sha256.Sum256([]byte(token))
	return sum[:], nil
}
func validName(name string) bool {
	if !utf8.ValidString(name) || len(name) == 0 || len(name) > 128 || strings.TrimSpace(name) == "" {
		return false
	}
	for _, c := range name {
		if c < 32 || c == 127 {
			return false
		}
	}
	return true
}
func (op Operation) Validate() error {
	if !validID(op.OperationID) || !validID(op.RecordID) || !validID(op.KeyID) || op.Kind < 1 || op.Kind > 5 || op.BaseRevision < 0 || op.Envelope.Version != 1 {
		return ErrInvalid
	}
	if len(op.Envelope.Nonce) != 32 || len(op.Envelope.Ciphertext) > 87403 {
		return ErrInvalid
	}
	nonce, err := base64.RawURLEncoding.Strict().DecodeString(op.Envelope.Nonce)
	if err != nil || len(nonce) != 24 {
		return ErrInvalid
	}
	ciphertext, err := base64.RawURLEncoding.Strict().DecodeString(op.Envelope.Ciphertext)
	if err != nil || len(ciphertext) < 16 || len(ciphertext) > 65552 {
		return ErrInvalid
	}
	return nil
}
