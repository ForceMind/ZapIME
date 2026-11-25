#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Sourced only from the root-owned release under the outer installer lock.

declare -a previous_compose
upgrading=0
upgrade_journal="$root/upgrade.pending"
maintenance_flag="$root/deployment-state/frozen"

upgrade_record() {
  local step=$1
  python3 -I - "$upgrade_journal" "$old_release" "$release" "$final_backup" "$step" <<'PY'
import os
from pathlib import Path
import sys
import tempfile

journal = Path(sys.argv[1])
descriptor, staged = tempfile.mkstemp(prefix="upgrade.incoming.", dir=journal.parent)
try:
    with os.fdopen(descriptor, "w", encoding="ascii") as output:
        output.write("\n".join(sys.argv[2:]) + "\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(staged, journal)
    directory = os.open(journal.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
finally:
    if os.path.exists(staged):
        os.unlink(staged)
PY
  phase=$step
}

trial_migration() (
  set -Eeuo pipefail
  local archive trial override db_name
  archive=$(mktemp "$root/trial.XXXXXXXX")
  override=$(mktemp "$root/trial-compose.XXXXXXXX")
  trial="zapime-rehearsal-$(openssl rand -hex 8)"
  db_name="$trial-db"
  # Reach private object storage on the installed network without resolving
  # the installed database: the candidate uses only the unique shadow alias.
  python3 -I - "$override" "$db_name" <<'PY'
import json
from pathlib import Path
import sys

output = {
    "services": {
        "db": {"networks": {"default": {"aliases": [sys.argv[2]]}}},
        "sync": {
            "environment": {"DATABASE_URL": f"postgres://zapime@{sys.argv[2]}:5432/zapime?sslmode=disable"},
            "networks": ["default", "installed-storage"],
        },
    },
    "networks": {"installed-storage": {"external": True, "name": "zapime_default"}},
}
Path(sys.argv[1]).write_text(json.dumps(output))
PY
  local -a shadow=(docker compose --project-name "$trial" --env-file "$root/config.env"
                   -f "$release/services/sync/compose.yml" -f "$override")
  trap 'f=$?; if ! "${shadow[@]}" down --volumes >/dev/null; then f=1; fi; rm -f -- "$archive" "$override"; exit "$f"' EXIT
  "${previous_compose[@]}" exec -T db pg_dump -U zapime -d zapime -Fc > "$archive"
  "${shadow[@]}" up -d --wait db
  "${shadow[@]}" exec -T db pg_restore -U zapime -d zapime --single-transaction --exit-on-error --no-owner --no-acl < "$archive"
  # Migrate the independent database and read the immutable object references.
  # This command performs no object writes.
  "${shadow[@]}" run --rm --no-deps -T sync verify-objects
  echo 'Upgrade rehearsal [PASS]'
)

begin_upgrade() {
  final_backup="$root/backups/upgrade-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 8).dump"
  phase=
  if [[ -f $upgrade_journal ]]; then
    local saved_source saved_target
    {
      read -r saved_source
      read -r saved_target
      read -r final_backup
      read -r phase
    } < "$upgrade_journal"
    [[ $saved_source =~ ^/opt/zapime/releases/[0-9a-f-]+$ && -d $saved_source &&
       $saved_target == "$release" && $final_backup =~ ^/opt/zapime/backups/upgrade-[A-Za-z0-9-]+\.dump$ &&
       ( $phase == rehearsed || $phase == backup_complete || $phase == verified ) ]] ||
      { echo 'Pending upgrade requires the same verified release. Run zapime status for recovery details.' >&2; return 1; }
    old_release=$saved_source
  else
    [[ -e $root/current ]] || return 0
    old_release=$(readlink -f "$root/current")
    [[ $old_release != "$release" ]] || return 0
  fi
  upgrading=1
  previous_compose=(docker compose --env-file "$root/config.env" -f "$old_release/services/sync/compose.yml")
  if [[ -z $phase ]]; then
    trial_migration
    upgrade_record rehearsed
  fi
  install -m 0644 /dev/null "$maintenance_flag"
  if [[ $phase == rehearsed ]]; then
    "${previous_compose[@]}" stop -t 25 sync
    [[ -z $("${previous_compose[@]}" ps --status running -q sync) ]] ||
      { echo 'The old writer is still running' >&2; return 1; }
    if [[ ! -f $final_backup || ! -f $final_backup.objects.tar ]]; then
      # Retain partial output for diagnosis, and use a new name on retry.
      if [[ -e $final_backup || -e $final_backup.objects.tar ]]; then
        final_backup="$root/backups/upgrade-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 8).dump"
        upgrade_record rehearsed
      fi
      COMPOSE_FILE="$old_release/services/sync/compose.yml" bash "$old_release/services/sync/backup.sh" "$final_backup"
    fi
    upgrade_record backup_complete
  fi
}

verify_upgrade() {
  [[ $upgrading == 1 ]] || return 0
  "${compose[@]}" run --rm --no-deps -T sync verify-objects
  upgrade_record verified
}

finish_upgrade() {
  [[ $upgrading == 1 ]] || return 0
  [[ $phase == verified && $(readlink -f "$root/current") == "$release" ]] ||
    { echo 'Release is still frozen. Run zapime status.' >&2; return 1; }
  # The pointer already identifies the tested release. If archival is
  # interrupted a retry can recheck the same current release and finish.
  rm -f -- "$maintenance_flag"
  mv -f -- "$upgrade_journal" "$root/upgrade.last"
  echo "Upgrade [PASS] Verified backup at $final_backup"
}
