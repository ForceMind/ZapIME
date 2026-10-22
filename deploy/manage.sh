#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
root=${ZAPIME_INSTALL_DIR:-/opt/zapime}
[[ -f $root/config.env && -d $root/current/services/sync ]] || { echo 'ZapIME installation not found' >&2; exit 1; }
export COMPOSE_PROJECT_NAME=zapime ZAPIME_ENV_FILE="$root/config.env"
cd "$root/current/services/sync"
compose=(docker compose --env-file "$ZAPIME_ENV_FILE")
action=${1:-status}
case "$action" in
  status) "${compose[@]}" ps; curl --fail --silent --show-error http://127.0.0.1:8787/healthz; echo ;;
  logs) "${compose[@]}" logs --tail 100 sync ;;
  stop) "${compose[@]}" --profile https stop ;;
  start)
    mode=$(sed -n 's/^ZAPIME_HTTPS_MODE=//p' "$ZAPIME_ENV_FILE")
    if [[ ${mode:-caddy} == caddy ]]; then "${compose[@]}" --profile https up -d --wait; else "${compose[@]}" up -d --wait db sync; fi ;;
  backup)
    destination=${2:-$root/backups/$(date -u +%Y%m%dT%H%M%SZ).dump}
    install -d -m 700 "$root/backups"
    bash backup.sh "$destination" ;;
  restore)
    [[ $# == 2 ]] || { echo 'Usage: zapime restore /absolute/path/backup.dump (fresh database, sync stopped)' >&2; exit 1; }
    bash restore.sh "$2" ;;
  restore-objects)
    [[ $# == 2 && -f $2 ]] || { echo 'Usage: zapime restore-objects /absolute/path/backup.dump.objects.tar' >&2; exit 1; }
    "${compose[@]}" run --rm --no-deps -T sync import-objects < "$2"
    "${compose[@]}" run --rm --no-deps -T sync verify-objects ;;
  verify-objects) "${compose[@]}" run --rm --no-deps -T sync verify-objects ;;
  check-storage) "${compose[@]}" run --rm --no-deps -T sync check-storage ;;
  recover-device)
    [[ $# == 2 ]] || { echo 'Usage: zapime recover-device device-uuid' >&2; exit 1; }
    "${compose[@]}" run --rm --no-deps -T sync recover-device "$2" ;;
  *) echo 'Usage: zapime {status|logs|start|stop|backup [file]|restore file|restore-objects file|verify-objects|check-storage|recover-device uuid}' >&2; exit 1 ;;
esac
