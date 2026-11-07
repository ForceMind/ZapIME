// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
)

func descriptor(t *testing.T) KeyDescriptor {
	t.Helper()
	sum := sha256.Sum256([]byte("synthetic public descriptor " + id(t)))
	return KeyDescriptor{1, id(t), hex.EncodeToString(sum[:])}
}

func TestKeyDescriptorBoundary(t *testing.T) {
	valid := descriptor(t)
	raw, err := json.Marshal(valid)
	if err != nil {
		t.Fatal(err)
	}
	decode := func(body, query, kind string) (KeyDescriptor, error) {
		request := httptest.NewRequest(http.MethodPut, "/v1/key-descriptor"+query, strings.NewReader(body))
		request.Header.Set("Content-Type", kind)
		return decodeKeyDescriptor(httptest.NewRecorder(), request)
	}
	for _, body := range []string{string(raw), string(raw) + strings.Repeat(" ", maxKeyDescriptorBytes-len(raw))} {
		got, err := decode(body, "", "application/json")
		if err != nil || got != valid {
			t.Fatal("valid descriptor rejected", err)
		}
	}
	for name, body := range map[string]string{
		"empty": "", "null": "null", "array": "[]", "missing": `{"version":1}`,
		"version string":    strings.Replace(string(raw), `"version":1`, `"version":"1"`, 1),
		"version float":     strings.Replace(string(raw), `"version":1`, `"version":1.0`, 1),
		"version future":    strings.Replace(string(raw), `"version":1`, `"version":2`, 1),
		"nil key":           strings.Replace(string(raw), valid.KeyID, "00000000-0000-0000-0000-000000000000", 1),
		"uppercase key":     strings.Replace(string(raw), valid.KeyID, "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", 1),
		"short digest":      strings.Replace(string(raw), valid.MaterialSHA256, "00", 1),
		"uppercase digest":  strings.Replace(string(raw), valid.MaterialSHA256, "A"+valid.MaterialSHA256[1:], 1),
		"digest type":       strings.Replace(string(raw), `"`+valid.MaterialSHA256+`"`, `[]`, 1),
		"unknown":           strings.TrimSuffix(string(raw), "}") + `,"account_id":"` + id(t) + `"}`,
		"duplicate":         strings.TrimSuffix(string(raw), "}") + `,"key_id":"` + valid.KeyID + `"}`,
		"escaped duplicate": strings.TrimSuffix(string(raw), "}") + `,"\u006bey_id":"` + valid.KeyID + `"}`,
		"trailing object":   string(raw) + " {}", "trailing scalar": string(raw) + " null",
		"oversized": string(raw) + strings.Repeat(" ", maxKeyDescriptorBytes-len(raw)+1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := decode(body, "", "application/json"); !errors.Is(err, ErrInvalid) {
				t.Fatal("invalid descriptor accepted", err)
			}
		})
	}
	for _, query := range []string{"?", "?account_id=" + id(t), "?key_id=" + valid.KeyID} {
		if _, err = decode(string(raw), query, "application/json"); !errors.Is(err, ErrInvalid) {
			t.Fatal("query accepted", err)
		}
	}
	for _, kind := range []string{"", "text/plain", "application/json; charset=utf-8"} {
		if _, err = decode(string(raw), "", kind); !errors.Is(err, ErrInvalid) {
			t.Fatal("content type accepted", err)
		}
	}
}

func publicState(t *testing.T, state KeyDescriptorState, expected KeyDescriptor) {
	t.Helper()
	if state.Protocol != 1 || !validID(state.AccountID) || state.State != "registered" || state.Descriptor == nil || *state.Descriptor != expected || state.RegisteredBy == nil || !validID(*state.RegisteredBy) || state.RegisteredAt == nil || state.RegisteredAt.IsZero() {
		t.Fatal("invalid registered state")
	}
}
func stateBytes(t *testing.T, state KeyDescriptorState) []byte {
	t.Helper()
	b, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func accountRevision(t *testing.T, s *Store, account string) (int64, int64) {
	t.Helper()
	var head, size int64
	if err := s.DB.QueryRowContext(context.Background(), `SELECT revision,stored_bytes FROM zapime_accounts WHERE id=$1`, account).Scan(&head, &size); err != nil {
		t.Fatal(err)
	}
	return head, size
}
func secondOwner(t *testing.T, s *Store, owner Identity) (Credential, Identity) {
	t.Helper()
	c, err := s.AddDevice(context.Background(), owner, "synthetic second owner")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.DB.Exec(`UPDATE zapime_devices SET role='owner' WHERE id=$1`, c.DeviceID); err != nil {
		t.Fatal(err)
	}
	actor, err := s.Authenticate(context.Background(), c.Token)
	if err != nil {
		t.Fatal(err)
	}
	return c, actor
}

func TestPostgresKeyDescriptorCreateOnceAndPushGate(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	_, owner := provision(t, s)
	input := descriptor(t)
	// Only this account uses an in-memory backend. Remove its own references
	// even when an assertion fails, before whole-database real S3 exercises.
	t.Cleanup(func() {
		for _, table := range []string{"zapime_operations", "zapime_records", "zapime_account_keys", "zapime_devices", "zapime_accounts"} {
			column := "account_id"
			if table == "zapime_accounts" {
				column = "id"
			}
			if _, err := s.DB.Exec("DELETE FROM "+table+" WHERE "+column+"=$1", owner.AccountID); err != nil {
				t.Error(err)
			}
		}
	})
	objects := &countedObjects{testObjects: testObjects{data: map[string][]byte{}}}
	s.Objects = objects
	first, err := s.KeyDescriptor(ctx, owner)
	if err != nil || first.State != "empty" || first.Descriptor != nil || first.RegisteredBy != nil || first.RegisteredAt != nil {
		t.Fatal("fresh state", err)
	}
	first, err = s.RegisterKeyDescriptor(ctx, owner, input)
	if err != nil {
		t.Fatal(err)
	}
	publicState(t, first, input)
	if *first.RegisteredBy != owner.DeviceID {
		t.Fatal("wrong initial registering device")
	}
	_, otherOwner := secondOwner(t, s, owner)
	retry, err := s.RegisterKeyDescriptor(ctx, otherOwner, input)
	if err != nil || !bytes.Equal(stateBytes(t, first), stateBytes(t, retry)) {
		t.Fatal("idempotent registration changed identity or timestamp", err)
	}
	wrong := descriptor(t)
	for _, candidate := range []KeyDescriptor{wrong, {1, input.KeyID, wrong.MaterialSHA256}, {1, wrong.KeyID, input.MaterialSHA256}} {
		if _, err = s.RegisterKeyDescriptor(ctx, owner, candidate); !errors.Is(err, ErrKeyDescriptorConflict) {
			t.Fatal("different descriptor replaced first", err)
		}
	}
	if head, size := accountRevision(t, s, owner.AccountID); head != 0 || size != 0 {
		t.Fatal("registration changed change revision/quota")
	}
	if objects.gets != 0 || objects.puts != 0 || objects.probes != 0 {
		t.Fatal("descriptor touched object storage")
	}
	good := operation(t)
	good.KeyID = input.KeyID
	accepted, err := s.Push(ctx, owner, good)
	if err != nil || accepted.Revision != 1 {
		t.Fatal("registered-key push failed", err)
	}
	again, err := s.Push(ctx, owner, good)
	if err != nil || again != accepted {
		t.Fatal("existing immutable replay failed", err)
	}
	beforeHead, beforeSize := accountRevision(t, s, owner.AccountID)
	beforeGets, beforePuts := objects.gets, objects.puts
	bad := operation(t)
	if _, err = s.Push(ctx, owner, bad); !errors.Is(err, ErrKeyDescriptorMismatch) {
		t.Fatal("old push route bypassed registered key", err)
	}
	if head, size := accountRevision(t, s, owner.AccountID); head != beforeHead || size != beforeSize {
		t.Fatal("mismatch mutated revision/quota")
	}
	if objects.gets != beforeGets || objects.puts != beforePuts {
		t.Fatal("mismatch touched object storage")
	}
	good.Deleted = true
	if _, err = s.Push(ctx, owner, good); !errors.Is(err, ErrOperationReused) {
		t.Fatal("registration relaxed old immutable replay check", err)
	}
	after, err := s.KeyDescriptor(ctx, owner)
	if err != nil || !bytes.Equal(stateBytes(t, first), stateBytes(t, after)) {
		t.Fatal("push changed descriptor", err)
	}
}

func TestPostgresKeyDescriptorConcurrentOwners(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	for _, same := range []bool{false, true} {
		t.Run(fmt.Sprintf("same_%t", same), func(t *testing.T) {
			_, first := provision(t, s)
			_, second := secondOwner(t, s, first)
			a, b := descriptor(t), descriptor(t)
			if same {
				b = a
			}
			start := make(chan struct{})
			results := make(chan error, 2)
			var wg sync.WaitGroup
			for index, actor := range []Identity{first, second} {
				input := []KeyDescriptor{a, b}[index]
				wg.Add(1)
				go func(actor Identity, input KeyDescriptor) {
					defer wg.Done()
					<-start
					_, err := s.RegisterKeyDescriptor(ctx, actor, input)
					results <- err
				}(actor, input)
			}
			close(start)
			wg.Wait()
			close(results)
			success, conflicts := 0, 0
			for err := range results {
				if err == nil {
					success++
				} else if errors.Is(err, ErrKeyDescriptorConflict) {
					conflicts++
				} else {
					t.Fatal(err)
				}
			}
			if same && (success != 2 || conflicts != 0) || !same && (success != 1 || conflicts != 1) {
				t.Fatal("CAS did not select exactly one identity", success, conflicts)
			}
			state, err := s.KeyDescriptor(ctx, first)
			if err != nil || state.Descriptor == nil || (*state.Descriptor != a && *state.Descriptor != b) {
				t.Fatal("missing winning descriptor", err)
			}
			if head, size := accountRevision(t, s, first.AccountID); head != 0 || size != 0 {
				t.Fatal("CAS changed history")
			}
		})
	}
}

func TestPostgresKeyDescriptorLegacyAndWriteRace(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	t.Run("existing history stays legacy and old API works", func(t *testing.T) {
		_, owner := provision(t, s)
		op := operation(t)
		first, err := s.Push(ctx, owner, op)
		if err != nil {
			t.Fatal(err)
		}
		beforeHead, beforeSize := accountRevision(t, s, owner.AccountID)
		state, err := s.KeyDescriptor(ctx, owner)
		if err != nil || state.State != "legacy" || state.Descriptor != nil {
			t.Fatal("historical account guessed a key", err)
		}
		if _, err = s.RegisterKeyDescriptor(ctx, owner, descriptor(t)); !errors.Is(err, ErrKeyDescriptorLegacy) {
			t.Fatal("legacy registration accepted", err)
		}
		if head, size := accountRevision(t, s, owner.AccountID); head != beforeHead || size != beforeSize {
			t.Fatal("legacy rejection mutated history")
		}
		if replay, err := s.Push(ctx, owner, op); err != nil || replay != first {
			t.Fatal("legacy retry compatibility lost", err)
		}
		op = operation(t)
		if _, err = s.Push(ctx, owner, op); err != nil {
			t.Fatal("unregistered old client lost write compatibility", err)
		}
	})
	for _, kind := range []string{"quota only", "rows despite zero head"} {
		t.Run(kind, func(t *testing.T) {
			_, owner := provision(t, s)
			if kind == "quota only" {
				if _, err := s.DB.Exec(`UPDATE zapime_accounts SET stored_bytes=1 WHERE id=$1`, owner.AccountID); err != nil {
					t.Fatal(err)
				}
			} else {
				if _, err := s.Push(ctx, owner, operation(t)); err != nil {
					t.Fatal(err)
				}
				if _, err := s.DB.Exec(`UPDATE zapime_accounts SET revision=0,stored_bytes=0 WHERE id=$1`, owner.AccountID); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := s.RegisterKeyDescriptor(ctx, owner, descriptor(t)); !errors.Is(err, ErrKeyDescriptorLegacy) {
				t.Fatal("head zero alone permitted registration", err)
			}
		})
	}
	t.Run("register serialized with original push route", func(t *testing.T) {
		_, owner := provision(t, s)
		input := descriptor(t)
		op := operation(t)
		start := make(chan struct{})
		var registration, write error
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); <-start; _, registration = s.RegisterKeyDescriptor(ctx, owner, input) }()
		go func() { defer wg.Done(); <-start; _, write = s.Push(ctx, owner, op) }()
		close(start)
		wg.Wait()
		if !(registration == nil && errors.Is(write, ErrKeyDescriptorMismatch) || write == nil && errors.Is(registration, ErrKeyDescriptorLegacy)) {
			t.Fatal("register/push race admitted split key identities", registration, write)
		}
	})
}

func TestPostgresKeyDescriptorAuthorizationAndHTTP(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	owner, actor := provision(t, s)
	other, _ := provision(t, s)
	member, err := s.AddDevice(ctx, actor, "synthetic member")
	if err != nil {
		t.Fatal(err)
	}
	memberActor, err := s.Authenticate(ctx, member.Token)
	if err != nil {
		t.Fatal(err)
	}
	input := descriptor(t)
	if _, err = s.RegisterKeyDescriptor(ctx, memberActor, input); !errors.Is(err, ErrForbidden) {
		t.Fatal("member registration accepted", err)
	}
	if _, err = s.RegisterKeyDescriptor(ctx, actor, input); err != nil {
		t.Fatal(err)
	}
	if state, err := s.KeyDescriptor(ctx, memberActor); err != nil || state.Descriptor == nil || *state.Descriptor != input {
		t.Fatal("member could not compare imported key", err)
	}
	server := httptest.NewServer(NewAPI(s))
	defer server.Close()
	call := func(method, path, token string, body []byte) (int, []byte) {
		t.Helper()
		req, err := http.NewRequest(method, server.URL+path, bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		wire, err := io.ReadAll(io.LimitReader(response.Body, 4097))
		if err != nil || len(wire) > 4096 {
			t.Fatal("unbounded key descriptor response", err)
		}
		if response.Header.Get("Cache-Control") != "no-store" || bytes.Contains(wire, []byte(owner.Token)) || bytes.Contains(wire, []byte(member.Token)) {
			t.Fatal("descriptor response exposed credential/cache")
		}
		return response.StatusCode, wire
	}
	for _, token := range []string{"", "invalid"} {
		if code, _ := call(http.MethodGet, "/v1/key-descriptor", token, nil); code != 401 {
			t.Fatal("unauthenticated descriptor read")
		}
	}
	for _, c := range []Credential{owner, member} {
		code, wire := call(http.MethodGet, "/v1/key-descriptor", c.Token, nil)
		var fields map[string]json.RawMessage
		json.Unmarshal(wire, &fields)
		if code != 200 || len(fields) != 6 {
			t.Fatal("descriptor response shape")
		}
		var state KeyDescriptorState
		if err = json.Unmarshal(wire, &state); err != nil {
			t.Fatal(err)
		}
		publicState(t, state, input)
		if state.AccountID != owner.AccountID {
			t.Fatal("descriptor crossed account")
		}
	}
	if code, wire := call(http.MethodGet, "/v1/key-descriptor", other.Token, nil); code != 200 || bytes.Contains(wire, []byte(input.KeyID)) {
		t.Fatal("cross-account descriptor leak")
	}
	raw, _ := json.Marshal(input)
	if code, _ := call(http.MethodPut, "/v1/key-descriptor", member.Token, raw); code != 403 {
		t.Fatal("member HTTP registration accepted")
	}
	if code, _ := call(http.MethodPut, "/v1/key-descriptor", owner.Token, raw); code != 200 {
		t.Fatal("same-key HTTP retry failed")
	}
	if code, _ := call(http.MethodGet, "/v1/key-descriptor?account_id="+other.AccountID, owner.Token, nil); code != 400 {
		t.Fatal("query account selector accepted")
	}
	if code, _ := call(http.MethodGet, "/v1/key-descriptor", owner.Token, []byte("{}")); code != 400 {
		t.Fatal("GET body accepted")
	}
	if code, _ := call(http.MethodPut, "/v1/key-descriptor", owner.Token, append(raw, []byte(" {}")...)); code != 400 {
		t.Fatal("trailing descriptor JSON accepted")
	}
	if err = s.Revoke(ctx, actor, member.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err = s.KeyDescriptor(ctx, memberActor); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("preauthenticated revoked actor read descriptor", err)
	}
	if _, err = s.RegisterKeyDescriptor(ctx, memberActor, input); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("preauthenticated revoked actor registered descriptor", err)
	}
	if code, _ := call(http.MethodGet, "/v1/key-descriptor", member.Token, nil); code != 401 {
		t.Fatal("revoked HTTP read accepted")
	}
	recovered, err := s.RecoverDevice(ctx, owner.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.KeyDescriptor(ctx, actor); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("old token actor read after rotation", err)
	}
	if _, err = s.RegisterKeyDescriptor(ctx, actor, input); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("old token actor registered after rotation", err)
	}
	fresh, err := s.Authenticate(ctx, recovered.Token)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.RegisterKeyDescriptor(ctx, fresh, input); err != nil {
		t.Fatal("fresh rotated credential cannot retry", err)
	}
	if _, err = s.DB.Exec(`UPDATE zapime_devices SET expires_at=now()-interval '1 second' WHERE id=$1`, fresh.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err = s.KeyDescriptor(ctx, fresh); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("preauthenticated expired actor read", err)
	}
}

// Only this test's random schema is created/dropped. No public tables or other
// test accounts are reset to manufacture a migration result.
func migrationDatabase(t *testing.T) *Store {
	t.Helper()
	parent := database(t)
	schema := "key_migration_" + strings.ReplaceAll(id(t), "-", "")
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err := parent.DB.Exec("CREATE SCHEMA " + quoted); err != nil {
		t.Fatal(err)
	}
	config, err := pgx.ParseConfig(os.Getenv("ZAPIME_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	config.RuntimeParams["search_path"] = schema
	db := stdlib.OpenDB(*config)
	t.Cleanup(func() {
		db.Close()
		if _, err := parent.DB.Exec("DROP SCHEMA " + quoted + " CASCADE"); err != nil {
			t.Error(err)
		}
	})
	s := &Store{DB: db}
	if err = s.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	return s
}
func TestPostgresKeyDescriptorSchemaMigration(t *testing.T) {
	ctx := context.Background()
	for _, old := range []int{1, 2} {
		t.Run(fmt.Sprintf("from_%d", old), func(t *testing.T) {
			s := migrationDatabase(t)
			_, actor := provision(t, s)
			original, err := s.Push(ctx, actor, operation(t))
			if err != nil {
				t.Fatal(err)
			}
			if _, err = s.DB.Exec(`DROP TABLE zapime_account_keys`); err != nil {
				t.Fatal(err)
			}
			if _, err = s.DB.Exec(`UPDATE zapime_schema SET version=$1`, old); err != nil {
				t.Fatal(err)
			}
			if err = s.Migrate(ctx); err != nil {
				t.Fatal(err)
			}
			if err = s.Migrate(ctx); err != nil {
				t.Fatal(err)
			}
			var version int
			if err = s.DB.QueryRow(`SELECT version FROM zapime_schema WHERE id=1`).Scan(&version); err != nil || version != 3 {
				t.Fatal("schema3 not persisted", err)
			}
			page, err := s.Pull(ctx, actor, 0, 100)
			if err != nil || len(page.Changes) != 1 || page.Changes[0] != original {
				t.Fatal("migration lost immutable history", err)
			}
			state, err := s.KeyDescriptor(ctx, actor)
			if err != nil || state.State != "legacy" || state.Descriptor != nil {
				t.Fatal("migration guessed old root", err)
			}
			if _, err = s.RegisterKeyDescriptor(ctx, actor, descriptor(t)); !errors.Is(err, ErrKeyDescriptorLegacy) {
				t.Fatal("old history adopted arbitrary root", err)
			}
			if _, err = s.DB.Exec(`UPDATE zapime_schema SET version=4`); err != nil {
				t.Fatal(err)
			}
			if err = s.Migrate(ctx); err == nil {
				t.Fatal("future schema downgraded")
			}
			if err = s.DB.QueryRow(`SELECT version FROM zapime_schema WHERE id=1`).Scan(&version); err != nil || version != 4 {
				t.Fatal("future version changed", err)
			}
		})
	}
}
