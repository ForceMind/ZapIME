#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Intended entry: curl -fsSL <repository>/deploy/install.sh | sudo bash
set +x
set -Eeuo pipefail
umask 077
ref=main
root=/opt/zapime
restore=
proxy=caddy
proxy_explicit=0
while (( $# )); do
  case "$1" in
    --ref) ref=${2:?Missing ref}; shift 2 ;;
    --restore) restore=${2:?Missing absolute backup path}; shift 2 ;;
    --existing-proxy) proxy=existing; proxy_explicit=1; shift ;;
    --help) echo 'ZapIME: [--ref main|40-hex-sha] [--restore /absolute/file.dump] [--existing-proxy]'; exit ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done
[[ $EUID == 0 && $(uname -s) == Linux ]] || { echo 'Run on your Linux server with sudo bash' >&2; exit 1; }
[[ $ref == main || $ref =~ ^[0-9a-f]{40}$ ]] || { echo 'Ref must be main or an exact commit SHA' >&2; exit 1; }
[[ -z $restore || ( $restore == /* && -f $restore && -f $restore.objects.tar ) ]] || { echo 'Restore requires both backup files at an absolute path' >&2; exit 1; }
# Never silently adopt an unrelated directory, symlink, or existing database.
[[ ! -L $root ]] || { echo 'Install directory cannot be a symlink' >&2; exit 1; }
if [[ -e $root && ! -f $root/installation-v1 ]]; then echo "$root already exists and is not managed by this installer" >&2; exit 1; fi
source /etc/os-release
case "$ID:${VERSION_CODENAME:-}" in
  ubuntu:jammy|ubuntu:noble|ubuntu:resolute|ubuntu:stonking|debian:bookworm|debian:trixie) ;;
  *) echo 'Supported: Ubuntu 22.04/24.04/26.04/26.10 or Debian 12/13' >&2; exit 1 ;;
esac
case $(uname -m) in x86_64|aarch64|arm64) ;; *) echo 'Use x86_64 or arm64' >&2; exit 1 ;; esac
# Re-running preserves the protected configuration and database; no down -v.
install -d -m 700 "$root" "$root/releases" "$root/backups"
exec 9>"$root/install.lock"
flock -n 9 || { echo 'Another ZapIME install/upgrade is running' >&2; exit 1; }
printf 'zapime-installer-v1\n' > "$root/installation-v1"
trap 'echo "Installation stopped. Existing data/configuration were kept. Correct the reported error and run the same command again." >&2' ERR
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl git openssl python3
if ! command -v docker >/dev/null; then
  # Follow Docker's signed apt repository installation; never remove other engines.
  if dpkg-query -W -f='${db:Status-Status}' containerd runc docker.io podman-docker 2>/dev/null | grep -q installed; then
    echo 'Existing container packages detected. Install a working Docker Engine + Compose v2 without removing your other services, then rerun.' >&2; exit 1
  fi
  install -d -m 755 /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/zapime-docker.asc
  chmod 644 /etc/apt/keyrings/zapime-docker.asc
  cat > /etc/apt/sources.list.d/zapime-docker.sources <<APT
Types: deb
URIs: https://download.docker.com/linux/$ID
Suites: $VERSION_CODENAME
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/zapime-docker.asc
APT
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
docker info >/dev/null
docker compose version >/dev/null || { echo 'Docker Compose v2 is required' >&2; exit 1; }
if [[ ! -f $root/config.env ]]; then
  exec 3<>/dev/tty || { echo 'First run needs a terminal to enter your R2 settings' >&2; exit 1; }
  printf '\nZapIME · private R2 storage\nCreate an R2 Standard PRIVATE bucket and bucket-scoped Object Read & Write credentials first.\nFree tier is metered, not an unlimited free plan.\n' >&3
  read -r -p 'HTTPS domain (DNS must point to this server): ' domain <&3
  read -r -p 'Cloudflare Account ID (32 hex): ' account <&3
  read -r -p 'R2 bucket name: ' bucket <&3
  read -r -p 'R2 jurisdiction [default/eu/us/fedramp] (default): ' jurisdiction <&3
  read -r -s -p 'R2 Access Key ID: ' access <&3; printf '\n' >&3
  read -r -s -p 'R2 Secret Access Key: ' secret <&3; printf '\n' >&3
  [[ $account =~ ^[0-9a-fA-F]{32}$ && $access =~ ^[0-9a-fA-F]{32}$ && $secret =~ ^[0-9a-fA-F]{64}$ && $bucket =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo 'Invalid R2 settings' >&2; exit 1; }
  [[ ${#domain} -le 253 && $domain == *.* && $domain =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ && $domain != *..* ]] || { echo 'Enter a domain only, without a URL or path' >&2; exit 1; }
  case ${jurisdiction:-default} in default) endpoint="https://$account.r2.cloudflarestorage.com" ;; eu|us|fedramp) endpoint="https://$account.$jurisdiction.r2.cloudflarestorage.com" ;; *) echo 'Invalid jurisdiction' >&2; exit 1 ;; esac
  password=$(openssl rand -hex 32)
  config=$(mktemp "$root/config.env.incoming.XXXXXX")
  printf 'POSTGRES_PASSWORD=%s\nZAPIME_HOST=%s\nZAPIME_OBJECT_BACKEND=s3\nZAPIME_S3_ENDPOINT=%s\nZAPIME_S3_BUCKET=%s\nZAPIME_S3_ACCESS_KEY_ID=%s\nZAPIME_S3_SECRET_ACCESS_KEY=%s\nZAPIME_S3_REGION=auto\nZAPIME_HTTPS_MODE=%s\n' "$password" "$domain" "$endpoint" "$bucket" "$access" "$secret" "$proxy" > "$config"
  ln "$config" "$root/config.env"; rm -f "$config"
  unset secret access password
fi
if [[ $proxy_explicit == 1 ]]; then
  sed -i '/^ZAPIME_HTTPS_MODE=/d' "$root/config.env"
  printf 'ZAPIME_HTTPS_MODE=existing\n' >> "$root/config.env"
else
  proxy=$(sed -n 's/^ZAPIME_HTTPS_MODE=//p' "$root/config.env")
  proxy=${proxy:-caddy}
fi
[[ $proxy == caddy || $proxy == existing ]] || { echo 'Invalid HTTPS mode in protected configuration' >&2; exit 1; }
# Clone only the server and deployment tools; never download input-method models.
staging=$(mktemp -d "$root/releases/incoming.XXXXXX")
git -C "$staging" init -q
git -C "$staging" remote add origin https://github.com/ForceMind/ZapIME.git
git -C "$staging" sparse-checkout init --cone
git -C "$staging" sparse-checkout set services/sync deploy
git -C "$staging" fetch -q --depth 1 origin "$ref"
git -C "$staging" checkout -q --detach FETCH_HEAD
sha=$(git -C "$staging" rev-parse HEAD)
release="$root/releases/$sha"
if [[ -d $release ]]; then rm -rf "$staging"; else mv "$staging" "$release"; fi
export COMPOSE_PROJECT_NAME=zapime ZAPIME_ENV_FILE="$root/config.env"
compose=(docker compose --env-file "$root/config.env" -f "$release/services/sync/compose.yml")
if [[ -e $root/current ]]; then
  [[ -z $restore ]] || { echo 'Restore is only accepted on a fresh installation' >&2; exit 1; }
  # Back up with the currently installed image BEFORE building its replacement.
  bash "$root/current/deploy/manage.sh" backup
fi
if [[ $proxy == caddy && ! -e $root/current && -z $("${compose[@]}" ps --status running -q https) ]]; then
  python3 - <<'PY'
import socket
for port in (80,443):
 with socket.socket() as s:
  try:s.bind(('0.0.0.0',port))
  except OSError:raise SystemExit(f'Port {port} is occupied. Use --existing-proxy and proxy HTTPS to 127.0.0.1:8787; no existing service was stopped.')
PY
fi
"${compose[@]}" build sync
"${compose[@]}" run --rm --no-deps -T sync check-storage
"${compose[@]}" up -d --wait db
if [[ -n $restore ]]; then bash "$release/services/sync/restore.sh" "$restore"; fi
if [[ $proxy == existing ]]; then "${compose[@]}" stop https; fi
"${compose[@]}" up -d --wait sync
if [[ ! -f $root/owner.json && -z $restore ]]; then
  owner=$(mktemp "$root/owner.json.incoming.XXXXXX")
  "${compose[@]}" exec -T sync zapime-sync provision 'Server owner' > "$owner"
  ln "$owner" "$root/owner.json"; rm -f "$owner"
fi
if [[ $proxy == caddy ]]; then
  "${compose[@]}" --profile https up -d --wait
  # Verify the domain and certificate; do not claim success from localhost alone.
  domain=$(sed -n 's/^ZAPIME_HOST=//p' "$root/config.env")
  ready=0
  for attempt in {1..30}; do
    if curl --connect-timeout 3 --max-time 5 --fail --silent "https://$domain/healthz" | python3 -c 'import json,sys;v=json.load(sys.stdin);assert v.get("status")=="ready" and v.get("protocol")==1' 2>/dev/null; then ready=1; break; fi
    sleep 2
  done
  [[ $ready == 1 ]] || { echo 'Local service is ready, but public HTTPS is not verified. Check DNS, ports 80/443 and Caddy logs, then rerun.' >&2; exit 1; }
fi
ln -s "$release" "$root/current.next"; mv -Tf "$root/current.next" "$root/current"
install -m 755 "$release/deploy/manage.sh" /usr/local/bin/zapime
# Daily recoverable backup, no automatic deletion of user archives.
if [[ -d /run/systemd/system ]]; then
  cat > /etc/systemd/system/zapime-backup.service <<'UNIT'
[Unit]
Description=ZapIME database and ciphertext object backup
After=docker.service
[Service]
Type=oneshot
UMask=0077
ExecStart=/usr/local/bin/zapime backup
UNIT
  cat > /etc/systemd/system/zapime-backup.timer <<'UNIT'
[Unit]
Description=Daily ZapIME backup
[Timer]
OnCalendar=daily
Persistent=true
[Install]
WantedBy=timers.target
UNIT
  systemctl daemon-reload
  systemctl enable --now zapime-backup.timer >/dev/null
fi
printf '\nZapIME installed: %s\nStatus: sudo zapime status\nStorage check: sudo zapime check-storage\nBackup: sudo zapime backup\n' "$sha"
if [[ -f $root/owner.json ]]; then echo 'Access credentials: /opt/zapime/owner.json (root only; not your E2EE recovery key)'; else echo 'Restored devices retain their original credentials. Use zapime recover-device only if necessary; E2EE keys are still client-owned.'; fi
if [[ $proxy == existing ]]; then echo 'HTTPS is delegated to your existing proxy. Point it to 127.0.0.1:8787; public HTTPS was not checked by this run.'; fi
