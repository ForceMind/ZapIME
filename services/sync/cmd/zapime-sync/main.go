// SPDX-License-Identifier: GPL-3.0-only
package main

import (
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
	"github.com/ForceMind/ZapIME/services/sync/internal/syncapi"
	_ "github.com/jackc/pgx/v5/stdlib"
)

func run() error {
	if len(os.Args) == 2 && os.Args[1] == "health" {
		client := http.Client{Timeout: 3 * time.Second}
		response, err := client.Get("http://127.0.0.1:8787/healthz")
		if err != nil {
			return errors.New("service_unhealthy")
		}
		response.Body.Close()
		if response.StatusCode != 200 {
			return errors.New("service_unhealthy")
		}
		return nil
	}
	objects, objectErr := syncapi.ObjectsFromEnv()
	if objectErr != nil {
		return errors.New("object_storage_configuration_invalid")
	}
	if len(os.Args) == 2 && (os.Args[1] == "check-storage" || os.Args[1] == "import-objects") {
		if objects == nil {
			return errors.New("object_storage_not_configured")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		if os.Args[1] == "import-objects" {
			if err := syncapi.ImportObjects(ctx, objects, os.Stdin); err != nil {
				return errors.New("object_import_failed")
			}
			return nil
		}
		if err := objects.Probe(ctx); err != nil {
			return errors.New("object_storage_check_failed")
		}
		return nil
	}
	if os.Getenv("DATABASE_URL") == "" {
		return errors.New("DATABASE_URL is required")
	}
	db, err := sql.Open("pgx", os.Getenv("DATABASE_URL"))
	if err != nil {
		return errors.New("database_configuration_invalid")
	}
	defer db.Close()
	db.SetMaxOpenConns(16)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(30 * time.Minute)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	store := &syncapi.Store{DB: db, Objects: objects}
	err = store.Migrate(ctx)
	if err == nil {
		err = store.CheckConfiguration(ctx)
	}
	cancel()
	if err != nil {
		return errors.New("database_migration_failed")
	}
	if len(os.Args) == 2 && (os.Args[1] == "export-objects" || os.Args[1] == "verify-objects") {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		var output io.Writer = os.Stdout
		if os.Args[1] == "verify-objects" {
			output = io.Discard
		}
		if err := store.ExportObjects(ctx, output); err != nil {
			return errors.New("object_export_failed")
		}
		return nil
	}
	if len(os.Args) == 5 && os.Args[1] == "bind-oidc-subject" {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := store.BindOIDCSubject(ctx, os.Args[2], os.Args[3], os.Args[4]); err != nil {
			if errors.Is(err, syncapi.ErrOIDCSubjectBound) {
				return syncapi.ErrOIDCSubjectBound
			}
			return errors.New("oidc_subject_binding_failed")
		}
		return nil
	}
	if len(os.Args) == 2 && (os.Args[1] == "bootstrap-oidc-account" || os.Args[1] == "bind-oidc-account") {
		var request struct {
			Account string `json:"account"`
			Issuer  string `json:"issuer"`
			Subject string `json:"subject"`
		}
		decoder := json.NewDecoder(io.LimitReader(os.Stdin, 4097))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil || decoder.Decode(new(any)) != io.EOF {
			return errors.New("oidc_binding_input_invalid")
		}
		login, err := oidcauth.FromEnv()
		if err != nil || login == nil || request.Issuer != os.Getenv("ZAPIME_OIDC_ISSUER") {
			return oidcauth.ErrConfiguration
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		bind := store.BindOIDCSubject
		if os.Args[1] == "bootstrap-oidc-account" {
			bind = store.BootstrapOIDCAccount
		}
		if err := bind(ctx, request.Account, request.Issuer, request.Subject); err != nil {
			return errors.New("oidc_subject_binding_failed")
		}
		return nil
	}
	if len(os.Args) > 1 {
		if len(os.Args) != 3 || (os.Args[1] != "provision" && os.Args[1] != "recover-device") {
			return errors.New("usage: zapime-sync [provision device-name | recover-device device-id | bind-oidc-subject account-id issuer subject]")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		var credential syncapi.Credential
		if os.Args[1] == "provision" {
			credential, err = store.Provision(ctx, os.Args[2])
		} else {
			credential, err = store.RecoverDevice(ctx, os.Args[2])
		}
		if err != nil {
			return errors.New("credential_provision_failed")
		}
		// 仅显式管理员命令输出一次新设备令牌，不在常规服务日志中打印。
		return json.NewEncoder(os.Stdout).Encode(credential)
	}
	// Only online requests use this disposable cache. Admin exports and integrity
	// checks above deliberately read the durable store rather than cached bytes.
	store.Objects = syncapi.NewCachedObjects(objects, 16*1024*1024, 10*time.Minute)
	address := os.Getenv("ZAPIME_LISTEN")
	if address == "" {
		address = "127.0.0.1:8787"
	}
	login, err := oidcauth.FromEnv()
	if err != nil {
		return err
	}
	automatic := os.Getenv("ZAPIME_OIDC_AUTO_ENROLL")
	if (automatic != "" && automatic != "0" && automatic != "1") || (automatic == "1" && login == nil) {
		return oidcauth.ErrConfiguration
	}
	api := syncapi.NewAPIWithOIDC(store, login)
	api.AllowAutomaticEnrollment = automatic == "1"
	api.Console, api.ConsoleVault, err = loadConsole(store, login)
	if err != nil {
		return err
	}
	handler := http.Handler(api)
	maintenance := os.Getenv("ZAPIME_MAINTENANCE_FLAG")
	route := ""
	if installation := os.Getenv("ZAPIME_DEPLOYMENT_ID"); installation != "" {
		if decoded, err := hex.DecodeString(installation); err != nil || len(decoded) != 16 {
			return errors.New("deployment_id_invalid")
		}
		route = "/.well-known/zapime-deploy/" + installation
	}
	if route != "" || maintenance != "" {
		handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if route != "" && r.Method == http.MethodGet && r.URL.EscapedPath() == route && r.URL.RawQuery == "" {
				w.Header().Set("Cache-Control", "no-store")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			if maintenance != "" && r.URL.EscapedPath() != "/healthz" {
				if _, err := os.Stat(maintenance); err == nil || !errors.Is(err, os.ErrNotExist) {
					w.Header().Set("Content-Type", "application/json")
					w.Header().Set("Cache-Control", "no-store")
					w.WriteHeader(http.StatusServiceUnavailable)
					_, _ = io.WriteString(w, "{\"error\":\"maintenance\"}\n")
					return
				}
			}
			api.ServeHTTP(w, r)
		})
	}
	server := &http.Server{Addr: address, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024}
	stopped := make(chan os.Signal, 1)
	signal.Notify(stopped, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(stopped)
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-stopped:
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			_ = server.Shutdown(ctx)
		case <-done:
		}
	}()
	log.Print("ZapIME ciphertext sync service starting")
	if err = server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return errors.New("http_server_failed")
	}
	return nil
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
}
