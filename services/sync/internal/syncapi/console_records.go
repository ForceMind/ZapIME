// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"math"
	"strings"
	"time"
	"unicode/utf8"
)

const consoleRecordsSchema = `CREATE TABLE IF NOT EXISTS zapime_console_keys(
 account_id uuid PRIMARY KEY REFERENCES zapime_accounts(id),
 encrypted_material bytea NOT NULL CHECK(octet_length(encrypted_material)=100),
 registered_by uuid NOT NULL REFERENCES zapime_devices(id),
 registered_at timestamptz NOT NULL DEFAULT now());`

var (
	ErrConsoleRecordExpired = errors.New("record_expired")
	ErrConsoleRecordDeleted = errors.New("record_deleted")
)

// ConsoleContent is the exact existing Rust Content subset, not a new sync kind.
type ConsoleContent struct {
	Type        string
	Scheme      string
	Code        string
	Text        string
	Weight      int64
	ExpiresAtMS uint64
	Pinned      bool
}

type consoleDictionary struct {
	Type   string `json:"type"`
	Scheme string `json:"scheme"`
	Code   string `json:"code"`
	Text   string `json:"text"`
	Weight int64  `json:"weight"`
}
type consoleClipboard struct {
	Type        string `json:"type"`
	Text        string `json:"text"`
	ExpiresAtMS uint64 `json:"expires_at_ms"`
	Pinned      bool   `json:"pinned"`
}

func (c ConsoleContent) MarshalJSON() ([]byte, error) {
	switch c.Type {
	case "dictionary":
		return json.Marshal(consoleDictionary{c.Type, c.Scheme, c.Code, c.Text, c.Weight})
	case "clipboard":
		return json.Marshal(consoleClipboard{c.Type, c.Text, c.ExpiresAtMS, c.Pinned})
	default:
		return nil, ErrInvalid
	}
}
func consoleStrictJSON(body []byte, out any) error {
	check := json.NewDecoder(bytes.NewReader(body))
	check.UseNumber()
	if consoleUniqueJSON(check, 0) != nil {
		return ErrInvalid
	}
	if _, err := check.Token(); err != io.EOF {
		return ErrInvalid
	}
	d := json.NewDecoder(bytes.NewReader(body))
	d.DisallowUnknownFields()
	if d.Decode(out) != nil || d.Decode(new(any)) != io.EOF {
		return ErrInvalid
	}
	return nil
}
func consoleUniqueJSON(d *json.Decoder, depth int) error {
	if depth > 16 {
		return ErrInvalid
	}
	token, err := d.Token()
	if err != nil {
		return ErrInvalid
	}
	switch token {
	case json.Delim('{'):
		seen := map[string]bool{}
		for d.More() {
			key, err := d.Token()
			name, ok := key.(string)
			if err != nil || !ok || seen[name] {
				return ErrInvalid
			}
			seen[name] = true
			if consoleUniqueJSON(d, depth+1) != nil {
				return ErrInvalid
			}
		}
		end, err := d.Token()
		if err != nil || end != json.Delim('}') {
			return ErrInvalid
		}
	case json.Delim('['):
		for d.More() {
			if consoleUniqueJSON(d, depth+1) != nil {
				return ErrInvalid
			}
		}
		end, err := d.Token()
		if err != nil || end != json.Delim(']') {
			return ErrInvalid
		}
	case json.Delim('}'), json.Delim(']'):
		return ErrInvalid
	}
	return nil
}
func consoleRequiredJSON(body []byte, names ...string) bool {
	var fields map[string]json.RawMessage
	if json.Unmarshal(body, &fields) != nil || len(fields) != len(names) {
		return false
	}
	for _, name := range names {
		if value, ok := fields[name]; !ok || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return false
		}
	}
	return true
}
func (c *ConsoleContent) UnmarshalJSON(body []byte) error {
	var tag struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(body, &tag) != nil {
		return ErrInvalid
	}
	switch tag.Type {
	case "dictionary":
		if !consoleRequiredJSON(body, "type", "scheme", "code", "text", "weight") {
			return ErrInvalid
		}
		var v consoleDictionary
		if consoleStrictJSON(body, &v) != nil {
			return ErrInvalid
		}
		*c = ConsoleContent{Type: v.Type, Scheme: v.Scheme, Code: v.Code, Text: v.Text, Weight: v.Weight}
	case "clipboard":
		if !consoleRequiredJSON(body, "type", "text", "expires_at_ms", "pinned") {
			return ErrInvalid
		}
		var v consoleClipboard
		if consoleStrictJSON(body, &v) != nil {
			return ErrInvalid
		}
		*c = ConsoleContent{Type: v.Type, Text: v.Text, ExpiresAtMS: v.ExpiresAtMS, Pinned: v.Pinned}
	default:
		return ErrInvalid
	}
	return c.validate()
}
func (c ConsoleContent) kind() int {
	switch c.Type {
	case "dictionary":
		return 1
	case "clipboard":
		return 5
	}
	return 0
}
func (c ConsoleContent) validate() error {
	if c.Text == "" || len(c.Text) > 65536 || !utf8.ValidString(c.Text) || strings.ContainsRune(c.Text, 0) {
		return ErrInvalid
	}
	switch c.Type {
	case "dictionary":
		if (c.Scheme != "pinyin" && c.Scheme != "english") || len(c.Code) == 0 || len(c.Code) > 128 {
			return ErrInvalid
		}
		for _, v := range []byte(c.Code) {
			if !(v >= 'a' && v <= 'z' || v >= '0' && v <= '9' || v == '\'') {
				return ErrInvalid
			}
		}
	case "clipboard":
		if c.ExpiresAtMS == 0 {
			return ErrInvalid
		}
	default:
		return ErrInvalid
	}
	return nil
}

// Revision inside Record is the sender's local revision. ServerRevision is the
// separately tracked CAS revision and must never be substituted for that field.
type ConsoleRecord struct {
	ID       string         `json:"id"`
	Revision uint64         `json:"revision"`
	DeviceID string         `json:"device_id"`
	Deleted  bool           `json:"deleted"`
	Content  ConsoleContent `json:"content"`
}

func (r ConsoleRecord) validate() error {
	if !validID(r.ID) || !validID(r.DeviceID) || r.Revision == 0 || r.Revision > math.MaxInt64 {
		return ErrInvalid
	}
	return r.Content.validate()
}

type ConsoleRecordView struct {
	Record         ConsoleRecord `json:"record"`
	ServerRevision int64         `json:"server_revision"`
}
type ConsoleRecordPage struct {
	Records   []ConsoleRecordView `json:"records"`
	NextAfter string              `json:"next_after"`
	Head      int64               `json:"head"`
	KeyState  string              `json:"key_state"`
}
type ConsoleRecordInput struct {
	OperationID  string         `json:"operation_id"`
	RecordID     string         `json:"record_id"`
	BaseRevision int64          `json:"base_revision"`
	Deleted      bool           `json:"deleted"`
	Content      ConsoleContent `json:"content"`
}

func (in ConsoleRecordInput) validate() error {
	if !validID(in.OperationID) || !validID(in.RecordID) || in.BaseRevision < 0 {
		return ErrInvalid
	}
	return in.Content.validate()
}

func consoleReadMaterial(ctx context.Context, tx *sql.Tx, account string, vault *ConsoleKeyVault) ([]byte, error) {
	var protected []byte
	err := tx.QueryRowContext(ctx, `SELECT encrypted_material FROM zapime_console_keys WHERE account_id=$1`, account).Scan(&protected)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrConsoleKeyRequired
	}
	if err != nil {
		return nil, err
	}
	material, err := vault.open(account, protected)
	if err != nil {
		return nil, err
	}
	descriptor, err := readKeyDescriptor(ctx, tx, account)
	actual, valid := consoleMaterialDescriptor(account, material)
	if err != nil || valid != nil || descriptor == nil || descriptor.Descriptor != actual {
		clear(material)
		return nil, ErrConsoleKeyUnavailable
	}
	return material, nil
}

// ConsoleRecords never changes sync cursors or returns unsupported record kinds.
// A page scans at most limit+1 current rows; NextAfter tracks scanned UUIDs even
// when expired clipboard rows or tombstones are filtered out.
func (api *API) ConsoleRecords(ctx context.Context, actor ConsoleActor, target string, vault *ConsoleKeyVault, kind, after string, limit int, adminView bool) (ConsoleRecordPage, error) {
	if kind != "dictionary" && kind != "clipboard" || after != "" && !validID(after) || limit < 1 || limit > 100 {
		return ConsoleRecordPage{}, ErrInvalid
	}
	tx, head, err := api.consoleLock(ctx, actor, target, false)
	if err != nil {
		return ConsoleRecordPage{}, err
	}
	defer tx.Rollback()
	if adminView && !api.Console.Admins[actor.Issuer+"\x00"+actor.Subject] {
		return ConsoleRecordPage{}, ErrForbidden
	}
	if adminView || target != actor.AccountID {
		if err = consoleAuditTx(ctx, tx, actor, target, "records.view."+kind); err != nil {
			return ConsoleRecordPage{}, err
		}
	}
	page := ConsoleRecordPage{Records: []ConsoleRecordView{}, Head: head, KeyState: "available"}
	material, err := consoleReadMaterial(ctx, tx, target, vault)
	if errors.Is(err, ErrConsoleKeyRequired) {
		descriptor, readErr := readKeyDescriptor(ctx, tx, target)
		if readErr != nil {
			return ConsoleRecordPage{}, readErr
		}
		page.KeyState = "required"
		if descriptor == nil {
			history, historyErr := accountHasKeyHistory(ctx, tx, target, head)
			if historyErr != nil {
				return ConsoleRecordPage{}, historyErr
			}
			page.KeyState = "empty"
			if history {
				page.KeyState = "legacy"
			}
		}
		return page, tx.Commit()
	}
	if err != nil {
		return ConsoleRecordPage{}, err
	}
	defer clear(material)
	kindNumber := 1
	if kind == "clipboard" {
		kindNumber = 5
	}
	rows, err := tx.QueryContext(ctx, `SELECT record_id::text,revision,change FROM zapime_records WHERE account_id=$1 AND kind=$2 AND ($3='' OR record_id>NULLIF($3,'')::uuid) ORDER BY record_id LIMIT $4`, target, kindNumber, after, limit+1)
	if err != nil {
		return ConsoleRecordPage{}, err
	}
	type row struct {
		id       string
		revision int64
		body     []byte
	}
	var bodies []row
	for rows.Next() {
		var v row
		if err = rows.Scan(&v.id, &v.revision, &v.body); err != nil {
			rows.Close()
			return ConsoleRecordPage{}, err
		}
		bodies = append(bodies, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return ConsoleRecordPage{}, err
	}
	if len(bodies) > limit {
		bodies = bodies[:limit]
		page.NextAfter = bodies[len(bodies)-1].id
	}
	now := uint64(time.Now().UnixMilli())
	for _, v := range bodies {
		change, err := api.Store.decodeChange(ctx, target, v.body)
		if err != nil {
			return ConsoleRecordPage{}, err
		}
		if change.Revision != v.revision || change.Operation.RecordID != v.id {
			return ConsoleRecordPage{}, ErrInvalid
		}
		record, err := consoleOpenRecord(target, material, change)
		if err != nil {
			return ConsoleRecordPage{}, err
		}
		if record.Deleted || record.Content.Type == "clipboard" && record.Content.ExpiresAtMS <= now {
			continue
		}
		page.Records = append(page.Records, ConsoleRecordView{record, v.revision})
	}
	return page, tx.Commit()
}

// ConsoleWriteRecord freezes one ciphertext operation and applies it using the
// normal sync writer. A repeated operation reuses its original stored bytes;
// encryption is never rerun to manufacture a replacement retry.
func (api *API) ConsoleWriteRecord(ctx context.Context, actor ConsoleActor, vault *ConsoleKeyVault, in ConsoleRecordInput) (ConsoleRecordView, error) {
	if err := in.validate(); err != nil {
		return ConsoleRecordView{}, err
	}
	tx, head, err := api.consoleLock(ctx, actor, actor.AccountID, true)
	if err != nil {
		return ConsoleRecordView{}, err
	}
	defer tx.Rollback()
	material, err := consoleReadMaterial(ctx, tx, actor.AccountID, vault)
	if err != nil {
		return ConsoleRecordView{}, err
	}
	defer clear(material)
	var frozen []byte
	var publisher string
	err = tx.QueryRowContext(ctx, `SELECT change,device_id::text FROM zapime_operations WHERE account_id=$1 AND operation_id=$2`, actor.AccountID, in.OperationID).Scan(&frozen, &publisher)
	if err == nil {
		change, err := api.Store.decodeChange(ctx, actor.AccountID, frozen)
		if err != nil {
			return ConsoleRecordView{}, err
		}
		record, err := consoleOpenRecord(actor.AccountID, material, change)
		if err != nil {
			return ConsoleRecordView{}, err
		}
		if publisher != actor.Device.DeviceID || change.Operation.RecordID != in.RecordID || change.Operation.BaseRevision != in.BaseRevision || record.Deleted != in.Deleted || record.Content != in.Content {
			return ConsoleRecordView{}, ErrOperationReused
		}
		return ConsoleRecordView{record, change.Revision}, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return ConsoleRecordView{}, err
	}
	var body []byte
	var currentRevision int64
	err = tx.QueryRowContext(ctx, `SELECT revision,change FROM zapime_records WHERE account_id=$1 AND record_id=$2`, actor.AccountID, in.RecordID).Scan(&currentRevision, &body)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return ConsoleRecordView{}, err
	}
	if currentRevision != in.BaseRevision {
		return ConsoleRecordView{}, &Conflict{}
	}
	localRevision := uint64(1)
	checkUnique := !in.Deleted && in.Content.Type == "dictionary"
	if len(body) > 0 {
		change, err := api.Store.decodeChange(ctx, actor.AccountID, body)
		if err != nil {
			return ConsoleRecordView{}, err
		}
		if change.Revision != currentRevision || change.Operation.RecordID != in.RecordID {
			return ConsoleRecordView{}, ErrInvalid
		}
		previous, err := consoleOpenRecord(actor.AccountID, material, change)
		if err != nil {
			return ConsoleRecordView{}, err
		}
		if previous.Content.kind() != in.Content.kind() {
			return ConsoleRecordView{}, ErrInvalid
		}
		if previous.Content.Type == "dictionary" && previous.Content.Scheme == in.Content.Scheme && previous.Content.Code == in.Content.Code && previous.Content.Text == in.Content.Text {
			checkUnique = false
		}
		// Expiry and deletion are terminal for this identity. Recreating an item
		// requires a new UUID, so pinning cannot extend an expired clipboard item.
		if previous.Deleted {
			return ConsoleRecordView{}, ErrConsoleRecordDeleted
		}
		if !in.Deleted && previous.Content.Type == "clipboard" && previous.Content.ExpiresAtMS <= uint64(time.Now().UnixMilli()) {
			return ConsoleRecordView{}, ErrConsoleRecordExpired
		}
		if previous.Content.Type == "clipboard" && previous.Content.ExpiresAtMS != in.Content.ExpiresAtMS {
			return ConsoleRecordView{}, ErrInvalid
		}
		if previous.Revision == math.MaxInt64 {
			return ConsoleRecordView{}, ErrLimit
		}
		localRevision = previous.Revision + 1
	} else if in.Deleted {
		return ConsoleRecordView{}, ErrInvalid
	}
	if !in.Deleted && in.Content.Type == "clipboard" && in.Content.ExpiresAtMS <= uint64(time.Now().UnixMilli()) {
		return ConsoleRecordView{}, ErrInvalid
	}
	if checkUnique {
		if err = api.Store.consoleDictionaryUnique(ctx, tx, actor.AccountID, material, in.RecordID, in.Content); err != nil {
			return ConsoleRecordView{}, err
		}
	}
	descriptor, err := consoleMaterialDescriptor(actor.AccountID, material)
	if err != nil {
		return ConsoleRecordView{}, err
	}
	record := ConsoleRecord{in.RecordID, localRevision, actor.Device.DeviceID, in.Deleted, in.Content}
	op := Operation{OperationID: in.OperationID, RecordID: in.RecordID, KeyID: descriptor.KeyID, Kind: in.Content.kind(), BaseRevision: in.BaseRevision, Deleted: in.Deleted}
	op.Envelope, err = consoleSealRecord(actor.AccountID, material, op, record)
	if err != nil {
		return ConsoleRecordView{}, err
	}
	if err = op.Validate(); err != nil {
		return ConsoleRecordView{}, err
	}
	change, err := api.Store.pushLocked(ctx, tx, actor.Device, head, op)
	if err != nil {
		return ConsoleRecordView{}, err
	}
	return ConsoleRecordView{record, change.Revision}, tx.Commit()
}

var ErrConsoleDictionaryDuplicate = errors.New("duplicate_dictionary_entry")

// Existing device writes are opaque to the server and have no plaintext index.
// Scan bounded pages only under the account lock and request deadline, and only
// when creating/changing a unique key. Weight-only edits do not scan the table.
func (s *Store) consoleDictionaryUnique(ctx context.Context, tx *sql.Tx, account string, material []byte, recordID string, content ConsoleContent) error {
	after := ""
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, `SELECT record_id::text,change FROM zapime_records WHERE account_id=$1 AND kind=1 AND record_id<>$2::uuid AND ($3='' OR record_id>NULLIF($3,'')::uuid) ORDER BY record_id LIMIT 100`, account, recordID, after)
		if err != nil {
			return err
		}
		type entry struct {
			id   string
			body []byte
		}
		var entries []entry
		for rows.Next() {
			var e entry
			if err = rows.Scan(&e.id, &e.body); err != nil {
				rows.Close()
				return err
			}
			entries = append(entries, e)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		for _, e := range entries {
			change, err := s.decodeChange(ctx, account, e.body)
			if err != nil {
				return err
			}
			if change.Operation.RecordID != e.id {
				return ErrInvalid
			}
			record, err := consoleOpenRecord(account, material, change)
			if err != nil {
				return err
			}
			if !record.Deleted && record.Content.Scheme == content.Scheme && record.Content.Code == content.Code && record.Content.Text == content.Text {
				return ErrConsoleDictionaryDuplicate
			}
			after = e.id
		}
		if len(entries) < 100 {
			return nil
		}
	}
}
