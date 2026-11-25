#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
root=${ZAPIME_INSTALL_DIR:-/opt/zapime}
[[ -f $root/config.env && -d $root/current/services/sync ]] || { echo 'ZapIME installation not found' >&2; exit 1; }
export COMPOSE_PROJECT_NAME=zapime ZAPIME_ENV_FILE="$root/config.env"
cd "$root/current/services/sync"
export COMPOSE_FILE="$PWD/compose.yml"
compose=(docker compose --env-file "$ZAPIME_ENV_FILE" -f "$COMPOSE_FILE")
action=${1:-status}
case "$action" in
  status|logs|doctor|update) ;;
  *)
    exec 9>"$root/install.lock"
    flock -n 9 || { echo 'Another ZapIME operation holds the install lock.' >&2; exit 1; }
    [[ ! -f $root/upgrade.pending ]] ||
      { echo 'An upgrade is pending. Run zapime status and retry the same verified release.' >&2; exit 1; } ;;
esac

local_health() {
  curl --connect-timeout 3 --max-time 5 --fail --silent --show-error http://127.0.0.1:8787/healthz
  echo
}

show_status() {
  printf 'Active release: %s\n' "$(basename "$(readlink -f "$root/current")")"
  "${compose[@]}" ps
  local container
  container=$("${compose[@]}" ps --status running -q sync)
  if [[ -n $container ]]; then
    docker inspect --format 'Running sync image: {{.Image}}' "$container"
    "${compose[@]}" exec -T db psql -U zapime -d zapime -Atc "SELECT 'Database schema: ' || version FROM zapime_schema WHERE id=1"
  fi
  if [[ -f $root/upgrade.pending ]]; then
    printf 'Upgrade pending. Target: %s\nBackup: %s\nPhase: %s\n' \
      "$(basename "$(sed -n '2p' "$root/upgrade.pending")")" \
      "$(sed -n '3p' "$root/upgrade.pending")" "$(sed -n '4p' "$root/upgrade.pending")"
  fi
  [[ ! -f $root/deployment-state/frozen ]] || echo 'Sync writes are frozen.'
  local_health
}

doctor() {
  local host instance code result=0
  local_health || result=1
  host=$(sed -n 's/^ZAPIME_HOST=//p' "$ZAPIME_ENV_FILE")
  instance=$(sed -n 's/^ZAPIME_INSTALLATION_ID=//p' "$ZAPIME_ENV_FILE")
  code=$(curl --connect-timeout 3 --max-time 5 --fail --silent -o /dev/null -w '%{http_code}' \
      "https://$host/.well-known/zapime-deploy/$instance") || code=0
  if [[ $code == 204 ]]; then echo 'Public HTTPS [PASS]'
  else echo 'Public HTTPS [PENDING] Check DNS and reverse proxy routing.'; result=1; fi
  if [[ -f $root/oidc/android.json ]]; then
    echo 'OIDC [CONFIGURED] Browser login requires the signed Android candidate.'
    if [[ -f $root/oidc/assetlinks.json && $code == 204 ]] &&
      curl --connect-timeout 3 --max-time 5 --fail --silent --max-filesize 8192 -w '\n%{http_code}' "https://$host/.well-known/assetlinks.json" |
        python3 -c 'import json,sys; body,status=sys.stdin.read(8200).rsplit("\n",1); sys.exit(status != "200" or json.loads(body) != json.load(open(sys.argv[1])))' "$root/oidc/assetlinks.json" 2>/dev/null; then
      echo 'Android App Link [PASS]'
    else echo 'Android App Link [PENDING]'; result=1; fi
  else echo 'OIDC [PENDING]'; result=1; fi
  return "$result"
}

case "$action" in
  status) show_status ;;
  doctor) doctor ;;
  update)
    [[ $# == 3 || $# == 4 ]] || { echo 'Usage: zapime update <HTTPS_RELEASE_DIR|/ABSOLUTE_BUNDLE_DIR> VERSION [IMAGE_ARCHIVE]' >&2; exit 1; }
    [[ -f $root/release-trust.sha256 && -f $root/current/deploy/bootstrap.sh ]] ||
      { echo 'Only an installation with a trusted release signer can be updated this way.' >&2; exit 1; }
    kind=--source
    if [[ $2 == /* ]]; then kind=--bundle; fi
    args=()
    [[ $# != 4 ]] || args+=(--image-archive "$4")
    exec bash "$root/current/deploy/bootstrap.sh" --install "$kind" "$2" --version "$3" \
      --key-sha256 "$(cat "$root/release-trust.sha256")" -- "${args[@]}" ;;
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
  *) echo 'Usage: zapime {status|doctor|logs|update SOURCE VERSION [IMAGE_ARCHIVE]|start|stop|backup [file]|restore file|restore-objects file|verify-objects|check-storage|recover-device uuid}' >&2; exit 1 ;;
esac
