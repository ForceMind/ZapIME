#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")"
umask 077
file=${1:?Usage: backup.sh /absolute/path/backup.dump}
objects="$file.objects.tar"
[[ $file == /* && ! -e "$file" && ! -L "$file" && ! -e "$objects" && ! -L "$objects" ]] || { echo 'Use a new absolute destination path' >&2; exit 1; }
compose=(docker compose)
if [[ -n ${ZAPIME_ENV_FILE:-} ]]; then compose+=(--env-file "$ZAPIME_ENV_FILE"); fi
temp=$(mktemp "$file.incoming.XXXXXX")
object_temp=$(mktemp "$objects.incoming.XXXXXX")
trap 'rm -f "$temp" "$object_temp"' EXIT
# Snapshot metadata first, then export an append-only superset of immutable objects.
"${compose[@]}" exec -T db pg_dump -U zapime -d zapime -Fc > "$temp"
"${compose[@]}" run --rm --no-deps -T sync export-objects > "$object_temp"
# Publish the DB dump last as the completion marker. Refuse any overwrite.
ln "$object_temp" "$objects"
ln "$temp" "$file"
echo "Backup complete: $file and $objects; keep BOTH files. Client keys are not included."
