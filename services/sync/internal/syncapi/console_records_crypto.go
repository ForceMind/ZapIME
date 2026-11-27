// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/chacha20poly1305"
)

var (
	ErrConsoleKeyUnavailable = errors.New("console_key_unavailable")
	ErrConsoleKeyRequired    = errors.New("console_key_required")
)

// ConsoleKeyVault protects explicitly escrowed workspace material at rest.
// Its master key must be stored separately from the database and its backups.
// No JSON, String or public raw-key accessor exists on this object.
type ConsoleKeyVault struct{ aead cipher.AEAD }

func NewConsoleKeyVault(master []byte) (*ConsoleKeyVault, error) {
	if len(master) != 32 {
		return nil, ErrInvalid
	}
	block, err := aes.NewCipher(master)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &ConsoleKeyVault{aead: aead}, nil
}

func consoleUUIDBytes(value string) ([]byte, error) {
	if !validID(value) {
		return nil, ErrInvalid
	}
	return hex.DecodeString(strings.ReplaceAll(value, "-", ""))
}

func consoleUUIDString(value []byte) string {
	h := hex.EncodeToString(value)
	return fmt.Sprintf("%s-%s-%s-%s-%s", h[:8], h[8:12], h[12:16], h[16:20], h[20:])
}

func consoleMaterialDescriptor(account string, material []byte) (KeyDescriptor, error) {
	accountBytes, err := consoleUUIDBytes(account)
	if err != nil || len(material) != 72 || !bytes.Equal(material[:8], []byte{'Z', 'S', 'K', 'M', 0, 0, 0, 1}) || !bytes.Equal(material[8:24], accountBytes) {
		return KeyDescriptor{}, ErrInvalid
	}
	key := consoleUUIDString(material[24:40])
	if !validID(key) {
		return KeyDescriptor{}, ErrInvalid
	}
	digest := sha256.Sum256(material)
	return KeyDescriptor{Version: 1, KeyID: key, MaterialSHA256: hex.EncodeToString(digest[:])}, nil
}

func (v *ConsoleKeyVault) seal(account string, material []byte) ([]byte, error) {
	if v == nil {
		return nil, ErrConsoleKeyUnavailable
	}
	if _, err := consoleMaterialDescriptor(account, material); err != nil {
		return nil, err
	}
	nonce := make([]byte, v.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return v.aead.Seal(nonce, nonce, material, []byte("ZapIME/console-material/v1/"+account)), nil
}

func (v *ConsoleKeyVault) open(account string, encrypted []byte) ([]byte, error) {
	if v == nil || len(encrypted) != 12+72+16 {
		return nil, ErrConsoleKeyUnavailable
	}
	material, err := v.aead.Open(nil, encrypted[:12], encrypted[12:], []byte("ZapIME/console-material/v1/"+account))
	if err != nil {
		return nil, ErrConsoleKeyUnavailable
	}
	if _, err = consoleMaterialDescriptor(account, material); err != nil {
		clear(material)
		return nil, ErrConsoleKeyUnavailable
	}
	return material, nil
}

func consoleRecordAAD(account string, op Operation) ([]byte, error) {
	aad := []byte("ZapIME/record/v1")
	for _, id := range []string{account, op.RecordID, op.OperationID, op.KeyID} {
		b, err := consoleUUIDBytes(id)
		if err != nil {
			return nil, err
		}
		aad = append(aad, b...)
	}
	if op.Kind != 1 && op.Kind != 5 {
		return nil, ErrInvalid
	}
	return append(aad, byte(op.Kind)), nil
}

func consoleSealRecord(account string, material []byte, op Operation, record ConsoleRecord) (Envelope, error) {
	desc, err := consoleMaterialDescriptor(account, material)
	if err != nil || desc.KeyID != op.KeyID {
		return Envelope{}, ErrConsoleKeyUnavailable
	}
	aad, err := consoleRecordAAD(account, op)
	if err != nil {
		return Envelope{}, err
	}
	plaintext, err := json.Marshal(record)
	if err != nil {
		return Envelope{}, err
	}
	defer clear(plaintext)
	if len(plaintext) > 65536 {
		return Envelope{}, ErrInvalid
	}
	aead, err := chacha20poly1305.NewX(material[40:])
	if err != nil {
		return Envelope{}, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return Envelope{}, err
	}
	encrypted := aead.Seal(nil, nonce, plaintext, aad)
	return Envelope{1, base64.RawURLEncoding.EncodeToString(nonce), base64.RawURLEncoding.EncodeToString(encrypted)}, nil
}

func consoleOpenRecord(account string, material []byte, change Change) (ConsoleRecord, error) {
	op := change.Operation
	if change.AccountID != account || op.Validate() != nil {
		return ConsoleRecord{}, ErrInvalid
	}
	desc, err := consoleMaterialDescriptor(account, material)
	if err != nil || desc.KeyID != op.KeyID {
		return ConsoleRecord{}, ErrConsoleKeyUnavailable
	}
	aad, err := consoleRecordAAD(account, op)
	if err != nil {
		return ConsoleRecord{}, err
	}
	nonce, _ := base64.RawURLEncoding.Strict().DecodeString(op.Envelope.Nonce)
	ct, _ := base64.RawURLEncoding.Strict().DecodeString(op.Envelope.Ciphertext)
	aead, err := chacha20poly1305.NewX(material[40:])
	if err != nil {
		return ConsoleRecord{}, err
	}
	plaintext, err := aead.Open(nil, nonce, ct, aad)
	if err != nil {
		return ConsoleRecord{}, errors.New("console_record_authentication_failed")
	}
	defer clear(plaintext)
	var record ConsoleRecord
	if !consoleRequiredJSON(plaintext, "id", "revision", "device_id", "deleted", "content") || consoleStrictJSON(plaintext, &record) != nil || record.validate() != nil || record.ID != op.RecordID || record.Deleted != op.Deleted || record.Content.kind() != op.Kind {
		return ConsoleRecord{}, ErrInvalid
	}
	return record, nil
}
