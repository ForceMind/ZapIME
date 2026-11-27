// SPDX-License-Identifier: GPL-3.0-only
package main

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
	"github.com/ForceMind/ZapIME/services/sync/internal/syncapi"
)

var errConsoleMasterKey = errors.New("console_master_key_file_invalid")

// The key is operator-provided and separate from database/object backups. It
// is never created or replaced by the server, returned by HTTP, or logged.
func loadConsole(store *syncapi.Store, verifier *oidcauth.Verifier) (*syncapi.Console, *syncapi.ConsoleKeyVault, error) {
	console, err := syncapi.NewConsoleFromEnv(store, verifier)
	if err != nil || console == nil {
		return console, nil, err
	}
	name := os.Getenv("ZAPIME_CONSOLE_MASTER_KEY_FILE")
	if !filepath.IsAbs(name) {
		return nil, nil, errConsoleMasterKey
	}
	info, err := os.Lstat(name)
	if err != nil || !info.Mode().IsRegular() {
		return nil, nil, errConsoleMasterKey
	}
	file, err := os.Open(name)
	if err != nil {
		return nil, nil, errConsoleMasterKey
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !opened.Mode().IsRegular() || !os.SameFile(info, opened) {
		return nil, nil, errConsoleMasterKey
	}
	// Windows key privacy depends on its ACL, not the synthetic Unix mode bits.
	// Linux deployment allows root-owned 0640 files for container GID 65532.
	if runtime.GOOS != "windows" && opened.Mode().Perm()&0o137 != 0 {
		return nil, nil, errConsoleMasterKey
	}
	master, err := io.ReadAll(io.LimitReader(file, 33))
	defer clear(master)
	if err != nil || len(master) != 32 {
		return nil, nil, errConsoleMasterKey
	}
	vault, err := syncapi.NewConsoleKeyVault(master)
	if err != nil {
		return nil, nil, errConsoleMasterKey
	}
	return console, vault, nil
}
