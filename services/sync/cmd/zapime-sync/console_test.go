// SPDX-License-Identifier: GPL-3.0-only
package main

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/ForceMind/ZapIME/services/sync/internal/oidcauth"
)

func consoleStartupEnvironment(t *testing.T) {
	t.Helper()
	for _, name := range []string{"ZAPIME_CONSOLE_CLIENT_SECRET", "ZAPIME_CONSOLE_ADMINS", "ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT", "ZAPIME_CONSOLE_PASSWORD_RESET_TOKEN", "ZAPIME_CONSOLE_MASTER_KEY_FILE"} {
		t.Setenv(name, "")
	}
	t.Setenv("ZAPIME_CONSOLE_ORIGIN", "https://sync.example.test")
	t.Setenv("ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT", "https://login.example.test/authorize")
	t.Setenv("ZAPIME_CONSOLE_TOKEN_ENDPOINT", "https://login.example.test/token")
	t.Setenv("ZAPIME_OIDC_CLIENT_ID", "synthetic-browser")
	t.Setenv("ZAPIME_OIDC_AUDIENCE", "https://sync.example.test/device-enrollment")
}

func TestConsoleDisabledDoesNotReadKey(t *testing.T) {
	consoleStartupEnvironment(t)
	t.Setenv("ZAPIME_CONSOLE_ORIGIN", "")
	t.Setenv("ZAPIME_CONSOLE_MASTER_KEY_FILE", filepath.Join(t.TempDir(), "missing.key"))
	console, vault, err := loadConsole(nil, nil)
	if err != nil || console != nil || vault != nil {
		t.Fatal("legacy deployment unexpectedly requires a console key", err)
	}
}

func TestConsoleStartupRequiresProtectedExactKeyFile(t *testing.T) {
	consoleStartupEnvironment(t)
	verifier := new(oidcauth.Verifier) // Only constructor configuration is tested.
	root := t.TempDir()
	for _, value := range []string{"", "relative.key", filepath.Join(root, "missing.key"), root} {
		t.Setenv("ZAPIME_CONSOLE_MASTER_KEY_FILE", value)
		if _, _, err := loadConsole(nil, verifier); !errors.Is(err, errConsoleMasterKey) {
			t.Fatal("missing, relative, or non-file key accepted", err)
		}
	}
	name := filepath.Join(root, "synthetic.key")
	t.Setenv("ZAPIME_CONSOLE_MASTER_KEY_FILE", name)
	for _, length := range []int{0, 31, 33, 64, 1024} {
		if err := os.WriteFile(name, []byte(strings.Repeat("S", length)), 0o600); err != nil {
			t.Fatal(err)
		}
		_, _, err := loadConsole(nil, verifier)
		if !errors.Is(err, errConsoleMasterKey) || strings.Contains(err.Error(), name) {
			t.Fatal("incorrect key size accepted or path leaked", err)
		}
	}
	if err := os.WriteFile(name, []byte(strings.Repeat("S", 32)), 0o600); err != nil {
		t.Fatal(err)
	}
	console, vault, err := loadConsole(nil, verifier)
	if err != nil || console == nil || vault == nil {
		t.Fatal("valid external 32-byte key rejected", err)
	}
	if runtime.GOOS != "windows" {
		if err = os.Chmod(name, 0o644); err != nil {
			t.Fatal(err)
		}
		if _, _, err = loadConsole(nil, verifier); !errors.Is(err, errConsoleMasterKey) {
			t.Fatal("world-readable key accepted", err)
		}
		if err = os.Chmod(name, 0o640); err != nil {
			t.Fatal(err)
		}
		if _, _, err = loadConsole(nil, verifier); err != nil {
			t.Fatal("container-readable 0640 key rejected", err)
		}
		link := filepath.Join(root, "linked.key")
		if err = os.Symlink(name, link); err != nil {
			t.Fatal(err)
		}
		t.Setenv("ZAPIME_CONSOLE_MASTER_KEY_FILE", link)
		if _, _, err = loadConsole(nil, verifier); !errors.Is(err, errConsoleMasterKey) {
			t.Fatal("symlink key accepted", err)
		}
	}
}
