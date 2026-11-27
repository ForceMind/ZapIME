// SPDX-License-Identifier: GPL-3.0-only
package syncapi

import (
	"context"
	"embed"
	"io"
	"net/http"
	"strconv"
	"strings"
)

//go:embed consoleweb/index.html consoleweb/app.js consoleweb/app.css
var consoleWeb embed.FS

func (api *API) consoleHTTP(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
	if api.Console == nil {
		http.NotFound(w, r)
		return
	}
	if api.serveConsoleAuth(ctx, w, r) {
		return
	}
	path := r.URL.Path
	const prefix = "/console/api/admin/accounts/"
	parts := strings.Split(strings.TrimPrefix(path, prefix), "/")
	if strings.HasPrefix(path, prefix) && len(parts) == 2 && parts[1] == "records" {
		if !validID(parts[0]) || r.Method != http.MethodGet {
			failure(w, ErrInvalid)
			return
		}
		actor, err := api.consoleActor(ctx, w, r, false)
		if err != nil {
			failure(w, err)
			return
		}
		if !actor.Admin {
			failure(w, ErrForbidden)
			return
		}
		api.serveConsolePage(ctx, w, r, actor, parts[0], true)
		return
	}
	if api.serveConsoleAdmin(ctx, w, r) {
		return
	}
	if path == "/console/api/material/enable" || path == "/console/api/records" || strings.HasPrefix(path, "/console/api/records/") {
		actor, err := api.consoleActor(ctx, w, r, r.Method != http.MethodGet)
		if err != nil {
			failure(w, err)
			return
		}
		if path == "/console/api/records" && r.Method == http.MethodGet {
			api.serveConsolePage(ctx, w, r, actor, actor.AccountID, false)
			return
		}
		if r.URL.RawQuery != "" || r.URL.ForceQuery {
			failure(w, ErrInvalid)
			return
		}
		if path == "/console/api/material/enable" {
			var input struct {
				Consent bool `json:"consent"`
			}
			if r.Method != http.MethodPost || decode(w, r, &input) != nil || !input.Consent {
				failure(w, ErrInvalid)
				return
			}
			state, err := api.ConsoleEnableMaterial(ctx, actor, api.ConsoleVault)
			if err != nil {
				failure(w, err)
				return
			}
			write(w, 200, state)
			return
		}
		input := ConsoleRecordInput{}
		if err = decode(w, r, &input); err != nil {
			failure(w, err)
			return
		}
		switch r.Method {
		case http.MethodPost:
			if path != "/console/api/records" || input.BaseRevision != 0 || input.Deleted || input.Content.Type != "dictionary" {
				failure(w, ErrInvalid)
				return
			}
		case http.MethodPut, http.MethodDelete:
			if path != "/console/api/records/"+input.RecordID || input.BaseRevision <= 0 || input.Deleted != (r.Method == http.MethodDelete) {
				failure(w, ErrInvalid)
				return
			}
		default:
			failure(w, ErrInvalid)
			return
		}
		view, err := api.ConsoleWriteRecord(ctx, actor, api.ConsoleVault, input)
		if err != nil {
			failure(w, err)
			return
		}
		write(w, 200, view)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.NotFound(w, r)
		return
	}
	var file, mime string
	switch path {
	case "/console", "/console/admin":
		http.Redirect(w, r, path+"/", http.StatusPermanentRedirect)
		return
	case "/console/", "/console/admin/":
		file, mime = "index.html", "text/html; charset=utf-8"
	case "/console/app.css":
		file, mime = "app.css", "text/css; charset=utf-8"
	case "/console/app.js":
		file, mime = "app.js", "text/javascript; charset=utf-8"
	default:
		http.NotFound(w, r)
		return
	}
	content, err := consoleWeb.ReadFile("consoleweb/" + file)
	if err != nil {
		failure(w, err)
		return
	}
	w.Header().Set("Content-Type", mime)
	if r.Method == http.MethodGet {
		_, _ = w.Write(content)
	}
}

func (api *API) serveConsolePage(ctx context.Context, w http.ResponseWriter, r *http.Request, actor ConsoleActor, target string, admin bool) {
	q := r.URL.Query()
	for k, v := range q {
		if len(v) != 1 || (k != "limit" && k != "kind" && k != "after") {
			failure(w, ErrInvalid)
			return
		}
	}
	limit := 40
	if n := q.Get("limit"); n != "" {
		var err error
		limit, err = strconv.Atoi(n)
		if err != nil {
			failure(w, ErrInvalid)
			return
		}
	}
	page, err := api.ConsoleRecords(ctx, actor, target, api.ConsoleVault, q.Get("kind"), q.Get("after"), limit, admin)
	if err != nil {
		failure(w, err)
		return
	}
	write(w, 200, page)
}

// Secret material travels only on the explicitly enabled device endpoint.
// The binary format is the same fixed, account-bound material verified by JNI.
func (api *API) consoleDeviceKey(ctx context.Context, w http.ResponseWriter, r *http.Request, actor Identity) {
	if api.Console == nil || api.ConsoleVault == nil {
		http.NotFound(w, r)
		return
	}
	action := strings.TrimPrefix(r.URL.Path, "/v1/console-material/")
	if r.Method != http.MethodPost || (action != "enable" && action != "import") || r.URL.RawQuery != "" || r.URL.ForceQuery || r.Header.Get("Content-Type") != "application/octet-stream" {
		failure(w, ErrInvalid)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 72))
	defer clear(body)
	if err != nil || (action == "import" && len(body) != 72) || (action == "enable" && len(body) != 0) {
		failure(w, ErrInvalid)
		return
	}
	material, err := api.Store.ConsoleDeviceMaterial(ctx, actor, api.ConsoleVault, action, body)
	if err != nil {
		failure(w, err)
		return
	}
	defer clear(material)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(material)
}
