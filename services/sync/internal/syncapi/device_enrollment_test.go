// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
	"github.com/go-jose/go-jose/v4"
	"github.com/go-jose/go-jose/v4/jwt"
)

const (
	enrollmentIssuer   = "https://login.example.test"
	enrollmentResource = "https://sync.example.test/device-enrollment"
	enrollmentClient   = "synthetic-device"
	enrollmentScope    = "zapime.device_enrollment"
	enrollmentEndpoint = "/v1/device-enrollment"
)

func enrollmentTestAPI(t *testing.T, s *Store) (*API, func(string) string) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal("generate synthetic signing key")
	}
	keys, err := json.Marshal(jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{Key: &key.PublicKey, KeyID: "fixture"}}})
	if err != nil {
		t.Fatal("encode synthetic key set")
	}
	policy := oidcauth.AccessPolicy{Issuer: enrollmentIssuer, Audience: enrollmentResource, ClientID: enrollmentClient,
		Scope: enrollmentScope, MaxLifetime: time.Minute}
	verifier, err := oidcauth.NewVerifier(policy, bytes.NewReader(keys))
	if err != nil {
		t.Fatal("configure synthetic issuer")
	}
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.RS256, Key: key},
		(&jose.SignerOptions{}).WithType("at+jwt").WithHeader("kid", "fixture"))
	if err != nil {
		t.Fatal("create synthetic signer")
	}
	return NewAPIWithOIDC(s, verifier), func(subject string) string {
		t.Helper()
		now := time.Now()
		wire, err := jwt.Signed(signer).Claims(jwt.Claims{Issuer: enrollmentIssuer, Subject: subject,
			Audience: jwt.Audience{enrollmentResource}, ID: id(t), IssuedAt: jwt.NewNumericDate(now), Expiry: jwt.NewNumericDate(now.Add(time.Minute))}).
			Claims(map[string]any{"client_id": enrollmentClient, "scope": enrollmentScope}).Serialize()
		if err != nil {
			t.Fatal("sign synthetic identity")
		}
		return wire
	}
}

func signedEnrollmentTest(t *testing.T, account, access string) (enrollmentRequest, enrollmentApproval, string, func(string) enrollmentClaim) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("generate synthetic device key")
	}
	publicDER, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal("encode synthetic device key")
	}
	token, tokenHash, err := newToken()
	if err != nil {
		t.Fatal("generate synthetic device credential")
	}
	req := enrollmentRequest{ID: id(t), AccountID: account, Name: "Synthetic enrollment", Deadline: time.Now().Add(5 * time.Minute).Unix(),
		PublicKey: base64.RawURLEncoding.EncodeToString(publicDER), TokenHash: base64.RawURLEncoding.EncodeToString(tokenHash)}
	fingerprint := enrollmentHash("zapime-enrollment-v1", enrollmentIssuer, enrollmentResource, req.AccountID, req.ID,
		req.Name, strconv.FormatInt(req.Deadline, 10), string(publicDER), string(tokenHash))
	sign := func(purpose, access string) string {
		t.Helper()
		accessHash := sha256.Sum256([]byte(access))
		message := enrollmentHash(purpose, string(fingerprint[:]), string(accessHash[:]))
		wire, err := ecdsa.SignASN1(rand.Reader, key, message[:])
		if err != nil {
			t.Fatal("sign synthetic enrollment")
		}
		return base64.RawURLEncoding.EncodeToString(wire)
	}
	req.Signature = sign("zapime-enrollment-begin-v1", access)
	return req, enrollmentApproval{req.ID, hex.EncodeToString(fingerprint[:])}, token, func(access string) enrollmentClaim {
		return enrollmentClaim{req.ID, account, sign("zapime-enrollment-claim-v1", access)}
	}
}

func enrollmentCall(api *API, path, bearer string, payload any) *httptest.ResponseRecorder {
	var request *http.Request
	if payload == nil {
		request = httptest.NewRequest(http.MethodGet, path, nil)
	} else {
		data, err := json.Marshal(payload)
		if err != nil {
			panic(err)
		}
		request = httptest.NewRequest(http.MethodPost, path, bytes.NewReader(data))
		request.Header.Set("Content-Type", "application/json")
	}
	request.Header.Set("Authorization", "Bearer "+bearer)
	out := httptest.NewRecorder()
	api.ServeHTTP(out, request)
	return out
}

func TestPostgresDeviceEnrollment(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	api, sign := enrollmentTestAPI(t, s)
	owner, actor := provision(t, s)
	other, _ := provision(t, s)
	const subject = "enrollment-subject"
	const otherSubject = "different-enrollment-subject"
	for _, id := range []string{subject, otherSubject} {
		if err := s.BindOIDCSubject(ctx, owner.AccountID, enrollmentIssuer, id); err != nil {
			t.Fatal("bind synthetic account")
		}
	}
	check := func(method, bearer string, body any, expected int) *httptest.ResponseRecorder {
		t.Helper()
		out := enrollmentCall(api, method, bearer, body)
		if out.Code != expected {
			t.Fatalf("%s: expected %d, got %d", method, expected, out.Code)
		}
		return out
	}
	oldToken, freshToken, otherToken := sign(subject), sign(subject), sign(otherSubject)
	req, approval, deviceToken, claim := signedEnrollmentTest(t, owner.AccountID, oldToken)
	pending := check(enrollmentEndpoint, oldToken, req, 200)
	var receipt enrollmentReceipt
	if json.Unmarshal(pending.Body.Bytes(), &receipt) != nil ||
		receipt.Fingerprint != approval.Fingerprint || receipt.ID != req.ID || receipt.Deadline != req.Deadline {
		t.Fatal("server fingerprint did not bind the intended enrollment")
	}
	if enrollmentCall(NewAPI(s), enrollmentEndpoint, oldToken, req).Code != 404 {
		t.Fatal("registration was enabled without deployment configuration")
	}
	check(enrollmentEndpoint, oldToken, req, 200)
	otherReq, _, _, _ := signedEnrollmentTest(t, owner.AccountID, oldToken)
	check(enrollmentEndpoint, oldToken, otherReq, 401)
	check("/v1/session", deviceToken, nil, 401)
	check(enrollmentEndpoint+"/claim", oldToken, claim(oldToken), 409)
	check(enrollmentEndpoint+"/approve", other.Token, approval, 401)
	check(enrollmentEndpoint+"/approve", oldToken, approval, 401)
	member, err := s.AddDevice(ctx, actor, "Synthetic existing member")
	if err != nil {
		t.Fatal("create synthetic existing member")
	}
	check(enrollmentEndpoint+"/approve", member.Token, approval, 403)
	check(enrollmentEndpoint+"/approve", owner.Token, enrollmentApproval{req.ID, strings.Repeat("0", 64)}, 401)
	check(enrollmentEndpoint+"/approve", owner.Token, approval, 204)
	check(enrollmentEndpoint+"/approve", owner.Token, approval, 204)
	check(enrollmentEndpoint+"/claim", otherToken, claim(otherToken), 401)
	check(enrollmentEndpoint+"/claim", freshToken, claim(oldToken), 401)
	check(enrollmentEndpoint+"/claim", freshToken, enrollmentClaim{req.ID, req.AccountID, req.Signature}, 401)
	proven := claim(freshToken)
	start, results := make(chan struct{}), make(chan *httptest.ResponseRecorder, 2)
	for range 2 {
		go func() {
			<-start
			results <- enrollmentCall(api, enrollmentEndpoint+"/claim", freshToken, proven)
		}()
	}
	close(start)
	for range 2 {
		out := <-results
		var session Session
		if out.Code != 200 || json.Unmarshal(out.Body.Bytes(), &session) != nil || session.DeviceID != req.ID ||
			session.AccountID != owner.AccountID || session.Role != "member" || strings.Contains(out.Body.String(), deviceToken) {
			t.Fatalf("concurrent claim did not return the original device: %d", out.Code)
		}
	}
	check("/v1/session", deviceToken, nil, 200)
	devices, err := s.Devices(ctx, actor)
	if err != nil || len(devices) != 3 {
		t.Fatal("enrollment did not create exactly one active device")
	}
	check("/v1/session", freshToken, nil, 401)
	if err = s.Revoke(ctx, actor, req.ID); err != nil {
		t.Fatal("revoke synthetic device")
	}
	check(enrollmentEndpoint+"/claim", freshToken, proven, 401)
	check("/v1/session", deviceToken, nil, 401)

	staleToken := sign(subject)
	stale, staleApproval, staleCredential, staleClaim := signedEnrollmentTest(t, owner.AccountID, staleToken)
	check(enrollmentEndpoint, staleToken, stale, 200)
	check(enrollmentEndpoint+"/approve", owner.Token, staleApproval, 204)
	if err = s.Revoke(ctx, actor, owner.DeviceID); err != nil {
		t.Fatal("revoke approving owner")
	}
	check(enrollmentEndpoint+"/claim", staleToken, staleClaim(staleToken), 401)
	rotated, err := s.RecoverDevice(ctx, owner.DeviceID)
	if err != nil {
		t.Fatal("rotate approving credential")
	}
	check(enrollmentEndpoint+"/approve", rotated.Token, staleApproval, 401)
	check(enrollmentEndpoint+"/claim", staleToken, staleClaim(staleToken), 401)
	check("/v1/session", staleCredential, nil, 401)
	current, err := s.Authenticate(ctx, rotated.Token)
	if err != nil {
		t.Fatal("authenticate rotated owner")
	}
	page, err := s.Pull(ctx, current, 0, 1)
	if err != nil || page.Head != 0 || len(page.Changes) != 0 {
		t.Fatal("enrollment changed synchronized data")
	}
}

func TestPostgresDeviceEnrollmentLimits(t *testing.T) {
	s := migrationDatabase(t)
	ctx := context.Background()
	api, sign := enrollmentTestAPI(t, s)
	owner, actor := provision(t, s)
	const subject = "limited-enrollment-subject"
	if err := s.BindOIDCSubject(ctx, owner.AccountID, enrollmentIssuer, subject); err != nil {
		t.Fatal("bind synthetic account")
	}
	var accepted enrollmentRequest
	var approval enrollmentApproval
	var claim func(string) enrollmentClaim
	for i := range 9 {
		access := sign(subject)
		req, id, _, prove := signedEnrollmentTest(t, owner.AccountID, access)
		out := enrollmentCall(api, enrollmentEndpoint, access, req)
		if i < 8 && out.Code != 200 || i == 8 && out.Code != 429 {
			t.Fatalf("wrong pending capacity status at index %d: %d", i, out.Code)
		}
		if i == 0 {
			accepted, approval, claim = req, id, prove
		}
	}
	for range 31 {
		if _, err := s.AddDevice(ctx, actor, "Synthetic capacity fixture"); err != nil {
			t.Fatal("seed active-device capacity")
		}
	}
	if out := enrollmentCall(api, enrollmentEndpoint+"/approve", owner.Token, approval); out.Code != 204 {
		t.Fatalf("owner approval: %d", out.Code)
	}
	access := sign(subject)
	if out := enrollmentCall(api, enrollmentEndpoint+"/claim", access, claim(access)); out.Code != 429 {
		t.Fatalf("active-device capacity was not enforced: %d", out.Code)
	}
	if _, err := s.DB.Exec(`UPDATE zapime_device_enrollments SET expires_at=clock_timestamp()-interval '1 second' WHERE id=$1`, accepted.ID); err != nil {
		t.Fatal("expire only the synthetic approved attempt")
	}
	if out := enrollmentCall(api, enrollmentEndpoint+"/claim", access, claim(access)); out.Code != 401 {
		t.Fatalf("expired device enrollment has been accepted: %d", out.Code)
	}
}
