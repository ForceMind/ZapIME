// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func consoleSyntheticMaterial(t *testing.T, account string) []byte {
	t.Helper()
	key := id(t)
	a, _ := consoleUUIDBytes(account)
	k, _ := consoleUUIDBytes(key)
	material := make([]byte, 72)
	copy(material, []byte{'Z', 'S', 'K', 'M', 0, 0, 0, 1})
	copy(material[8:24], a)
	copy(material[24:40], k)
	for i := 40; i < 72; i++ {
		material[i] = byte(i)
	}
	return material
}
func consoleTestVault(t *testing.T) *ConsoleKeyVault {
	t.Helper()
	vault, err := NewConsoleKeyVault(bytes.Repeat([]byte{0x42}, 32))
	if err != nil {
		t.Fatal(err)
	}
	return vault
}

func TestConsoleMaterialVaultBinding(t *testing.T) {
	account := id(t)
	material := consoleSyntheticMaterial(t, account)
	defer clear(material)
	vault := consoleTestVault(t)
	sealed, err := vault.seal(account, material)
	if err != nil {
		t.Fatal(err)
	}
	if len(sealed) != 100 || bytes.Contains(sealed, material) {
		t.Fatal("invalid protected envelope")
	}
	opened, err := vault.open(account, sealed)
	if err != nil || !bytes.Equal(material, opened) {
		t.Fatal("vault roundtrip", err)
	}
	clear(opened)
	if _, err = vault.open(id(t), sealed); !errors.Is(err, ErrConsoleKeyUnavailable) {
		t.Fatal("cross-account material accepted", err)
	}
	sealed[len(sealed)-1] ^= 1
	if _, err = vault.open(account, sealed); !errors.Is(err, ErrConsoleKeyUnavailable) {
		t.Fatal("tampered material accepted", err)
	}
	other, _ := NewConsoleKeyVault(bytes.Repeat([]byte{0x43}, 32))
	if _, err = other.open(account, sealed); !errors.Is(err, ErrConsoleKeyUnavailable) {
		t.Fatal("wrong master accepted", err)
	}
	if _, err = NewConsoleKeyVault(make([]byte, 31)); !errors.Is(err, ErrInvalid) {
		t.Fatal("invalid master accepted")
	}
}

func TestConsoleRecordRustWireBinding(t *testing.T) {
	account := id(t)
	material := consoleSyntheticMaterial(t, account)
	defer clear(material)
	descriptor, err := consoleMaterialDescriptor(account, material)
	if err != nil {
		t.Fatal(err)
	}
	op := Operation{OperationID: id(t), RecordID: id(t), KeyID: descriptor.KeyID, Kind: 1}
	record := ConsoleRecord{ID: op.RecordID, Revision: 1, DeviceID: id(t), Content: ConsoleContent{Type: "dictionary", Scheme: "pinyin", Code: "ceshi", Text: "合成词条", Weight: 0}}
	op.Envelope, err = consoleSealRecord(account, material, op, record)
	if err != nil || op.Validate() != nil {
		t.Fatal("seal", err)
	}
	change := Change{AccountID: account, DeviceID: record.DeviceID, Revision: 1, Operation: op}
	got, err := consoleOpenRecord(account, material, change)
	if err != nil || got != record {
		t.Fatal("record roundtrip", err)
	}
	body, err := json.Marshal(record)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(body), `"weight":0`) || strings.Contains(string(body), "server_revision") {
		t.Fatal("not the Rust record wire")
	}
	aad, err := consoleRecordAAD(account, op)
	if err != nil {
		t.Fatal(err)
	}
	if len(aad) != len("ZapIME/record/v1")+65 || string(aad[:len("ZapIME/record/v1")]) != "ZapIME/record/v1" || aad[len(aad)-1] != 1 {
		t.Fatal("incompatible Rust AAD")
	}
	for _, mutate := range []func(*Change){
		func(c *Change) { c.AccountID = id(t) }, func(c *Change) { c.Operation.RecordID = id(t) }, func(c *Change) { c.Operation.OperationID = id(t) }, func(c *Change) { c.Operation.KeyID = id(t) }, func(c *Change) { c.Operation.Kind = 5 }, func(c *Change) { c.Operation.Deleted = true },
		func(c *Change) {
			ct, _ := base64.RawURLEncoding.DecodeString(c.Operation.Envelope.Ciphertext)
			ct[0] ^= 1
			c.Operation.Envelope.Ciphertext = base64.RawURLEncoding.EncodeToString(ct)
		},
	} {
		altered := change
		mutate(&altered)
		if _, err := consoleOpenRecord(account, material, altered); err == nil {
			t.Fatal("changed binding accepted")
		}
	}
}

func TestConsoleStrictContent(t *testing.T) {
	for _, body := range []string{
		`{"type":"dictionary","scheme":"pinyin","code":"ceshi","text":"合成","weight":0,"text":"覆盖"}`,
		`{"type":"dictionary","scheme":"pinyin","code":"ceshi","text":"合成"}`,
		`{"type":"dictionary","scheme":"pinyin","code":"ceshi","text":"合成","weight":null}`,
		`{"type":"dictionary","scheme":"other","code":"ceshi","text":"合成","weight":0}`,
		`{"type":"dictionary","scheme":"pinyin","code":"UPPER","text":"合成","weight":0}`,
		`{"type":"clipboard","text":"合成","expires_at_ms":1}`,
		`{"type":"clipboard","text":"合成","expires_at_ms":0,"pinned":false}`,
		`{"type":"clipboard","text":"合成","expires_at_ms":1,"pinned":null}`,
		`{"type":"clipboard","text":"合成","expires_at_ms":1,"pinned":false,"scheme":"pinyin"}`,
		`{"type":"favorite","text":"合成"}`,
	} {
		var c ConsoleContent
		if json.Unmarshal([]byte(body), &c) == nil {
			t.Fatal("invalid content accepted")
		}
	}
}

// Optional native interoperability gate; a roundtrip inside the Go port alone
// cannot prove that the real Rust authentication/storage boundary accepts it.
func TestConsoleRustClientInterop(t *testing.T) {
	fixture := os.Getenv("ZAPIME_RUST_SYNC_FIXTURE")
	if fixture == "" {
		t.Skip("set ZAPIME_RUST_SYNC_FIXTURE to the compiled manual_sync_fixture")
	}
	if !filepath.IsAbs(fixture) {
		t.Fatal("absolute native fixture required")
	}
	dir := t.TempDir()
	account := id(t)
	run := func(args ...string) []byte {
		t.Helper()
		cmd := exec.Command(fixture, args...)
		body, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatal("native fixture failed", err)
		}
		return body
	}
	run("init-foreground", dir, account)
	material, err := os.ReadFile(filepath.Join(dir, "material.bin"))
	if err != nil {
		t.Fatal(err)
	}
	defer clear(material)
	descriptor, err := consoleMaterialDescriptor(account, material)
	if err != nil {
		t.Fatal(err)
	}
	device := id(t)
	now := time.Now().UTC()
	state := keyDescriptorState(account, &registeredKeyDescriptor{Descriptor: descriptor, RegisteredBy: device, RegisteredAt: now})
	writeFixture := func(name string, value any) string {
		t.Helper()
		body, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(dir, name)
		if err = os.WriteFile(path, body, 0600); err != nil {
			t.Fatal(err)
		}
		return path
	}
	registered := writeFixture("registered.json", state)
	page := Page{Head: 2, NextCursor: 2}
	for index, content := range []ConsoleContent{{Type: "dictionary", Scheme: "pinyin", Code: "ceshi", Text: "合成跨语言词条", Weight: 0}, {Type: "clipboard", Text: "合成跨语言剪贴板", ExpiresAtMS: uint64(now.Add(time.Hour).UnixMilli())}} {
		op := Operation{OperationID: id(t), RecordID: id(t), KeyID: descriptor.KeyID, Kind: content.kind()}
		record := ConsoleRecord{ID: op.RecordID, Revision: 1, DeviceID: device, Content: content}
		op.Envelope, err = consoleSealRecord(account, material, op, record)
		if err != nil {
			t.Fatal(err)
		}
		page.Changes = append(page.Changes, Change{AccountID: account, DeviceID: device, Revision: int64(index + 1), Operation: op})
	}
	response := run("apply", dir, "b", registered, writeFixture("page.json", page))
	if !bytes.Contains(response, []byte(`"applied":2`)) {
		t.Fatal("Rust did not authenticate and apply both console record kinds")
	}
}

func consoleRecordsActor(t *testing.T, s *Store) (*API, ConsoleActor) {
	t.Helper()
	ctx := context.Background()
	_, device := provision(t, s)
	issuer := "https://console.synthetic.invalid"
	subject := "console-records-" + id(t)
	if err := s.BindOIDCSubject(ctx, device.AccountID, issuer, subject); err != nil {
		t.Fatal(err)
	}
	device.Role = "member"
	if _, err := s.DB.ExecContext(ctx, `UPDATE zapime_devices SET role='member' WHERE id=$1`, device.DeviceID); err != nil {
		t.Fatal(err)
	}
	session := consoleHash(id(t))
	csrf := consoleHash(id(t))
	if _, err := s.DB.ExecContext(ctx, `INSERT INTO zapime_console_sessions(session_hash,csrf_hash,account_id,device_id,device_token_hash,issuer,subject,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,now()+interval '15 minutes')`, session, csrf, device.AccountID, device.DeviceID, device.tokenHash, issuer, subject); err != nil {
		t.Fatal(err)
	}
	api := NewAPI(s)
	api.Console = &Console{Admins: map[string]bool{}}
	return api, ConsoleActor{AccountID: device.AccountID, Issuer: issuer, Subject: subject, Device: device, sessionHash: session}
}

func TestPostgresConsoleRecordLifecycle(t *testing.T) {
	s := database(t)
	api, actor := consoleRecordsActor(t, s)
	vault := consoleTestVault(t)
	ctx := context.Background()
	state, err := api.ConsoleEnableMaterial(ctx, actor, vault)
	if err != nil || state.State != "registered" {
		t.Fatal("enable", err)
	}
	in := ConsoleRecordInput{OperationID: id(t), RecordID: id(t), Content: ConsoleContent{Type: "dictionary", Scheme: "pinyin", Code: "ceshi", Text: "仅合成资料", Weight: 7}}
	first, err := api.ConsoleWriteRecord(ctx, actor, vault, in)
	if err != nil || first.ServerRevision != 1 {
		t.Fatal("create", err)
	}
	retry, err := api.ConsoleWriteRecord(ctx, actor, vault, in)
	if err != nil || retry != first {
		t.Fatal("immutable retry", err)
	}
	stale := in
	stale.OperationID = id(t)
	stale.Content.Text = "合成新编辑"
	if _, err = api.ConsoleWriteRecord(ctx, actor, vault, stale); err == nil {
		t.Fatal("stale CAS accepted")
	}
	duplicate := in
	duplicate.OperationID = id(t)
	duplicate.RecordID = id(t)
	if _, err = api.ConsoleWriteRecord(ctx, actor, vault, duplicate); !errors.Is(err, ErrConsoleDictionaryDuplicate) {
		t.Fatal("duplicate dictionary accepted", err)
	}
	page, err := api.ConsoleRecords(ctx, actor, actor.AccountID, vault, "dictionary", "", 10, false)
	if err != nil || len(page.Records) != 1 || page.Records[0] != first {
		t.Fatal("list", err)
	}
	clip := ConsoleRecordInput{OperationID: id(t), RecordID: id(t), Content: ConsoleContent{Type: "clipboard", Text: "合成剪贴板", ExpiresAtMS: uint64(time.Now().Add(time.Hour).UnixMilli())}}
	saved, err := api.ConsoleWriteRecord(ctx, actor, vault, clip)
	if err != nil {
		t.Fatal("clipboard", err)
	}
	clip.OperationID = id(t)
	clip.BaseRevision = saved.ServerRevision
	clip.Content.Pinned = true
	pinned, err := api.ConsoleWriteRecord(ctx, actor, vault, clip)
	if err != nil {
		t.Fatal("pin", err)
	}
	extended := clip
	extended.OperationID = id(t)
	extended.BaseRevision = pinned.ServerRevision
	extended.Content.ExpiresAtMS++
	if _, err = api.ConsoleWriteRecord(ctx, actor, vault, extended); !errors.Is(err, ErrInvalid) {
		t.Fatal("pin extended expiry", err)
	}
	clip.OperationID = id(t)
	clip.BaseRevision = pinned.ServerRevision
	clip.Deleted = true
	tombstone, err := api.ConsoleWriteRecord(ctx, actor, vault, clip)
	if err != nil {
		t.Fatal("delete", err)
	}
	clip.OperationID = id(t)
	clip.BaseRevision = tombstone.ServerRevision
	clip.Deleted = false
	if _, err = api.ConsoleWriteRecord(ctx, actor, vault, clip); err == nil {
		t.Fatal("tombstone resurrected")
	}
	page, err = api.ConsoleRecords(ctx, actor, actor.AccountID, vault, "clipboard", "", 10, false)
	if err != nil || len(page.Records) != 0 {
		t.Fatal("tombstone displayed", err)
	}
	_, admin := consoleRecordsActor(t, s)
	api.Console.Admins[admin.Issuer+"\x00"+admin.Subject] = true
	page, err = api.ConsoleRecords(ctx, admin, actor.AccountID, vault, "dictionary", "", 10, true)
	if err != nil || len(page.Records) != 1 {
		t.Fatal("admin view", err)
	}
	var audits int
	if err = s.DB.QueryRowContext(ctx, `SELECT count(*) FROM zapime_console_audit WHERE actor_account_id=$1 AND target_account_id=$2 AND action='records.view.dictionary'`, admin.AccountID, actor.AccountID).Scan(&audits); err != nil || audits != 1 {
		t.Fatal("admin view not audited", err)
	}
	if _, err = api.ConsoleRecords(ctx, admin, admin.AccountID, vault, "dictionary", "", 10, true); err != nil {
		t.Fatal("admin own view", err)
	}
	if err = s.DB.QueryRowContext(ctx, `SELECT count(*) FROM zapime_console_audit WHERE actor_account_id=$1 AND target_account_id=$1 AND action='records.view.dictionary'`, admin.AccountID).Scan(&audits); err != nil || audits != 1 {
		t.Fatal("admin own view not audited", err)
	}
	if _, _, err = api.consoleLock(ctx, admin, actor.AccountID, true); !errors.Is(err, ErrForbidden) {
		t.Fatal("admin target write authorized", err)
	}
	if _, err = s.DB.ExecContext(ctx, `UPDATE zapime_accounts SET disabled_at=now() WHERE id=$1`, actor.AccountID); err != nil {
		t.Fatal(err)
	}
	if _, err = api.ConsoleWriteRecord(ctx, actor, vault, in); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("disabled stale actor wrote", err)
	}
}

func TestPostgresConsoleMaterialMigration(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	vault := consoleTestVault(t)
	_, owner := provision(t, s)
	material := consoleSyntheticMaterial(t, owner.AccountID)
	defer clear(material)
	descriptor, err := consoleMaterialDescriptor(owner.AccountID, material)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.RegisterKeyDescriptor(ctx, owner, descriptor); err != nil {
		t.Fatal(err)
	}
	if _, err = s.ConsoleDeviceMaterial(ctx, owner, vault, "enable", nil); !errors.Is(err, ErrConsoleKeyRequired) {
		t.Fatal("existing account silently replaced", err)
	}
	wrong := bytes.Clone(material)
	wrong[71] ^= 1
	defer clear(wrong)
	if _, err = s.ConsoleDeviceMaterial(ctx, owner, vault, "import", wrong); !errors.Is(err, ErrKeyDescriptorMismatch) {
		t.Fatal("wrong root imported", err)
	}
	got, err := s.ConsoleDeviceMaterial(ctx, owner, vault, "import", material)
	if err != nil || !bytes.Equal(got, material) {
		t.Fatal("matching material not migrated", err)
	}
	clear(got)
	got, err = s.ConsoleDeviceMaterial(ctx, owner, vault, "enable", nil)
	if err != nil || !bytes.Equal(got, material) {
		t.Fatal("registered material not reused", err)
	}
	clear(got)
	if _, err = s.ConsoleDeviceMaterial(ctx, owner, vault, "import", wrong); !errors.Is(err, ErrKeyDescriptorMismatch) {
		t.Fatal("existing root overwritten", err)
	}
	_, legacy := provision(t, s)
	if _, err = s.Push(ctx, legacy, operation(t)); err != nil {
		t.Fatal(err)
	}
	if _, err = s.ConsoleDeviceMaterial(ctx, legacy, vault, "enable", nil); !errors.Is(err, ErrKeyDescriptorLegacy) {
		t.Fatal("legacy history reset", err)
	}
}

func TestPostgresConsoleLegacyKeyState(t *testing.T) {
	s := database(t)
	api, actor := consoleRecordsActor(t, s)
	ctx := context.Background()
	vault := consoleTestVault(t)
	if _, err := s.Push(ctx, actor.Device, operation(t)); err != nil {
		t.Fatal(err)
	}
	page, err := api.ConsoleRecords(ctx, actor, actor.AccountID, vault, "dictionary", "", 10, false)
	if err != nil || page.KeyState != "legacy" || len(page.Records) != 0 {
		t.Fatal("legacy history presented as empty account", err)
	}
	if _, err = api.ConsoleEnableMaterial(ctx, actor, vault); !errors.Is(err, ErrKeyDescriptorLegacy) {
		t.Fatal("legacy history root reset", err)
	}
}
