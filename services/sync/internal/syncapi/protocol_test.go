// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"encoding/base64"
	"testing"
)

func id(t *testing.T) string {
	t.Helper()
	s, err := UUID()
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func operation(t *testing.T) Operation {
	return Operation{OperationID: id(t), RecordID: id(t), KeyID: id(t), Kind: 4,
		Envelope: Envelope{Version: 1, Nonce: base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{1}, 24)), Ciphertext: base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{2}, 48))}}
}

func TestEnvelopeBounds(t *testing.T) {
	valid := operation(t)
	if err := valid.Validate(); err != nil {
		t.Fatal(err)
	}
	for name, change := range map[string]func(*Operation){
		"nil record":        func(o *Operation) { o.RecordID = "00000000-0000-0000-0000-000000000000" },
		"negative revision": func(o *Operation) { o.BaseRevision = -1 },
		"unknown kind":      func(o *Operation) { o.Kind = 6 },
		"future cipher":     func(o *Operation) { o.Envelope.Version = 2 },
		"short nonce":       func(o *Operation) { o.Envelope.Nonce = "abc" },
		"short tag":         func(o *Operation) { o.Envelope.Ciphertext = "Ag" },
		"too large":         func(o *Operation) { o.Envelope.Ciphertext = base64.RawURLEncoding.EncodeToString(make([]byte, 65553)) },
		"padding":           func(o *Operation) { o.Envelope.Nonce += "=" },
	} {
		t.Run(name, func(t *testing.T) {
			op := valid
			change(&op)
			if op.Validate() == nil {
				t.Fatal("accepted invalid envelope")
			}
		})
	}
}
func TestTokenFormatAndHash(t *testing.T) {
	first, hash, err := newToken()
	if err != nil {
		t.Fatal(err)
	}
	second, _, err := newToken()
	if err != nil {
		t.Fatal(err)
	}
	if first == second || len(hash) != 32 || bytes.Contains(hash, []byte(first)) {
		t.Fatal("invalid token generation")
	}
	if _, err = tokenHash(first); err != nil {
		t.Fatal(err)
	}
	for _, token := range []string{"", first + "=", "Bearer " + first, first[1:]} {
		if _, err = tokenHash(token); err == nil {
			t.Fatal("accepted malformed token")
		}
	}
}
func TestDeviceNameBounds(t *testing.T) {
	for _, name := range []string{"", "  ", "name\nsecret", string(bytes.Repeat([]byte{'a'}, 129)), string([]byte{0xff})} {
		if validName(name) {
			t.Fatal("invalid name accepted")
		}
	}
	if !validName("测试设备") {
		t.Fatal("unicode name rejected")
	}
}
func FuzzOperationBoundary(f *testing.F) {
	f.Add("abc", "abc", int64(0))
	f.Fuzz(func(t *testing.T, nonce, cipher string, revision int64) {
		op := Operation{OperationID: "d72ef286-c9d0-4aae-b13a-18e84f59b765", RecordID: "042dcdbd-b1dc-4a0d-931c-567221a172e8", KeyID: "d72ef286-c9d0-4aae-b13a-18e84f59b765", Kind: 1, BaseRevision: revision, Envelope: Envelope{1, nonce, cipher}}
		_ = op.Validate()
	})
}
