// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

const automaticPath = "/v1/device-enrollment/automatic"

type automaticInstallation struct {
	key       *ecdsa.PrivateKey
	publicKey []byte
	device    string
	token     string
	hash      []byte
}

func newAutomaticInstallation(t *testing.T) automaticInstallation {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("generate installation key")
	}
	publicKey, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal("marshal installation key")
	}
	token, hash, err := newToken()
	if err != nil {
		t.Fatal("generate device credential")
	}
	return automaticInstallation{key, publicKey, id(t), token, hash}
}

func (installation automaticInstallation) request(t *testing.T, account, subject, access, name string) automaticEnrollmentRequest {
	t.Helper()
	fingerprint := enrollmentHash("zapime-auto-device-v1", enrollmentIssuer, enrollmentResource, account, installation.device,
		accountLoginFingerprint(enrollmentIssuer, subject, enrollmentResource, account), name, string(installation.publicKey), string(installation.hash))
	accessHash := sha256.Sum256([]byte(access))
	digest := enrollmentHash("zapime-auto-device-sign-v1", string(fingerprint[:]), string(accessHash[:]))
	signature, err := ecdsa.SignASN1(rand.Reader, installation.key, digest[:])
	if err != nil {
		t.Fatal("sign installation request")
	}
	encode := base64.RawURLEncoding.EncodeToString
	return automaticEnrollmentRequest{installation.device, account, name, encode(installation.publicKey), encode(installation.hash), encode(signature)}
}

func initializeKey(api *API, token string, key KeyDescriptor) *httptest.ResponseRecorder {
	wire, _ := json.Marshal(key)
	req := httptest.NewRequest(http.MethodPut, "/v1/key-descriptor/initialize", bytes.NewReader(wire))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	reply := httptest.NewRecorder()
	api.ServeHTTP(reply, req)
	return reply
}

func TestPostgresAutomaticEnrollmentAndFirstKey(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	api, sign := enrollmentTestAPI(t, s)
	const subject = "automatic-synthetic-subject"
	account := id(t)
	if _, err := s.DB.Exec(`INSERT INTO zapime_accounts(id) VALUES($1)`, account); err != nil {
		t.Fatal("create empty account without a pre-existing device")
	}
	if err := s.BindOIDCSubject(ctx, account, enrollmentIssuer, subject); err != nil {
		t.Fatal("bind synthetic subject")
	}
	check := func(path, access string, input any, want int) *httptest.ResponseRecorder {
		t.Helper()
		reply := enrollmentCall(api, path, access, input)
		if reply.Code != want {
			t.Fatalf("%s: wanted %d, got %d", path, want, reply.Code)
		}
		return reply
	}
	first, second := newAutomaticInstallation(t), newAutomaticInstallation(t)
	access := sign(subject)
	req := first.request(t, account, subject, access, "Synthetic first")
	check(automaticPath, access, req, 404)
	api.AllowAutomaticEnrollment = true
	check("/v1/account-login", access, nil, 200)
	check("/v1/session", first.token, nil, 401)
	check(automaticPath, access, first.request(t, id(t), subject, access, "Wrong account"), 401)
	invalid := req
	invalid.Signature = "AQ"
	check(automaticPath, access, invalid, 401)
	unbound := sign("unbound-synthetic-subject")
	check(automaticPath, unbound, second.request(t, account, "unbound-synthetic-subject", unbound, "Unbound"), 401)
	check(automaticPath, access, req, 200)
	// Discard the first receipt. Both retry and session lookup must resolve the same durable member.
	firstReply := check(automaticPath, access, req, 200)
	session := check("/v1/session", first.token, nil, 200)
	var firstSession Session
	if json.Unmarshal(session.Body.Bytes(), &firstSession) != nil || firstSession.AccountID != account || firstSession.DeviceID != first.device || firstSession.Role != "member" || !bytes.Equal(session.Body.Bytes(), firstReply.Body.Bytes()) {
		t.Fatal("automatic enrollment did not preserve device identity across retries")
	}
	check(automaticPath, access, second.request(t, account, subject, access, "Different install"), 401)
	legacyReq, _, _, _ := signedEnrollmentTest(t, account, access)
	check(enrollmentEndpoint, access, legacyReq, 401)
	legacyAccess := sign(subject)
	legacyReq, _, _, _ = signedEnrollmentTest(t, account, legacyAccess)
	check(enrollmentEndpoint, legacyAccess, legacyReq, 200)
	check(automaticPath, legacyAccess, second.request(t, account, subject, legacyAccess, "Legacy ticket"), 401)
	freshAccess := sign(subject)
	check(automaticPath, freshAccess, second.request(t, account, subject, freshAccess, "Synthetic second"), 200)

	firstKey, secondKey := descriptor(t), descriptor(t)
	firstActor, err := s.Authenticate(ctx, first.token)
	if err != nil {
		t.Fatal("authenticate automatic first member")
	}
	if _, err = s.RegisterKeyDescriptor(ctx, firstActor, firstKey); err != ErrForbidden {
		t.Fatal("generic key registration bypassed owner requirement")
	}
	start := make(chan struct{})
	out := make(chan int, 2)
	for _, candidate := range []struct {
		token string
		key   KeyDescriptor
	}{{first.token, firstKey}, {second.token, secondKey}} {
		go func() {
			<-start
			out <- initializeKey(api, candidate.token, candidate.key).Code
		}()
	}
	close(start)
	a, b := <-out, <-out
	if !((a == 200 && b == 409) || (a == 409 && b == 200)) {
		t.Fatalf("expected one initialization and one conflict, got %d and %d", a, b)
	}
	state, err := s.KeyDescriptor(ctx, firstActor)
	if err != nil || state.Descriptor == nil || state.RegisteredBy == nil {
		t.Fatal("registered key not visible")
	}
	winnerToken := first.token
	if *state.RegisteredBy == second.device {
		winnerToken = second.token
	}
	if got := initializeKey(api, winnerToken, *state.Descriptor).Code; got != 200 {
		t.Fatalf("same key initialization receipt was not recoverable: %d", got)
	}
	page, err := s.Pull(ctx, firstActor, 0, 1)
	if err != nil || page.Head != 0 || len(page.Changes) != 0 {
		t.Fatal("initialization changed the data history")
	}

	if _, err = s.DB.Exec(`UPDATE zapime_devices SET expires_at=$2 WHERE id=$1`, first.device, time.Now().Add(-time.Minute)); err != nil {
		t.Fatal("expire synthetic device")
	}
	check("/v1/session", first.token, nil, 401)
	newAccess := sign(subject)
	check(automaticPath, newAccess, first.request(t, account, subject, newAccess, "Synthetic first"), 200)
	check("/v1/session", first.token, nil, 200)
	if err = s.Revoke(ctx, firstActor, first.device); err != nil {
		t.Fatal("revoke synthetic device")
	}
	check("/v1/session", first.token, nil, 401)
	nextAccess := sign(subject)
	check(automaticPath, nextAccess, first.request(t, account, subject, nextAccess, "Renamed"), 401)
	first.device = id(t)
	check(automaticPath, nextAccess, first.request(t, account, subject, nextAccess, "New ID for same installation"), 401)
	if got := initializeKey(api, first.token, firstKey).Code; got != 401 {
		t.Fatalf("revoked device can still initialize: %d", got)
	}
}

func TestPostgresAutomaticInitializationKeepsHistoryAndOtherRoles(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	api, sign := enrollmentTestAPI(t, s)
	api.AllowAutomaticEnrollment = true
	owner, actor := provision(t, s)
	manual, err := s.AddDevice(ctx, actor, "Synthetic manual device")
	if err != nil {
		t.Fatal("create manual device")
	}
	proposed := descriptor(t)
	if got := initializeKey(api, manual.Token, proposed).Code; got != 403 {
		t.Fatalf("manual member gained first key permission: %d", got)
	}
	const subject = "automatic-history-subject"
	if err = s.BindOIDCSubject(ctx, owner.AccountID, enrollmentIssuer, subject); err != nil {
		t.Fatal("bind synthetic subject")
	}
	device := newAutomaticInstallation(t)
	access := sign(subject)
	req := device.request(t, owner.AccountID, subject, access, "Automatic synthetic device")
	if got := enrollmentCall(api, automaticPath, access, req).Code; got != 200 {
		t.Fatalf("automatic enrollment failed: %d", got)
	}
	if _, err = s.Push(ctx, actor, operation(t)); err != nil {
		t.Fatal("create actual legacy history after device grant")
	}
	if got := initializeKey(api, device.token, proposed).Code; got != 409 {
		t.Fatalf("initialization overwrote existing history: %d", got)
	}
	state, err := s.KeyDescriptor(ctx, actor)
	if err != nil || state.State != "legacy" || state.Descriptor != nil {
		t.Fatal("existing history lost identity state")
	}
}
