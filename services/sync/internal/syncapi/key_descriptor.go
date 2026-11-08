// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"
)

const maxKeyDescriptorBytes = 1024

// Public identity only, never the root, recovery code or protected material.
// The digest is SHA256 of the client's versioned/account-bound material bytes;
// the service cannot establish possession of the root from this descriptor.
type KeyDescriptor struct {
	Version        int    `json:"version"`
	KeyID          string `json:"key_id"`
	MaterialSHA256 string `json:"material_sha256"`
}

func (descriptor KeyDescriptor) Validate() error {
	if descriptor.Version != 1 || !validID(descriptor.KeyID) || len(descriptor.MaterialSHA256) != 64 {
		return ErrInvalid
	}
	for _, c := range descriptor.MaterialSHA256 {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return ErrInvalid
		}
	}
	return nil
}

// Fixed six-field response. Empty and legacy states have three explicit nulls.
// No change revision is advanced by observing or registering key identity.
type KeyDescriptorState struct {
	Protocol     int            `json:"protocol"`
	AccountID    string         `json:"account_id"`
	State        string         `json:"state"`
	Descriptor   *KeyDescriptor `json:"descriptor"`
	RegisteredBy *string        `json:"registered_by"`
	RegisteredAt *time.Time     `json:"registered_at"`
}

type registeredKeyDescriptor struct {
	Descriptor   KeyDescriptor
	RegisteredBy string
	RegisteredAt time.Time
}

func readKeyDescriptor(ctx context.Context, tx *sql.Tx, account string) (*registeredKeyDescriptor, error) {
	var row registeredKeyDescriptor
	var digest []byte
	err := tx.QueryRowContext(ctx, `SELECT version,key_id::text,material_sha256,registered_by::text,registered_at
        FROM zapime_account_keys WHERE account_id=$1`, account).Scan(
		&row.Descriptor.Version, &row.Descriptor.KeyID, &digest, &row.RegisteredBy, &row.RegisteredAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	row.Descriptor.MaterialSHA256 = hex.EncodeToString(digest)
	row.RegisteredAt = row.RegisteredAt.UTC()
	if len(digest) != 32 || row.Descriptor.Validate() != nil || !validID(row.RegisteredBy) || row.RegisteredAt.IsZero() {
		return nil, errors.New("key_descriptor_storage_invalid")
	}
	return &row, nil
}

func keyDescriptorState(account string, row *registeredKeyDescriptor) KeyDescriptorState {
	return KeyDescriptorState{1, account, "registered", &row.Descriptor, &row.RegisteredBy, &row.RegisteredAt}
}

func accountHasKeyHistory(ctx context.Context, tx *sql.Tx, account string, head int64) (bool, error) {
	var storedBytes int64
	var rows bool
	err := tx.QueryRowContext(ctx, `SELECT stored_bytes,
        EXISTS(SELECT 1 FROM zapime_records WHERE account_id=$1)
        OR EXISTS(SELECT 1 FROM zapime_operations WHERE account_id=$1)
        FROM zapime_accounts WHERE id=$1`, account).Scan(&storedBytes, &rows)
	return head != 0 || storedBytes != 0 || rows, err
}

// Authenticated members may compare imported material with this public identity.
// Reads take the same account lock/revocation recheck as push and never use R2.
func (s *Store) KeyDescriptor(ctx context.Context, actor Identity) (KeyDescriptorState, error) {
	tx, head, err := s.lock(ctx, actor)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	defer tx.Rollback()
	row, err := readKeyDescriptor(ctx, tx, actor.AccountID)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	if row != nil {
		state := keyDescriptorState(actor.AccountID, row)
		return state, tx.Commit()
	}
	history, err := accountHasKeyHistory(ctx, tx, actor.AccountID, head)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	state := "empty"
	if history {
		state = "legacy"
	}
	return KeyDescriptorState{Protocol: 1, AccountID: actor.AccountID, State: state}, tx.Commit()
}

// First registration is owner-only, create-once and serialized with all changes.
// An empty local device or empty pull result alone must not trigger this method.
// Existing historical accounts need a separately reviewed migration/recovery
// procedure; neither their ciphertext nor their server identity is reset here.
func (s *Store) RegisterKeyDescriptor(ctx context.Context, actor Identity, input KeyDescriptor) (KeyDescriptorState, error) {
	if err := input.Validate(); err != nil {
		return KeyDescriptorState{}, err
	}
	tx, head, err := s.lock(ctx, actor)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	defer tx.Rollback()
	if actor.Role != "owner" {
		return KeyDescriptorState{}, ErrForbidden
	}
	row, err := readKeyDescriptor(ctx, tx, actor.AccountID)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	if row != nil {
		if row.Descriptor != input {
			return KeyDescriptorState{}, ErrKeyDescriptorConflict
		}
		return keyDescriptorState(actor.AccountID, row), tx.Commit()
	}
	history, err := accountHasKeyHistory(ctx, tx, actor.AccountID, head)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	if history {
		return KeyDescriptorState{}, ErrKeyDescriptorLegacy
	}
	digest, _ := hex.DecodeString(input.MaterialSHA256) // Validate fixed lowercase hex first.
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_account_keys(account_id,version,key_id,material_sha256,registered_by)
        VALUES($1,$2,$3,$4,$5)`, actor.AccountID, input.Version, input.KeyID, digest, actor.DeviceID); err != nil {
		return KeyDescriptorState{}, err
	}
	row, err = readKeyDescriptor(ctx, tx, actor.AccountID)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	if row == nil {
		return KeyDescriptorState{}, errors.New("key_descriptor_storage_invalid")
	}
	return keyDescriptorState(actor.AccountID, row), tx.Commit()
}

// Token decoding rejects duplicates as well as unknown fields. The ordinary
// changes API decoder is unchanged, preserving its existing protocol behavior.
func decodeKeyDescriptor(w http.ResponseWriter, r *http.Request) (KeyDescriptor, error) {
	var value KeyDescriptor
	if r.Header.Get("Content-Type") != "application/json" || r.URL.RawQuery != "" || r.URL.ForceQuery {
		return value, ErrInvalid
	}
	reader := http.MaxBytesReader(w, r.Body, maxKeyDescriptorBytes)
	defer reader.Close()
	body, err := io.ReadAll(reader)
	if err != nil {
		return value, ErrInvalid
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return value, ErrInvalid
	}
	seen := map[string]bool{}
	for decoder.More() {
		token, err = decoder.Token()
		name, stringName := token.(string)
		if err != nil || !stringName || seen[name] {
			return value, ErrInvalid
		}
		seen[name] = true
		switch name {
		case "version":
			number, err := decoder.Token()
			if err != nil || number != json.Number("1") {
				return value, ErrInvalid
			}
			value.Version = 1
		case "key_id":
			if err = decoder.Decode(&value.KeyID); err != nil {
				return value, ErrInvalid
			}
		case "material_sha256":
			if err = decoder.Decode(&value.MaterialSHA256); err != nil {
				return value, ErrInvalid
			}
		default:
			return value, ErrInvalid
		}
	}
	if token, err = decoder.Token(); err != nil || token != json.Delim('}') || len(seen) != 3 {
		return value, ErrInvalid
	}
	if _, err = decoder.Token(); err != io.EOF || value.Validate() != nil {
		return value, ErrInvalid
	}
	return value, nil
}
