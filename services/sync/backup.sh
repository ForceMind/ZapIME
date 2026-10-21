#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")"
umask 077
file=${1:?Usage: backup.sh /absolute/path/backup.dump}
[[ $file == /* && ! -e "$file" && ! -L "$file" ]] || { echo 'Use a new absolute destination path' >&2; exit 1; }
temp=$(mktemp "$file.incoming.XXXXXX")
trap 'rm -f "$temp"' EXIT
docker compose exec -T db pg_dump -U zapime -d zapime -Fc > "$temp"
ln "$temp" "$file"
echo 'Database backup written; client encryption keys are not included.'
