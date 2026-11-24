// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"net/http"
	"strings"
	"time"
)

func (api *API) accountLogin(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	if api.oidc == nil || r.Method != http.MethodGet {
		write(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery || r.ContentLength != 0 || len(r.TransferEncoding) != 0 {
		failure(w, ErrInvalid)
		return
	}
	values := r.Header.Values("Authorization")
	if len(values) != 1 || !strings.HasPrefix(values[0], "Bearer ") {
		failure(w, ErrUnauthorized)
		return
	}
	proof, err := api.oidc.Verify(strings.TrimPrefix(values[0], "Bearer "), time.Now())
	if err != nil {
		failure(w, ErrUnauthorized)
		return
	}
	account, err := api.Store.ResolveOIDCAccount(ctx, proof)
	if err != nil {
		failure(w, err)
		return
	}
	write(w, http.StatusOK, struct {
		Protocol  int    `json:"protocol"`
		AccountID string `json:"account_id"`
	}{1, account})
}
