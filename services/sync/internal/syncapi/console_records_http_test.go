// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPostgresConsoleRecordsHTTP(t *testing.T) {
	api, sign := consoleTestAPI(t)
	api.ConsoleVault = consoleTestVault(t)
	credential, cookie, csrf, actor := consoleTestLogin(t, api, sign, "records-http-owner", true)
	call := func(method, path string, input any, token string) *httptest.ResponseRecorder {
		t.Helper()
		var body []byte
		var err error
		if input != nil {
			body, err = json.Marshal(input)
			if err != nil {
				t.Fatal(err)
			}
		}
		r := httptest.NewRequest(method, path, bytes.NewReader(body))
		r.AddCookie(cookie)
		r.Header.Set("X-Console-Account-ID", actor.AccountID)
		if input != nil {
			r.Header.Set("Content-Type", "application/json")
		}
		r.Header.Set("Origin", api.Console.Origin)
		r.Header.Set("X-CSRF-Token", token)
		w := httptest.NewRecorder()
		api.ServeHTTP(w, r)
		return w
	}
	if w := call(http.MethodPost, "/console/api/material/enable", map[string]bool{"consent": false}, csrf); w.Code != 400 {
		t.Fatal("missing consent accepted", w.Code)
	}
	if w := call(http.MethodPost, "/console/api/material/enable", map[string]bool{"consent": true}, ""); w.Code != 403 {
		t.Fatal("missing CSRF accepted", w.Code)
	}
	initial := call(http.MethodGet, "/console/api/records?kind=dictionary", nil, "")
	var initialPage ConsoleRecordPage
	if initial.Code != 200 || json.Unmarshal(initial.Body.Bytes(), &initialPage) != nil || initialPage.KeyState != "empty" {
		t.Fatal("new account not marked empty", initial.Code)
	}
	if w := call(http.MethodPost, "/console/api/material/enable", map[string]bool{"consent": true}, csrf); w.Code != 200 {
		t.Fatal("enable", w.Code)
	}
	in := ConsoleRecordInput{OperationID: id(t), RecordID: id(t), Content: ConsoleContent{Type: "dictionary", Scheme: "pinyin", Code: "ceshi", Text: "合成HTTP词条", Weight: 1}}
	w := call(http.MethodPost, "/console/api/records", in, csrf)
	var first ConsoleRecordView
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &first) != nil || first.ServerRevision != 1 {
		t.Fatal("create HTTP", w.Code)
	}
	if w = call(http.MethodPost, "/console/api/records", in, csrf); w.Code != 200 {
		t.Fatal("retry HTTP", w.Code)
	}
	w = call(http.MethodGet, "/console/api/records?kind=dictionary", nil, "")
	var page ConsoleRecordPage
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.Records) != 1 {
		t.Fatal("own list", w.Code)
	}
	var audits int
	if err := api.Store.DB.QueryRow(`SELECT count(*) FROM zapime_console_audit WHERE actor_account_id=$1 AND action='records.view.dictionary'`, actor.AccountID).Scan(&audits); err != nil || audits != 0 {
		t.Fatal("normal list unexpectedly audited", err)
	}
	w = call(http.MethodGet, "/console/api/admin/accounts/"+actor.AccountID+"/records?kind=dictionary", nil, "")
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.Records) != 1 {
		t.Fatal("own admin list", w.Code)
	}
	if err := api.Store.DB.QueryRow(`SELECT count(*) FROM zapime_console_audit WHERE actor_account_id=$1 AND target_account_id=$1 AND action='records.view.dictionary'`, actor.AccountID).Scan(&audits); err != nil || audits != 1 {
		t.Fatal("own admin view was not audited", audits, err)
	}
	in.OperationID = id(t)
	in.BaseRevision = first.ServerRevision
	in.Content.Weight = 2
	w = call(http.MethodPut, "/console/api/records/"+in.RecordID, in, csrf)
	var edited ConsoleRecordView
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &edited) != nil || edited.Record.Content.Weight != 2 {
		t.Fatal("edit HTTP", w.Code)
	}
	stale := in
	stale.OperationID = id(t)
	if w = call(http.MethodPut, "/console/api/records/"+in.RecordID, stale, csrf); w.Code != 409 {
		t.Fatal("stale HTTP edit accepted", w.Code)
	}
	in.OperationID = id(t)
	in.BaseRevision = edited.ServerRevision
	in.Deleted = true
	w = call(http.MethodDelete, "/console/api/records/"+in.RecordID, in, csrf)
	var tombstone ConsoleRecordView
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &tombstone) != nil || !tombstone.Record.Deleted {
		t.Fatal("delete HTTP", w.Code)
	}
	if w = call(http.MethodDelete, "/console/api/records/"+in.RecordID, in, csrf); w.Code != 200 {
		t.Fatal("delete retry HTTP", w.Code)
	}
	in.OperationID = id(t)
	in.BaseRevision = tombstone.ServerRevision
	in.Deleted = false
	if w = call(http.MethodPut, "/console/api/records/"+in.RecordID, in, csrf); w.Code != 410 {
		t.Fatal("HTTP tombstone resurrected", w.Code)
	}
	// A valid native encrypted record may already be expired before the console
	// opens it. Both personal and administrator HTTP lists must filter it.
	r := httptest.NewRequest(http.MethodPost, "/v1/console-material/enable", nil)
	r.Header.Set("Authorization", "Bearer "+credential.Token)
	r.Header.Set("Content-Type", "application/octet-stream")
	w = httptest.NewRecorder()
	api.ServeHTTP(w, r)
	if w.Code != 200 || w.Body.Len() != 72 {
		t.Fatal("device material HTTP", w.Code)
	}
	material := bytes.Clone(w.Body.Bytes())
	defer clear(material)
	descriptor, err := consoleMaterialDescriptor(actor.AccountID, material)
	if err != nil {
		t.Fatal(err)
	}
	op := Operation{OperationID: id(t), RecordID: id(t), KeyID: descriptor.KeyID, Kind: 5}
	expired := ConsoleRecord{ID: op.RecordID, Revision: 1, DeviceID: actor.Device.DeviceID, Content: ConsoleContent{Type: "clipboard", Text: "合成已过期剪贴板", ExpiresAtMS: uint64(time.Now().Add(-time.Second).UnixMilli())}}
	op.Envelope, err = consoleSealRecord(actor.AccountID, material, op, expired)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = api.Store.Push(context.Background(), actor.Device, op); err != nil {
		t.Fatal("native expired fixture", err)
	}
	for _, path := range []string{"/console/api/records?kind=clipboard", "/console/api/admin/accounts/" + actor.AccountID + "/records?kind=clipboard"} {
		w = call(http.MethodGet, path, nil, "")
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.Records) != 0 {
			t.Fatal("expired clipboard displayed", w.Code)
		}
	}
	// An existing escrow that cannot be authenticated is an unavailable service,
	// never a prompt to replace/re-import the account's root material.
	var protected []byte
	if err = api.Store.DB.QueryRow(`SELECT encrypted_material FROM zapime_console_keys WHERE account_id=$1`, actor.AccountID).Scan(&protected); err != nil {
		t.Fatal(err)
	}
	corrupt := bytes.Clone(protected)
	corrupt[len(corrupt)-1] ^= 1
	if _, err = api.Store.DB.Exec(`UPDATE zapime_console_keys SET encrypted_material=$2 WHERE account_id=$1`, actor.AccountID, corrupt); err != nil {
		t.Fatal(err)
	}
	if w = call(http.MethodGet, "/console/api/records?kind=dictionary", nil, ""); w.Code != 503 {
		t.Fatal("corrupt escrow presented as migration", w.Code)
	}
	if _, err = api.Store.DB.Exec(`UPDATE zapime_console_keys SET encrypted_material=$2 WHERE account_id=$1`, actor.AccountID, protected); err != nil {
		t.Fatal(err)
	}
	workingVault := api.ConsoleVault
	api.ConsoleVault, err = NewConsoleKeyVault(bytes.Repeat([]byte{0x44}, 32))
	if err != nil {
		t.Fatal(err)
	}
	if w = call(http.MethodGet, "/console/api/records?kind=dictionary", nil, ""); w.Code != 503 {
		t.Fatal("wrong master presented as migration", w.Code)
	}
	api.ConsoleVault = workingVault
	if _, err = api.Store.DB.Exec(`DELETE FROM zapime_console_keys WHERE account_id=$1`, actor.AccountID); err != nil {
		t.Fatal(err)
	}
	w = call(http.MethodGet, "/console/api/records?kind=dictionary", nil, "")
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &page) != nil || page.KeyState != "required" {
		t.Fatal("registered account missing escrow not marked required", w.Code)
	}
	if w = call(http.MethodPost, "/console/api/material/enable", map[string]bool{"consent": true}, csrf); w.Code != 409 {
		t.Fatal("registered account silently replaced", w.Code)
	}
}
