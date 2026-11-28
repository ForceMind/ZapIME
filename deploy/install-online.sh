#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Online entry for the installer and configuration wizard verified in CI.

zapime_install_online() {
  local revision=bd29e738da889efdbbf2fd47c290e8d9038a0eac
  local digest=c4bd3dfd4bdc2e0e6ffd668f56df7330ff2d6569c2c267df078398a1cedebfb8
  local -a elevate=()
  if (( EUID != 0 )); then
    elevate=(sudo --)
  fi
  "${elevate[@]}" bash -c '
    set -euo pipefail
    umask 077
    installer=$(mktemp)
    trap '\''rm -f -- "$installer"'\'' EXIT
    curl -fsSL --proto "=https" --proto-redir "=https" \
      "https://raw.githubusercontent.com/ForceMind/ZapIME/$1/deploy/install.sh" -o "$installer"
    printf "%s  %s\n" "$2" "$installer" | sha256sum -c - >/dev/null
    bash "$installer" --wizard --ref "$1"
  ' zapime-install "$revision" "$digest"
}

zapime_install_online
