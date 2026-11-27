// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

type API struct {
	Console                  *Console
	ConsoleVault             *ConsoleKeyVault
	Store                    *Store
	AllowAutomaticEnrollment bool
	slots                    chan struct{}
	oidc                     *oidcauth.Verifier
}

func NewAPI(store *Store) *API { return NewAPIWithOIDC(store, nil) }

func NewAPIWithOIDC(store *Store, verifier *oidcauth.Verifier) *API {
	return &API{Store: store, slots: make(chan struct{}, 32), oidc: verifier}
}
func write(w http.ResponseWriter, status int, object any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(object)
}
func failure(w http.ResponseWriter, err error) {
	var conflict *Conflict
	switch {
	case errors.As(err, &conflict):
		write(w, http.StatusConflict, map[string]any{"error": "revision_conflict", "current": conflict.Current})
	case errors.Is(err, ErrUnauthorized):
		w.Header().Set("WWW-Authenticate", "Bearer")
		write(w, 401, map[string]string{"error": "unauthorized"})
	case errors.Is(err, ErrForbidden):
		write(w, 403, map[string]string{"error": "forbidden"})
	case errors.Is(err, ErrOperationReused):
		write(w, 409, map[string]string{"error": "operation_id_reused"})
	case errors.Is(err, ErrCursor):
		write(w, 409, map[string]string{"error": "invalid_cursor"})
	case errors.Is(err, ErrConsoleKeyUnavailable):
		write(w, 503, map[string]string{"error": "console_key_unavailable"})
	case errors.Is(err, ErrConsoleKeyRequired):
		write(w, 409, map[string]string{"error": "console_key_required"})
	case errors.Is(err, ErrConsoleDictionaryDuplicate):
		write(w, 409, map[string]string{"error": "duplicate_dictionary_entry"})
	case errors.Is(err, ErrConsoleRecordExpired):
		write(w, 410, map[string]string{"error": "record_expired"})
	case errors.Is(err, ErrConsoleRecordDeleted):
		write(w, 410, map[string]string{"error": "record_deleted"})
	case errors.Is(err, ErrKeyDescriptorConflict):
		write(w, 409, map[string]string{"error": "key_descriptor_conflict"})
	case errors.Is(err, ErrKeyDescriptorLegacy):
		write(w, 409, map[string]string{"error": "key_descriptor_legacy"})
	case errors.Is(err, ErrKeyDescriptorMismatch):
		write(w, 409, map[string]string{"error": "key_descriptor_mismatch"})
	case errors.Is(err, ErrEnrollmentPending):
		write(w, 409, map[string]string{"error": "enrollment_pending"})
	case errors.Is(err, ErrInvalid):
		write(w, 400, map[string]string{"error": "invalid_request"})
	case errors.Is(err, ErrLimit):
		write(w, 429, map[string]string{"error": "limit_reached"})
	default:
		write(w, 503, map[string]string{"error": "storage_unavailable"})
	}
}
func decode(w http.ResponseWriter, r *http.Request, out any) error {
	if r.Header.Get("Content-Type") != "application/json" {
		return ErrInvalid
	}
	r.Body = http.MaxBytesReader(w, r.Body, 100000)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(out); err != nil {
		return ErrInvalid
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return ErrInvalid
	}
	return nil
}

func (api *API) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	select {
	case api.slots <- struct{}{}:
		defer func() { <-api.slots }()
	default:
		write(w, 503, map[string]string{"error": "server_busy"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if r.URL.Path == "/console" || strings.HasPrefix(r.URL.Path, "/console/") {
		api.consoleHTTP(ctx, w, r)
		return
	}
	if r.URL.Path == "/healthz" && r.Method == http.MethodGet {
		if err := api.Store.DB.PingContext(ctx); err != nil {
			failure(w, err)
			return
		}
		write(w, 200, map[string]any{"status": "ready", "protocol": 1})
		return
	}
	switch r.URL.Path {
	case "/v1/account-login":
		api.accountLogin(ctx, w, r)
		return
	case "/v1/device-enrollment/automatic":
		api.automaticDeviceEnrollment(ctx, w, r)
		return
	case "/v1/device-enrollment", "/v1/device-enrollment/approve", "/v1/device-enrollment/claim":
		api.deviceEnrollment(ctx, w, r)
		return
	}
	authorization := r.Header.Get("Authorization")
	if !strings.HasPrefix(authorization, "Bearer ") {
		failure(w, ErrUnauthorized)
		return
	}
	actor, err := api.Store.Authenticate(ctx, strings.TrimPrefix(authorization, "Bearer "))
	if err != nil {
		failure(w, err)
		return
	}
	switch {
	case strings.HasPrefix(r.URL.Path, "/v1/console-material/"):
		api.consoleDeviceKey(ctx, w, r, actor)
	case r.URL.Path == "/v1/key-descriptor" && r.Method == http.MethodGet:
		if r.URL.RawQuery != "" || r.URL.ForceQuery || r.ContentLength != 0 || len(r.TransferEncoding) != 0 {
			failure(w, ErrInvalid)
			return
		}
		state, err := api.Store.KeyDescriptor(ctx, actor)
		if err != nil {
			failure(w, err)
			return
		}
		if api.Console != nil && api.ConsoleVault != nil {
			w.Header().Set("Zapime-Console-Material", "1")
		}
		write(w, 200, state)
	case (r.URL.Path == "/v1/key-descriptor" || r.URL.Path == "/v1/key-descriptor/initialize") && r.Method == http.MethodPut:
		initialize := r.URL.Path == "/v1/key-descriptor/initialize"
		if initialize && (api.oidc == nil || !api.AllowAutomaticEnrollment) {
			write(w, http.StatusNotFound, map[string]string{"error": "not_found"})
			return
		}
		input, err := decodeKeyDescriptor(w, r)
		if err != nil {
			failure(w, err)
			return
		}
		state, err := api.Store.registerKeyDescriptor(ctx, actor, input, initialize)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, 200, state)
	case r.URL.Path == "/v1/session" && r.Method == http.MethodGet:
		// Read-only identity for the presented device token. No caller-supplied
		// account selector, key material, object reads or sync cursor mutation.
		write(w, 200, Session{1, actor.AccountID, actor.DeviceID, actor.Role, actor.ExpiresAt.UTC()})
	case r.URL.Path == "/v1/changes" && r.Method == http.MethodPost:
		var op Operation
		if err = decode(w, r, &op); err != nil {
			failure(w, err)
			return
		}
		result, err := api.Store.Push(ctx, actor, op)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, 200, result)
	case r.URL.Path == "/v1/changes" && r.Method == http.MethodGet:
		after := int64(0)
		limit := 100
		if raw := r.URL.Query().Get("after"); raw != "" {
			after, err = strconv.ParseInt(raw, 10, 64)
			if err != nil {
				failure(w, ErrInvalid)
				return
			}
		}
		if raw := r.URL.Query().Get("limit"); raw != "" {
			limit, err = strconv.Atoi(raw)
			if err != nil {
				failure(w, ErrInvalid)
				return
			}
		}
		page, err := api.Store.Pull(ctx, actor, after, limit)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, 200, page)
	case r.URL.Path == "/v1/devices" && r.Method == http.MethodGet:
		devices, err := api.Store.Devices(ctx, actor)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, 200, map[string]any{"devices": devices})
	case r.URL.Path == "/v1/devices" && r.Method == http.MethodPost:
		var input struct {
			Name string `json:"name"`
		}
		if err = decode(w, r, &input); err != nil {
			failure(w, err)
			return
		}
		credential, err := api.Store.AddDevice(ctx, actor, input.Name)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, 201, credential)
	case strings.HasPrefix(r.URL.Path, "/v1/devices/") && r.Method == http.MethodDelete:
		if err = api.Store.Revoke(ctx, actor, strings.TrimPrefix(r.URL.Path, "/v1/devices/")); err != nil {
			failure(w, err)
			return
		}
		write(w, 200, map[string]bool{"revoked": true})
	default:
		write(w, 404, map[string]string{"error": "not_found"})
	}
}
