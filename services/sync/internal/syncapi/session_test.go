// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSessionIdentityAndRevocation(t *testing.T) {
	s := database(t)
	ctx := context.Background()
	owner, actor := provision(t, s)
	other, _ := provision(t, s)
	member, err := s.AddDevice(ctx, actor, "合成会话设备")
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(NewAPI(s))
	defer server.Close()
	read := func(token, query string) (int, map[string]json.RawMessage) {
		t.Helper()
		request, err := http.NewRequest(http.MethodGet, server.URL+"/v1/session"+query, nil)
		if err != nil {
			t.Fatal(err)
		}
		if token != "" {
			request.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("identity response must not be cached")
		}
		wire, err := io.ReadAll(io.LimitReader(response.Body, 4097))
		if err != nil || len(wire) > 4096 || strings.Contains(string(wire), owner.Token) || strings.Contains(string(wire), member.Token) {
			t.Fatal("unbounded or credential-bearing response")
		}
		var fields map[string]json.RawMessage
		if err = json.Unmarshal(wire, &fields); err != nil {
			t.Fatal(err)
		}
		return response.StatusCode, fields
	}
	for _, token := range []string{"", "invalid"} {
		if code, _ := read(token, ""); code != 401 {
			t.Fatal("unauthenticated session accepted")
		}
	}
	for _, credential := range []Credential{owner, other, member} {
		code, fields := read(credential.Token, "?account_id="+other.AccountID)
		if code != 200 || len(fields) != 5 {
			t.Fatal("session response shape")
		}
		wire, _ := json.Marshal(fields)
		var session Session
		if err = json.Unmarshal(wire, &session); err != nil {
			t.Fatal(err)
		}
		identity, err := s.Authenticate(ctx, credential.Token)
		if err != nil || session.Protocol != 1 || session.AccountID != credential.AccountID || session.DeviceID != credential.DeviceID || session.Role != identity.Role || !session.ExpiresAt.Equal(identity.ExpiresAt) {
			t.Fatal("session identity/account isolation")
		}
	}
	if err = s.Revoke(ctx, actor, member.DeviceID); err != nil {
		t.Fatal(err)
	}
	if code, _ := read(member.Token, ""); code != 401 {
		t.Fatal("revoked device can inspect session")
	}
	expired, err := s.AddDevice(ctx, actor, "合成到期设备")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.DB.ExecContext(ctx, `UPDATE zapime_devices SET expires_at=now()-interval '1 second' WHERE id=$1`, expired.DeviceID); err != nil {
		t.Fatal(err)
	}
	if code, _ := read(expired.Token, ""); code != 401 {
		t.Fatal("expired device can inspect session")
	}
	page, err := s.Pull(ctx, actor, 0, 1)
	if err != nil || page.Head != 0 || page.NextCursor != 0 || len(page.Changes) != 0 {
		t.Fatal("identity inspection mutated sync history")
	}
}
