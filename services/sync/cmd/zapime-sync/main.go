// SPDX-License-Identifier: GPL-3.0-only
package main

import (
	"context"
	"database/sql"
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
	if len(os.Args) > 1 {
		if len(os.Args) != 3 || (os.Args[1] != "provision" && os.Args[1] != "recover-device") {
			return errors.New("usage: zapime-sync [provision device-name | recover-device device-id]")
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
	address := os.Getenv("ZAPIME_LISTEN")
	if address == "" {
		address = "127.0.0.1:8787"
	}
	server := &http.Server{Addr: address, Handler: syncapi.NewAPI(store), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024}
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
