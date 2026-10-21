// SPDX-License-Identifier: GPL-3.0-only
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type module struct {
	Path, Version string
	Licenses      []string
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	if len(os.Args) != 2 {
		return fmt.Errorf("usage: collect-notices output-directory")
	}
	destination := os.Args[1]
	if err := os.MkdirAll(destination, 0755); err != nil {
		return err
	}
	list, err := exec.Command("go", "list", "-deps", "-f", "{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|{{.Module.Dir}}{{end}}", "./cmd/zapime-sync").Output()
	if err != nil {
		return err
	}
	seen := map[string]bool{}
	inventory := []module{}
	for _, line := range strings.Split(string(list), "\n") {
		if line == "" {
			continue
		}
		parts := strings.Split(line, "|")
		if len(parts) != 3 || seen[parts[0]] {
			continue
		}
		seen[parts[0]] = true
		directory := filepath.Join(destination, strings.ReplaceAll(parts[0], "/", "_")+"@"+parts[1])
		if err = os.MkdirAll(directory, 0755); err != nil {
			return err
		}
		entry := module{Path: parts[0], Version: parts[1], Licenses: []string{}}
		files, err := os.ReadDir(parts[2])
		if err != nil {
			return err
		}
		for _, file := range files {
			upper := strings.ToUpper(file.Name())
			wanted := false
			for _, prefix := range []string{"LICENSE", "LICENCE", "COPYING", "NOTICE", "COPYRIGHT", "AUTHORS", "PATENTS"} {
				if strings.HasPrefix(upper, prefix) {
					wanted = true
				}
			}
			if file.IsDir() || !wanted {
				continue
			}
			body, err := os.ReadFile(filepath.Join(parts[2], file.Name()))
			if err != nil {
				return err
			}
			if err = os.WriteFile(filepath.Join(directory, file.Name()), body, 0644); err != nil {
				return err
			}
			entry.Licenses = append(entry.Licenses, file.Name())
		}
		if len(entry.Licenses) == 0 {
			return fmt.Errorf("missing module license: %s", entry.Path)
		}
		inventory = append(inventory, entry)
	}
	root, err := exec.Command("go", "env", "GOROOT").Output()
	if err != nil {
		return err
	}
	body, err := os.ReadFile(filepath.Join(strings.TrimSpace(string(root)), "LICENSE"))
	if err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(destination, "Go-LICENSE"), body, 0644); err != nil {
		return err
	}
	body, err = json.MarshalIndent(inventory, "", "  ")
	if err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(destination, "inventory.json"), body, 0644); err != nil {
		return err
	}
	fmt.Printf("Collected runtime module notices: %d\n", len(inventory))
	return nil
}
