#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")"
file=${1:?Usage: restore.sh /absolute/path/backup.dump}
[[ $file == /* && -f "$file" && -f "$file.objects.tar" ]] || { echo 'Restore needs the dump AND its .objects.tar sidecar' >&2; exit 1; }
compose=(docker compose)
if [[ -n ${ZAPIME_ENV_FILE:-} ]]; then compose+=(--env-file "$ZAPIME_ENV_FILE"); fi
[[ -z $("${compose[@]}" ps --status running -q sync) ]] || { echo 'Stop sync before restore' >&2; exit 1; }
tables=$("${compose[@]}" exec -T db psql -U zapime -d zapime -Atc "SELECT count(*) FROM pg_tables WHERE schemaname='public'")
[[ $tables == 0 ]] || { echo 'Restore requires a fresh empty database' >&2; exit 1; }
"${compose[@]}" exec -T db pg_restore -U zapime -d zapime --single-transaction --exit-on-error --no-owner --no-acl < "$file"
refs=$("${compose[@]}" exec -T db psql -U zapime -d zapime -Atc "SELECT count(*) FROM zapime_operations WHERE change->>'storage_version'='2'")
if (( refs > 0 )); then
  "${compose[@]}" run --rm --no-deps -T sync import-objects < "$file.objects.tar"
fi
"${compose[@]}" run --rm --no-deps -T sync verify-objects
echo 'Database and object import finished; start sync only after success. Client recovery keys are still required.'
