// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"time"
)

type Store struct{ DB *sql.DB }

// 迁移只管理自身表；未来版本必须显式迁移，拒绝降级覆盖未知 schema。
func (s *Store) Migrate(ctx context.Context) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(7442001);
        CREATE TABLE IF NOT EXISTS zapime_schema(id integer PRIMARY KEY CHECK(id=1), version integer NOT NULL);
        INSERT INTO zapime_schema VALUES(1,1) ON CONFLICT DO NOTHING;`); err != nil {
		return err
	}
	var version int
	if err = tx.QueryRowContext(ctx, `SELECT version FROM zapime_schema WHERE id=1`).Scan(&version); err != nil {
		return err
	}
	if version != 1 {
		return errors.New("unsupported_database_version")
	}
	_, err = tx.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS zapime_accounts(
		id uuid PRIMARY KEY,revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),stored_bytes bigint NOT NULL DEFAULT 0 CHECK(stored_bytes>=0),created_at timestamptz NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS zapime_devices(
        id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES zapime_accounts(id),name text NOT NULL,
        role text NOT NULL CHECK(role IN ('owner','member')),token_hash bytea NOT NULL UNIQUE,
        expires_at timestamptz NOT NULL,revoked_at timestamptz);
    CREATE INDEX IF NOT EXISTS zapime_devices_account ON zapime_devices(account_id);
    CREATE TABLE IF NOT EXISTS zapime_records(
        account_id uuid NOT NULL REFERENCES zapime_accounts(id),record_id uuid NOT NULL,
        revision bigint NOT NULL,kind smallint NOT NULL CHECK(kind BETWEEN 1 AND 5),change jsonb NOT NULL,
        PRIMARY KEY(account_id,record_id));
    CREATE TABLE IF NOT EXISTS zapime_operations(
        account_id uuid NOT NULL REFERENCES zapime_accounts(id),operation_id uuid NOT NULL,
        device_id uuid NOT NULL REFERENCES zapime_devices(id),revision bigint NOT NULL,digest bytea NOT NULL,change jsonb NOT NULL,
        PRIMARY KEY(account_id,operation_id),UNIQUE(account_id,revision));`)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) Provision(ctx context.Context, name string) (Credential, error) {
	if !validName(name) {
		return Credential{}, ErrInvalid
	}
	account, err := UUID()
	if err != nil {
		return Credential{}, err
	}
	device, err := UUID()
	if err != nil {
		return Credential{}, err
	}
	token, hash, err := newToken()
	if err != nil {
		return Credential{}, err
	}
	expires := time.Now().UTC().Add(30 * 24 * time.Hour)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Credential{}, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_accounts(id) VALUES($1)`, account); err != nil {
		return Credential{}, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO zapime_devices(id,account_id,name,role,token_hash,expires_at) VALUES($1,$2,$3,'owner',$4,$5)`, device, account, name, hash, expires); err != nil {
		return Credential{}, err
	}
	if err = tx.Commit(); err != nil {
		return Credential{}, err
	}
	return Credential{account, device, token, expires}, nil
}

func (s *Store) Authenticate(ctx context.Context, token string) (Identity, error) {
	hash, err := tokenHash(token)
	if err != nil {
		return Identity{}, err
	}
	var actor Identity
	err = s.DB.QueryRowContext(ctx, `SELECT account_id::text,id::text,role FROM zapime_devices WHERE token_hash=$1 AND revoked_at IS NULL AND expires_at>now()`, hash).Scan(&actor.AccountID, &actor.DeviceID, &actor.Role)
	actor.tokenHash = hash
	if errors.Is(err, sql.ErrNoRows) {
		return Identity{}, ErrUnauthorized
	}
	return actor, err
}

// 账号行锁串行化写入、拉取和撤销；中间件认证之后仍在事务内重新检查授权。
func (s *Store) lock(ctx context.Context, actor Identity) (*sql.Tx, int64, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, 0, err
	}
	failed := true
	defer func() {
		if failed {
			tx.Rollback()
		}
	}()
	var head int64
	if err = tx.QueryRowContext(ctx, `SELECT revision FROM zapime_accounts WHERE id=$1 FOR UPDATE`, actor.AccountID).Scan(&head); err != nil {
		return nil, 0, err
	}
	var role string
	err = tx.QueryRowContext(ctx, `SELECT role FROM zapime_devices WHERE id=$1 AND account_id=$2 AND token_hash=$3 AND revoked_at IS NULL AND expires_at>now()`, actor.DeviceID, actor.AccountID, actor.tokenHash).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, 0, ErrUnauthorized
	}
	if err != nil {
		return nil, 0, err
	}
	if role != actor.Role {
		return nil, 0, ErrUnauthorized
	}
	failed = false
	return tx, head, nil
}

func (s *Store) Push(ctx context.Context, actor Identity, op Operation) (Change, error) {
	if err := op.Validate(); err != nil {
		return Change{}, err
	}
	tx, head, err := s.lock(ctx, actor)
	if err != nil {
		return Change{}, err
	}
	defer tx.Rollback()
	canonical, _ := json.Marshal(op)
	digest := sha256.Sum256(canonical)
	var storedDigest, stored []byte
	var device string
	err = tx.QueryRowContext(ctx, `SELECT digest,change,device_id::text FROM zapime_operations WHERE account_id=$1 AND operation_id=$2`, actor.AccountID, op.OperationID).Scan(&storedDigest, &stored, &device)
	if err == nil {
		if !bytes.Equal(storedDigest, digest[:]) || device != actor.DeviceID {
			return Change{}, ErrOperationReused
		}
		var change Change
		if err = json.Unmarshal(stored, &change); err != nil {
			return Change{}, err
		}
		return change, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Change{}, err
	}
	var revision int64
	var kind int
	var current []byte
	err = tx.QueryRowContext(ctx, `SELECT revision,kind,change FROM zapime_records WHERE account_id=$1 AND record_id=$2`, actor.AccountID, op.RecordID).Scan(&revision, &kind, &current)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return Change{}, err
	}
	if revision != op.BaseRevision {
		var change *Change
		if len(current) > 0 {
			if err = json.Unmarshal(current, &change); err != nil {
				return Change{}, err
			}
		}
		return Change{}, &Conflict{change}
	}
	if kind != 0 && kind != op.Kind {
		return Change{}, ErrInvalid
	}
	if head == math.MaxInt64 {
		return Change{}, ErrLimit
	}
	change := Change{actor.AccountID, actor.DeviceID, head + 1, op}
	encoded, err := json.Marshal(change)
	if err != nil {
		return Change{}, err
	}
	var storedBytes int64
	if err = tx.QueryRowContext(ctx, `SELECT stored_bytes FROM zapime_accounts WHERE id=$1`, actor.AccountID).Scan(&storedBytes); err != nil {
		return Change{}, err
	}
	// 保守计算事件日志和当前记录两份，防止单个凭据无界填满服务器。
	cost := int64(len(encoded)*2 + 512)
	if storedBytes+cost > 256*1024*1024 {
		return Change{}, ErrLimit
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO zapime_operations(account_id,operation_id,device_id,revision,digest,change) VALUES($1,$2,$3,$4,$5,$6)`, actor.AccountID, op.OperationID, actor.DeviceID, head+1, digest[:], encoded)
	if err != nil {
		return Change{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO zapime_records(account_id,record_id,revision,kind,change) VALUES($1,$2,$3,$4,$5)
      ON CONFLICT(account_id,record_id) DO UPDATE SET revision=excluded.revision,kind=excluded.kind,change=excluded.change`, actor.AccountID, op.RecordID, head+1, op.Kind, encoded)
	if err != nil {
		return Change{}, err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE zapime_accounts SET revision=$2,stored_bytes=stored_bytes+$3 WHERE id=$1`, actor.AccountID, head+1, cost); err != nil {
		return Change{}, err
	}
	return change, tx.Commit()
}

func (s *Store) Pull(ctx context.Context, actor Identity, after int64, limit int) (Page, error) {
	if after < 0 || limit < 1 || limit > 100 {
		return Page{}, ErrInvalid
	}
	tx, head, err := s.lock(ctx, actor)
	if err != nil {
		return Page{}, err
	}
	defer tx.Rollback()
	if after > head {
		return Page{}, ErrCursor
	}
	page := Page{Changes: []Change{}, NextCursor: after, Head: head}
	rows, err := tx.QueryContext(ctx, `SELECT change FROM zapime_operations WHERE account_id=$1 AND revision>$2 ORDER BY revision LIMIT $3`, actor.AccountID, after, limit)
	if err != nil {
		return Page{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var body []byte
		if err = rows.Scan(&body); err != nil {
			return Page{}, err
		}
		var change Change
		if err = json.Unmarshal(body, &change); err != nil {
			return Page{}, err
		}
		page.Changes = append(page.Changes, change)
		page.NextCursor = change.Revision
	}
	if err = rows.Err(); err != nil {
		return Page{}, err
	}
	rows.Close()
	return page, tx.Commit()
}

func (s *Store) Devices(ctx context.Context, actor Identity) ([]Device, error) {
	tx, _, err := s.lock(ctx, actor)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, `SELECT id::text,name,role,expires_at,revoked_at FROM zapime_devices WHERE account_id=$1 ORDER BY id`, actor.AccountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	devices := []Device{}
	for rows.Next() {
		var d Device
		if err = rows.Scan(&d.ID, &d.Name, &d.Role, &d.ExpiresAt, &d.RevokedAt); err != nil {
			return nil, err
		}
		devices = append(devices, d)
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	return devices, tx.Commit()
}

func (s *Store) AddDevice(ctx context.Context, actor Identity, name string) (Credential, error) {
	if !validName(name) {
		return Credential{}, ErrInvalid
	}
	tx, _, err := s.lock(ctx, actor)
	if err != nil {
		return Credential{}, err
	}
	defer tx.Rollback()
	if actor.Role != "owner" {
		return Credential{}, ErrForbidden
	}
	var count int
	if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM zapime_devices WHERE account_id=$1 AND revoked_at IS NULL AND expires_at>now()`, actor.AccountID).Scan(&count); err != nil {
		return Credential{}, err
	}
	if count >= 32 {
		return Credential{}, ErrLimit
	}
	device, err := UUID()
	if err != nil {
		return Credential{}, err
	}
	token, hash, err := newToken()
	if err != nil {
		return Credential{}, err
	}
	expires := time.Now().UTC().Add(30 * 24 * time.Hour)
	_, err = tx.ExecContext(ctx, `INSERT INTO zapime_devices(id,account_id,name,role,token_hash,expires_at) VALUES($1,$2,$3,'member',$4,$5)`, device, actor.AccountID, name, hash, expires)
	if err != nil {
		return Credential{}, err
	}
	return Credential{actor.AccountID, device, token, expires}, tx.Commit()
}
func (s *Store) Revoke(ctx context.Context, actor Identity, target string) error {
	if !validID(target) {
		return ErrInvalid
	}
	tx, _, err := s.lock(ctx, actor)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if actor.Role != "owner" && target != actor.DeviceID {
		return ErrForbidden
	}
	result, err := tx.ExecContext(ctx, `UPDATE zapime_devices SET revoked_at=COALESCE(revoked_at,now()) WHERE id=$1 AND account_id=$2`, target, actor.AccountID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count == 0 {
		return ErrInvalid
	}
	return tx.Commit()
}

// 只供持有数据库管理权限的显式 CLI 使用；重发访问凭据不等于恢复端到端密钥。
func (s *Store) RecoverDevice(ctx context.Context, id string) (Credential, error) {
	if !validID(id) {
		return Credential{}, ErrInvalid
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Credential{}, err
	}
	defer tx.Rollback()
	var account string
	if err = tx.QueryRowContext(ctx, `SELECT account_id::text FROM zapime_devices WHERE id=$1`, id).Scan(&account); err != nil {
		return Credential{}, err
	}
	var head int64
	if err = tx.QueryRowContext(ctx, `SELECT revision FROM zapime_accounts WHERE id=$1 FOR UPDATE`, account).Scan(&head); err != nil {
		return Credential{}, err
	}
	token, hash, err := newToken()
	if err != nil {
		return Credential{}, err
	}
	expires := time.Now().UTC().Add(30 * 24 * time.Hour)
	_, err = tx.ExecContext(ctx, `UPDATE zapime_devices SET token_hash=$2,expires_at=$3,revoked_at=NULL WHERE id=$1`, id, hash, expires)
	if err != nil {
		return Credential{}, err
	}
	return Credential{account, id, token, expires}, tx.Commit()
}
