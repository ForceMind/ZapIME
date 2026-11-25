// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

type a3EnrollmentRequest struct {
	Operation     string
	Authorization string
	Body          json.RawMessage
}

type a3EnrollmentResponse struct {
	Status int             `json:"status"`
	Body   json.RawMessage `json:"body"`
}

// Only the CI fixture uses this private stdin/stdout bridge. Android contacts
// the same test HTTPS origin and the real Go router and PostgreSQL handle writes.
func TestProviderIssuedEnrollmentRoutes(t *testing.T) {
	if os.Getenv("ZAPIME_A3_ENROLLMENT_ROUTES") != "1" {
		t.Skip("driven by the isolated Android enrollment fixture")
	}
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 4096), 32*1024)
	if !scanner.Scan() {
		t.Fatal("missing fixture JWKS")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	store, api := a3FixtureAccountAPI(t, ctx, scanner.Bytes())
	ownerToken, ownerHash, err := newToken()
	if err != nil {
		t.Fatal("cannot create synthetic owner credential")
	}
	ownerID := id(t)
	if _, err := store.DB.ExecContext(ctx, `INSERT INTO zapime_devices(id,account_id,name,role,token_hash,expires_at)
        VALUES($1,$2,'A3 synthetic owner','owner',$3,now()+interval '1 minute')`,
		ownerID, a3FixtureAccount, ownerHash); err != nil {
		t.Fatal("cannot provision synthetic owner")
	}
	if _, err := store.Authenticate(ctx, ownerToken); err != nil {
		t.Fatal("cannot authenticate synthetic owner")
	}
	out := json.NewEncoder(os.Stdout)
	if out.Encode(a3EnrollmentResponse{Status: 200}) != nil {
		t.Fatal("cannot start enrollment fixture")
	}
	var enrolled enrollmentReceipt
	for count := 0; count < 16 && scanner.Scan(); count++ {
		var operation a3EnrollmentRequest
		if json.Unmarshal(scanner.Bytes(), &operation) != nil || len(operation.Body) > 4096 {
			t.Fatal("invalid fixture request")
		}
		reply := a3EnrollmentResponse{Status: 204}
		switch operation.Operation {
		case "begin":
			reply = a3EnrollmentRoute(api, http.MethodPost, "/v1/device-enrollment", operation)
			if reply.Status == http.StatusOK && json.Unmarshal(reply.Body, &enrolled) != nil {
				t.Fatal("invalid enrollment receipt")
			}
		case "claim":
			reply = a3EnrollmentRoute(api, http.MethodPost, "/v1/device-enrollment/claim", operation)
		case "session":
			reply = a3EnrollmentRoute(api, http.MethodGet, "/v1/session", operation)
		case "approve":
			body, err := json.Marshal(enrollmentApproval{enrolled.ID, enrolled.Fingerprint})
			reply = a3EnrollmentRoute(api, http.MethodPost, "/v1/device-enrollment/approve",
				a3EnrollmentRequest{Authorization: "Bearer " + ownerToken, Body: body})
			if err != nil || enrolled.ID == "" || reply.Status != http.StatusNoContent {
				t.Fatal("independent fixture owner could not approve the enrollment")
			}
		case "revoke":
			revoked := a3EnrollmentRoute(api, http.MethodDelete, "/v1/devices/"+enrolled.ID,
				a3EnrollmentRequest{Authorization: "Bearer " + ownerToken})
			if enrolled.ID == "" || revoked.Status != http.StatusOK {
				t.Fatal("independent fixture owner could not revoke the enrollment")
			}
		case "finish":
			var head, total, revoked int
			if err := store.DB.QueryRowContext(ctx, `SELECT revision,
              (SELECT count(*) FROM zapime_devices WHERE account_id=$1),
              (SELECT count(*) FROM zapime_devices WHERE account_id=$1 AND id=$2 AND revoked_at IS NOT NULL)
              FROM zapime_accounts WHERE id=$1`, a3FixtureAccount, enrolled.ID).Scan(&head, &total, &revoked); err != nil ||
				head != 0 || total != 2 || revoked != 1 {
				t.Fatal("unexpected net device or data change")
			}
			if out.Encode(reply) != nil {
				t.Fatal("cannot report fixture result")
			}
			return
		default:
			t.Fatal("unexpected fixture operation")
		}
		if out.Encode(reply) != nil {
			t.Fatal("cannot report fixture response")
		}
	}
	t.Fatal("enrollment fixture did not finish")
}

func a3EnrollmentRoute(api *API, method, path string, operation a3EnrollmentRequest) a3EnrollmentResponse {
	request := httptest.NewRequest(method, path, bytes.NewReader(operation.Body))
	if strings.HasPrefix(operation.Authorization, "Bearer ") {
		request.Header.Set("Authorization", operation.Authorization)
	}
	if method == http.MethodPost {
		request.Header.Set("Content-Type", "application/json")
	}
	out := httptest.NewRecorder()
	api.ServeHTTP(out, request)
	return a3EnrollmentResponse{Status: out.Code, Body: out.Body.Bytes()}
}
