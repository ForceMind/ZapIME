// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
)

// All callers have already acquired the account lock and checked current
// authorization. Existing descriptors/material are immutable; lost vault
// ciphertext or an unavailable master key never triggers an automatic reset.
func consoleEnableMaterialTx(ctx context.Context, tx *sql.Tx, account, device string, head int64, vault *ConsoleKeyVault, action string, imported []byte, mayInitialize bool) ([]byte, error) {
	if vault == nil {
		return nil, ErrConsoleKeyUnavailable
	}
	if action != "enable" && action != "import" || action == "enable" && len(imported) != 0 {
		return nil, ErrInvalid
	}
	descriptor, err := readKeyDescriptor(ctx, tx, account)
	if err != nil {
		return nil, err
	}
	var existing []byte
	err = tx.QueryRowContext(ctx, `SELECT encrypted_material FROM zapime_console_keys WHERE account_id=$1`, account).Scan(&existing)
	if err == nil {
		material, err := consoleReadMaterial(ctx, tx, account, vault)
		if err != nil {
			return nil, err
		}
		if action == "import" && !bytes.Equal(material, imported) {
			clear(material)
			return nil, ErrKeyDescriptorMismatch
		}
		return material, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	var material []byte
	if action == "import" {
		actual, err := consoleMaterialDescriptor(account, imported)
		if err != nil {
			return nil, err
		}
		if descriptor == nil || descriptor.Descriptor != actual {
			return nil, ErrKeyDescriptorMismatch
		}
		material = bytes.Clone(imported)
	} else {
		// A previously registered account needs its original key, even if its
		// current data page happens to be empty. Never infer replacement consent.
		if descriptor != nil {
			return nil, ErrConsoleKeyRequired
		}
		history, err := accountHasKeyHistory(ctx, tx, account, head)
		if err != nil {
			return nil, err
		}
		if history {
			return nil, ErrKeyDescriptorLegacy
		}
		if !mayInitialize {
			return nil, ErrForbidden
		}
		keyID, err := UUID()
		if err != nil {
			return nil, err
		}
		accountBytes, _ := consoleUUIDBytes(account)
		keyBytes, _ := consoleUUIDBytes(keyID)
		material = make([]byte, 72)
		copy(material, []byte{'Z', 'S', 'K', 'M', 0, 0, 0, 1})
		copy(material[8:24], accountBytes)
		copy(material[24:40], keyBytes)
		if _, err = rand.Read(material[40:]); err != nil {
			clear(material)
			return nil, err
		}
	}
	keep := false
	defer func() {
		if !keep {
			clear(material)
		}
	}()
	actual, err := consoleMaterialDescriptor(account, material)
	if err != nil {
		return nil, err
	}
	protected, err := vault.seal(account, material)
	if err != nil {
		return nil, err
	}
	if descriptor == nil {
		digest, _ := hex.DecodeString(actual.MaterialSHA256)
		if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_account_keys(account_id,version,key_id,material_sha256,registered_by) VALUES($1,1,$2,$3,$4)`, account, actual.KeyID, digest, device); err != nil {
			return nil, err
		}
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_console_keys(account_id,encrypted_material,registered_by) VALUES($1,$2,$3)`, account, protected, device); err != nil {
		return nil, err
	}
	keep = true
	return material, nil
}

// ConsoleEnableMaterial is invoked only after explicit web consent. It returns
// public metadata; the browser never receives root material or a recovery code.
func (api *API) ConsoleEnableMaterial(ctx context.Context, actor ConsoleActor, vault *ConsoleKeyVault) (KeyDescriptorState, error) {
	tx, head, err := api.consoleLock(ctx, actor, actor.AccountID, true)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	defer tx.Rollback()
	material, err := consoleEnableMaterialTx(ctx, tx, actor.AccountID, actor.Device.DeviceID, head, vault, "enable", nil, true)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	defer clear(material)
	row, err := readKeyDescriptor(ctx, tx, actor.AccountID)
	if err != nil {
		return KeyDescriptorState{}, err
	}
	if err = consoleAuditTx(ctx, tx, actor, actor.AccountID, "material.enable"); err != nil {
		return KeyDescriptorState{}, err
	}
	return keyDescriptorState(actor.AccountID, row), tx.Commit()
}

// ConsoleDeviceMaterial is an explicit, authenticated HTTPS transfer boundary.
// A caller must clear both imported material and the returned byte slice. It
// does not authorize automatic upload or modify existing records or cursors.
func (s *Store) ConsoleDeviceMaterial(ctx context.Context, actor Identity, vault *ConsoleKeyVault, action string, imported []byte) ([]byte, error) {
	tx, head, err := s.lock(ctx, actor)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	mayInitialize := actor.Role == "owner"
	if !mayInitialize {
		err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM zapime_automatic_devices WHERE account_id=$1 AND device_id=$2 AND may_initialize)`, actor.AccountID, actor.DeviceID).Scan(&mayInitialize)
		if err != nil {
			return nil, err
		}
	}
	material, err := consoleEnableMaterialTx(ctx, tx, actor.AccountID, actor.DeviceID, head, vault, action, imported, mayInitialize)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		clear(material)
		return nil, err
	}
	return material, nil
}
