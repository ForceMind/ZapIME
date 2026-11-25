// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strings"
	"time"
)

type accountLoginIdentity struct {
	Protocol           int    `json:"protocol"`
	AccountID          string `json:"account_id"`
	SubjectFingerprint string `json:"subject_fingerprint"`
}

func (api *API) accountLogin(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if api.oidc == nil || r.Method != http.MethodGet {
		write(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery || r.ContentLength != 0 || len(r.TransferEncoding) != 0 {
		failure(w, ErrInvalid)
		return
	}
	token, err := loginBearer(r)
	if err != nil {
		failure(w, err)
		return
	}
	proof, err := api.oidc.Verify(token, time.Now())
	if err != nil {
		failure(w, ErrUnauthorized)
		return
	}
	account, err := api.Store.ResolveOIDCAccount(ctx, proof)
	if err != nil {
		failure(w, err)
		return
	}
	issuer, subject, _, _ := proof.Claims()
	fingerprint := accountLoginFingerprint(issuer, subject, proof.Audience(), account)
	write(w, http.StatusOK, accountLoginIdentity{1, account, fingerprint})
}

func accountLoginFingerprint(issuer, subject, audience, account string) string {
	fingerprint := enrollmentHash("zapime-account-login-v1", issuer, subject, audience, account)
	return hex.EncodeToString(fingerprint[:])
}

func loginBearer(r *http.Request) (string, error) {
	values := r.Header.Values("Authorization")
	if len(values) != 1 || !strings.HasPrefix(values[0], "Bearer ") {
		return "", ErrUnauthorized
	}
	return strings.TrimPrefix(values[0], "Bearer "), nil
}

func (api *API) deviceEnrollment(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if api.oidc == nil || r.Method != http.MethodPost {
		write(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery {
		failure(w, ErrInvalid)
		return
	}
	token, err := loginBearer(r)
	if err != nil {
		failure(w, err)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if r.URL.Path == "/v1/device-enrollment/approve" {
		actor, err := api.Store.Authenticate(ctx, token)
		if err != nil {
			failure(w, err)
			return
		}
		var req enrollmentApproval
		if err := decode(w, r, &req); err != nil {
			failure(w, err)
			return
		}
		if err := api.Store.approveEnrollment(ctx, actor, req); err != nil {
			failure(w, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusNoContent)
		return
	}
	proof, err := api.oidc.Verify(token, time.Now())
	if err != nil {
		failure(w, ErrUnauthorized)
		return
	}
	accessHash := sha256.Sum256([]byte(token))
	if r.URL.Path == "/v1/device-enrollment/claim" {
		var req enrollmentClaim
		if err := decode(w, r, &req); err != nil {
			failure(w, err)
			return
		}
		session, err := api.Store.claimEnrollment(ctx, proof, accessHash, req)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, http.StatusOK, session)
		return
	}
	var req enrollmentRequest
	if err := decode(w, r, &req); err != nil {
		failure(w, err)
		return
	}
	receipt, err := api.Store.beginEnrollment(ctx, proof, accessHash, req)
	if err != nil {
		failure(w, err)
		return
	}
	write(w, http.StatusOK, receipt)
}
