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
# Browser cookies and pending OAuth handshakes are live credentials, not data
# recovery state. Never revive them from a restored snapshot. Older schemas
# without Console tables continue to restore through the same path.
"${compose[@]}" exec -T db psql -U zapime -d zapime -v ON_ERROR_STOP=1 -c '
DO $$ BEGIN
  IF to_regclass('\''public.zapime_console_sessions'\'') IS NOT NULL THEN
    UPDATE zapime_devices SET revoked_at=COALESCE(revoked_at,now())
      WHERE id IN (SELECT device_id FROM zapime_console_sessions);
    DELETE FROM zapime_console_sessions;
    DELETE FROM zapime_console_logins;
  END IF;
END $$;' >/dev/null
refs=$("${compose[@]}" exec -T db psql -U zapime -d zapime -Atc "SELECT count(*) FROM zapime_operations WHERE change->>'storage_version'='2'")
if (( refs > 0 )); then
  "${compose[@]}" run --rm --no-deps -T sync import-objects < "$file.objects.tar"
fi
"${compose[@]}" run --rm --no-deps -T sync verify-objects
echo 'Database and object import finished; browser sessions invalidated. Start sync only after success. Restore the SAME external console master key separately; client recovery materials are still required.'
