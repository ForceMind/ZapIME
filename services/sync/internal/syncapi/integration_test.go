// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	_ "github.com/jackc/pgx/v5/stdlib"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func database(t *testing.T) *Store {
	t.Helper()
	url := os.Getenv("ZAPIME_TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("ZAPIME_REQUIRE_DATABASE") == "1" {
			t.Fatal("PostgreSQL integration database required")
		}
		t.Skip("set ZAPIME_TEST_DATABASE_URL to run real PostgreSQL tests")
	}
	db, err := sql.Open("pgx", url)
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(16)
	t.Cleanup(func() { db.Close() })
	s := &Store{DB: db}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err = s.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	return s
}
func provision(t *testing.T, s *Store) (Credential, Identity) {
	t.Helper()
	c, err := s.Provision(context.Background(), "合成测试设备")
	if err != nil {
		t.Fatal(err)
	}
	a, err := s.Authenticate(context.Background(), c.Token)
	if err != nil {
		t.Fatal(err)
	}
	return c, a
}

func TestPostgresTransactions(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	_, owner := provision(t, s)
	t.Run("idempotency replay and conflicting reuse", func(t *testing.T) {
		op := operation(t)
		first, err := s.Push(ctx, owner, op)
		if err != nil {
			t.Fatal(err)
		}
		again, err := s.Push(ctx, owner, op)
		if err != nil || again.Revision != first.Revision {
			t.Fatal("idempotent retry failed", err)
		}
		op.Deleted = true
		if _, err = s.Push(ctx, owner, op); !errors.Is(err, ErrOperationReused) {
			t.Fatal("operation reuse accepted", err)
		}
	})
	t.Run("concurrent stale writes keep one winner", func(t *testing.T) {
		op := operation(t)
		first, err := s.Push(ctx, owner, op)
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		results := make(chan error, 2)
		for i := 0; i < 2; i++ {
			next := op
			next.OperationID = id(t)
			next.BaseRevision = first.Revision
			wg.Add(1)
			go func() { defer wg.Done(); _, err := s.Push(ctx, owner, next); results <- err }()
		}
		wg.Wait()
		close(results)
		success, conflicts := 0, 0
		for err := range results {
			var conflict *Conflict
			if err == nil {
				success++
			} else if errors.As(err, &conflict) {
				conflicts++
			} else {
				t.Fatal(err)
			}
		}
		if success != 1 || conflicts != 1 {
			t.Fatal("concurrent update lost conflict", success, conflicts)
		}
	})
	t.Run("tombstones reject stale resurrection", func(t *testing.T) {
		op := operation(t)
		first, err := s.Push(ctx, owner, op)
		if err != nil {
			t.Fatal(err)
		}
		op.OperationID = id(t)
		op.BaseRevision = first.Revision
		op.Deleted = true
		deleted, err := s.Push(ctx, owner, op)
		if err != nil {
			t.Fatal(err)
		}
		op.OperationID = id(t)
		op.BaseRevision = first.Revision
		op.Deleted = false
		_, err = s.Push(ctx, owner, op)
		var conflict *Conflict
		if !errors.As(err, &conflict) || !conflict.Current.Operation.Deleted || conflict.Current.Revision != deleted.Revision {
			t.Fatal("stale resurrection was not rejected", err)
		}
	})
	t.Run("pagination preserves every operation", func(t *testing.T) {
		_, fresh := provision(t, s)
		for i := 0; i < 5; i++ {
			if _, err := s.Push(ctx, fresh, operation(t)); err != nil {
				t.Fatal(err)
			}
		}
		cursor, count := int64(0), 0
		for cursor < 5 {
			page, err := s.Pull(ctx, fresh, cursor, 2)
			if err != nil {
				t.Fatal(err)
			}
			if page.Head != 5 || len(page.Changes) == 0 {
				t.Fatal("broken pagination")
			}
			for _, change := range page.Changes {
				if change.Revision != cursor+1 {
					t.Fatal("cursor gap")
				}
				cursor = change.Revision
				count++
			}
		}
		if count != 5 {
			t.Fatal("missing changes")
		}
		if _, err := s.Pull(ctx, fresh, 6, 2); !errors.Is(err, ErrCursor) {
			t.Fatal("future cursor accepted")
		}
	})
	t.Run("account isolation", func(t *testing.T) {
		_, other := provision(t, s)
		page, err := s.Pull(ctx, other, 0, 100)
		if err != nil || len(page.Changes) != 0 {
			t.Fatal("cross-account read", err)
		}
		devices, err := s.Devices(ctx, other)
		if err != nil || len(devices) != 1 {
			t.Fatal("cross-account device list", err)
		}
		if err = s.Revoke(ctx, other, owner.DeviceID); !errors.Is(err, ErrInvalid) {
			t.Fatal("cross-account revocation", err)
		}
	})
	t.Run("revocation rejects already authenticated actor", func(t *testing.T) {
		c, err := s.AddDevice(ctx, owner, "离线设备")
		if err != nil {
			t.Fatal(err)
		}
		actor, err := s.Authenticate(ctx, c.Token)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = s.AddDevice(ctx, actor, "越权设备"); !errors.Is(err, ErrForbidden) {
			t.Fatal("member authorized device")
		}
		if err = s.Revoke(ctx, owner, c.DeviceID); err != nil {
			t.Fatal(err)
		}
		if _, err = s.Authenticate(ctx, c.Token); !errors.Is(err, ErrUnauthorized) {
			t.Fatal("revoked token accepted")
		}
		if _, err = s.Push(ctx, actor, operation(t)); !errors.Is(err, ErrUnauthorized) {
			t.Fatal("stale authorization write accepted")
		}
		if _, err = s.Pull(ctx, actor, 0, 100); !errors.Is(err, ErrUnauthorized) {
			t.Fatal("stale authorization read accepted")
		}
	})
	t.Run("credential recovery invalidates old in-flight identity", func(t *testing.T) {
		c, old := provision(t, s)
		next, err := s.RecoverDevice(ctx, c.DeviceID)
		if err != nil {
			t.Fatal(err)
		}
		if next.AccountID != c.AccountID || next.Token == c.Token {
			t.Fatal("wrong recovered identity")
		}
		if _, err = s.Authenticate(ctx, c.Token); !errors.Is(err, ErrUnauthorized) {
			t.Fatal("old token accepted")
		}
		if _, err = s.Push(ctx, old, operation(t)); !errors.Is(err, ErrUnauthorized) {
			t.Fatal("old authenticated identity survived rotation")
		}
		if _, err = s.Authenticate(ctx, next.Token); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("database never stores bearer token", func(t *testing.T) {
		c, actor := provision(t, s)
		var hash []byte
		if err := s.DB.QueryRowContext(ctx, "SELECT token_hash FROM zapime_devices WHERE id=$1", actor.DeviceID).Scan(&hash); err != nil {
			t.Fatal(err)
		}
		if len(hash) != 32 || bytes.Contains(hash, []byte(c.Token)) {
			t.Fatal("token storage is not one-way digest")
		}
	})
	t.Run("quota rejection leaves no partial revision", func(t *testing.T) {
		_, actor := provision(t, s)
		if _, err := s.DB.ExecContext(ctx, `UPDATE zapime_accounts SET stored_bytes=268435456 WHERE id=$1`, actor.AccountID); err != nil {
			t.Fatal(err)
		}
		if _, err := s.Push(ctx, actor, operation(t)); !errors.Is(err, ErrLimit) {
			t.Fatal("quota not enforced", err)
		}
		page, err := s.Pull(ctx, actor, 0, 100)
		if err != nil || page.Head != 0 || len(page.Changes) != 0 {
			t.Fatal("partial quota write", err)
		}
	})
}

func TestHTTPAndPersistentCiphertext(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	credential, actor := provision(t, s)
	server := httptest.NewServer(NewAPI(s))
	defer server.Close()
	send := func(method, path, token, body string) (int, map[string]any) {
		t.Helper()
		request, err := http.NewRequest(method, server.URL+path, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Content-Type", "application/json")
		if token != "" {
			request.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var data map[string]any
		if err = json.NewDecoder(response.Body).Decode(&data); err != nil {
			t.Fatal(err)
		}
		return response.StatusCode, data
	}
	if code, _ := send("GET", "/v1/changes", "", ""); code != 401 {
		t.Fatal("anonymous read", code)
	}
	if code, _ := send("POST", "/v1/changes", credential.Token, `{"text":"synthetic plaintext must not persist"}`); code != 400 {
		t.Fatal("plaintext accepted", code)
	}
	if code, _ := send("POST", "/v1/changes", credential.Token, strings.Repeat("x", 100001)); code != 400 {
		t.Fatal("oversize accepted", code)
	}
	op := operation(t)
	encoded, _ := json.Marshal(op)
	if code, _ := send("POST", "/v1/changes", credential.Token, string(encoded)); code != 200 {
		t.Fatal("cipher push", code)
	}
	if code, data := send("GET", "/v1/changes?after=0&limit=1", credential.Token, ""); code != 200 || len(data["changes"].([]any)) != 1 {
		t.Fatal("cipher pull", code)
	}
	if code, _ := send("GET", "/v1/changes?after=-1", credential.Token, ""); code != 400 {
		t.Fatal("invalid cursor", code)
	}
	// 使用新连接模拟进程重启，验证进度与密文不是内存中的伪实现。
	second, err := sql.Open("pgx", os.Getenv("ZAPIME_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	reopened := &Store{DB: second}
	page, err := reopened.Pull(ctx, actor, 0, 100)
	if err != nil || len(page.Changes) != 1 || page.Changes[0].Operation.Envelope != op.Envelope {
		t.Fatal("persistent ciphertext roundtrip", err)
	}
	var stored string
	if err = s.DB.QueryRowContext(ctx, `SELECT change::text FROM zapime_records WHERE account_id=$1`, actor.AccountID).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(stored, "synthetic plaintext") || strings.Contains(stored, credential.Token) {
		t.Fatal("plaintext/credential persisted")
	}
	for _, invalid := range []string{string(encoded) + " {}", strings.Replace(string(encoded), `"version":1`, `"version":9`, 1)} {
		if code, _ := send("POST", "/v1/changes", credential.Token, invalid); code != 400 {
			t.Fatal(fmt.Sprint("invalid JSON accepted: ", code))
		}
	}
}
