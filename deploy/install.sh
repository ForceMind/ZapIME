#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Intended entry: curl -fsSL <repository>/deploy/install.sh | sudo bash
set +x
set -Eeuo pipefail
umask 077
ref=main
root=/opt/zapime
restore=
verified_runtime= image_archive=
oidc_config= oidc_jwks= oidc_subject= oidc_account= android_signer=
proxy=caddy
proxy_explicit=0
while (( $# )); do
  case "$1" in
    --ref) ref=${2:?Missing ref}; shift 2 ;;
    --restore) restore=${2:?Missing absolute backup path}; shift 2 ;;
    --verified-runtime) verified_runtime=${2:?Missing verified runtime directory}; shift 2 ;;
    --image-archive) image_archive=${2:?Missing image archive}; shift 2 ;;
    --existing-proxy) proxy=existing; proxy_explicit=1; shift ;;
    --oidc-config) oidc_config=${2:?Missing public Android OIDC JSON}; shift 2 ;;
    --oidc-jwks) oidc_jwks=${2:?Missing public JWKS}; shift 2 ;;
    --oidc-subject-file) oidc_subject=${2:?Missing issuer subject file}; shift 2 ;;
    --oidc-account) oidc_account=${2:?Missing existing account UUID}; shift 2 ;;
    --android-signer) android_signer=${2:?Missing Android certificate SHA-256}; shift 2 ;;
    --help)
      echo 'ZapIME: [--ref main|40-hex-sha] [--restore /absolute/file.dump] [--existing-proxy]'
      echo 'OIDC: --oidc-config JSON --oidc-jwks JSON --oidc-subject-file FILE [--oidc-account UUID] [--android-signer SHA256]'
      exit ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done
[[ $EUID == 0 && $(uname -s) == Linux ]] || { echo 'Run on your Linux server with sudo bash' >&2; exit 1; }
[[ $ref == main || $ref =~ ^[0-9a-f]{40}$ ]] || { echo 'Ref must be main or an exact commit SHA' >&2; exit 1; }
if [[ -n $verified_runtime ]]; then
  [[ $verified_runtime =~ ^/tmp/zapime-install\.[A-Za-z0-9]+$ && -d $verified_runtime && ! -L $verified_runtime &&
     $(stat -c '%u %a' "$verified_runtime") == '0 700' && -f $verified_runtime/manifest.json &&
     -f $verified_runtime/zapime-runtime.zip ]] || { echo 'Use the verified runtime bootstrap entry' >&2; exit 1; }
elif [[ -n $image_archive ]]; then
  echo 'An image archive requires the signed runtime entry.' >&2; exit 1
fi
[[ -z $restore || ( $restore == /* && -f $restore && -f $restore.objects.tar ) ]] || { echo 'Restore requires both backup files at an absolute path' >&2; exit 1; }
if [[ -n $oidc_config || -n $oidc_jwks || -n $oidc_subject || -n $oidc_account || -n $android_signer ]]; then
  [[ -f $oidc_config && -f $oidc_jwks && -f $oidc_subject ]] || { echo 'OIDC requires configuration, public JWKS, and verified issuer subject files' >&2; exit 1; }
  if [[ ( -e $root/current || -n $restore ) && ! -f $root/oidc/binding.json && -z $oidc_account ]]; then
    echo 'Select the existing ZapIME account UUID when enabling OIDC for an existing database.' >&2; exit 1
  fi
fi
# Never silently adopt an unrelated directory, symlink, or existing database.
[[ ! -L $root ]] || { echo 'Install directory cannot be a symlink' >&2; exit 1; }
if [[ -e $root && ! -f $root/installation-v1 ]]; then echo "$root already exists and is not managed by this installer" >&2; exit 1; fi
ID= VERSION_CODENAME=
if [[ -r /etc/os-release ]]; then source /etc/os-release; fi
case $(uname -m) in x86_64|aarch64|arm64) ;; *) echo 'Use x86_64 or arm64' >&2; exit 1 ;; esac
install_dependencies() {
  local missing=() tools=(curl openssl python3 flock) tool
  [[ -n $verified_runtime ]] || tools+=(git)
  for tool in "${tools[@]}"; do
    command -v "$tool" >/dev/null || missing+=("$tool")
  done
  if (( ${#missing[@]} )); then
    if command -v apt-get >/dev/null; then
      apt-get update -qq
      apt-get install -y -qq ca-certificates curl git openssl python3 util-linux
    elif command -v dnf >/dev/null; then
      dnf install -y ca-certificates curl git openssl python3 util-linux
    elif command -v yum >/dev/null; then
      yum install -y ca-certificates curl git openssl python3 util-linux
    elif command -v apk >/dev/null; then
      apk add ca-certificates curl git openssl python3 util-linux
    elif command -v pacman >/dev/null; then
      pacman -S --needed --noconfirm ca-certificates curl git openssl python util-linux
    elif command -v zypper >/dev/null; then
      zypper --non-interactive install ca-certificates curl git openssl python3 util-linux
    else
      printf 'Install missing dependencies: %s\n' "${missing[*]}" >&2
      return 1
    fi
  fi
  for tool in "${tools[@]}"; do
    command -v "$tool" >/dev/null || { echo "Missing dependency: $tool" >&2; return 1; }
  done
}
export DEBIAN_FRONTEND=noninteractive
install_dependencies
# Re-running preserves the protected configuration and database; no down -v.
install -d -m 700 "$root" "$root/releases" "$root/backups"
exec 9>"$root/install.lock"
flock -n 9 || { echo 'Another ZapIME install/upgrade is running' >&2; exit 1; }
printf 'zapime-installer-v1\n' > "$root/installation-v1"
trap 'echo "Installation stopped. Existing data/configuration were kept. Correct the reported error and run the same command again." >&2' ERR
if ! command -v docker >/dev/null; then
  case "$ID" in
    ubuntu|debian)
      [[ $VERSION_CODENAME =~ ^[a-z0-9]+$ ]] || { echo 'Install Docker Engine and Compose v2 first' >&2; exit 1; }
      if dpkg-query -W -f='${db:Status-Status}' containerd runc docker.io podman-docker 2>/dev/null | grep -q installed; then
        echo 'Existing container packages detected. Install Docker Engine and Compose v2 using your normal package source.' >&2; exit 1
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
      apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin ;;
    alpine) apk add docker docker-cli-compose ;;
    arch|manjaro) pacman -S --needed --noconfirm docker docker-compose ;;
    *)
      echo 'Install Docker Engine and Compose v2 from your configured distribution repositories, then rerun.' >&2
      exit 1 ;;
  esac
  if command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
    systemctl enable --now docker
  elif command -v rc-service >/dev/null && [[ -d /run/openrc ]]; then
    rc-update add docker default
    rc-service docker start
  fi
fi
docker info >/dev/null
docker compose version >/dev/null || { echo 'Docker Compose v2 is required' >&2; exit 1; }
prepare_runtime_images() {
  local metadata architecture expected reference candidate
  python3 -I - "$verified_runtime" "$image_archive" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import stat
import sys

work = Path(sys.argv[1])
manifest = json.loads((work / "manifest.json").read_bytes())
archive_hash = manifest["archive_sha256"]
archive_input = sys.argv[2]
if archive_hash and not archive_input:
    raise SystemExit("Provide the signed release's offline image archive")
if archive_input:
    if not archive_hash:
        raise SystemExit("The signed release does not describe an offline archive")
    fd = os.open(archive_input, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise SystemExit("Use a regular image archive")
        digest = hashlib.sha256()
        with (work / "images.tar").open("xb") as target:
            for data in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(data)
                target.write(data)
    if digest.hexdigest() != archive_hash:
        raise SystemExit("Image archive digest mismatch")
lines = [manifest["architecture"]]
for image in manifest["images"].values():
    lines.append(image["reference"] + " " + image["id"])
(work / "images.txt").write_text("\n".join(lines) + "\n")
PY
  if [[ -n $image_archive ]]; then
    docker image load --input "$verified_runtime/images.tar" >/dev/null
    rm -f -- "$verified_runtime/images.tar"
  fi
  {
    read -r architecture
    while read -r reference expected; do
      metadata=$(docker image inspect --format '{{.Id}} {{.Architecture}} {{.Os}}' "$reference" 2>/dev/null) || metadata=
      candidate="$expected $architecture linux"
      if [[ $metadata != "$candidate" && -z $image_archive && $reference == *@sha256:* ]]; then
        docker pull --platform "linux/$architecture" "$reference" >/dev/null
        metadata=$(docker image inspect --format '{{.Id}} {{.Architecture}} {{.Os}}' "$reference")
      fi
      [[ $metadata == "$candidate" ]] || { echo 'Container image ID or architecture mismatch' >&2; return 1; }
    done
  } < "$verified_runtime/images.txt"
}
if [[ -n $verified_runtime ]]; then
  prepare_runtime_images
fi
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
  installation_id=$(openssl rand -hex 16)
  password=$(openssl rand -hex 32)
  config=$(mktemp "$root/config.env.incoming.XXXXXX")
  printf 'ZAPIME_INSTALLATION_ID=%s\nPOSTGRES_PASSWORD=%s\nZAPIME_HOST=%s\nZAPIME_OBJECT_BACKEND=s3\nZAPIME_S3_ENDPOINT=%s\nZAPIME_S3_BUCKET=%s\nZAPIME_S3_ACCESS_KEY_ID=%s\nZAPIME_S3_SECRET_ACCESS_KEY=%s\nZAPIME_S3_REGION=auto\nZAPIME_HTTPS_MODE=%s\n' "$installation_id" "$password" "$domain" "$endpoint" "$bucket" "$access" "$secret" "$proxy" > "$config"
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
# Refuse a pre-existing Compose project/volume belonging to another installation.
installation_id=$(sed -n 's/^ZAPIME_INSTALLATION_ID=//p' "$root/config.env")
[[ $installation_id =~ ^[0-9a-f]{32}$ ]] || { echo 'Invalid installation ID in protected configuration' >&2; exit 1; }
for container in $(docker ps -aq --filter label=com.docker.compose.project=zapime); do
  owner=$(docker inspect --format '{{index .Config.Labels "io.zapime.installation"}}' "$container")
  [[ $owner == "$installation_id" ]] || { echo 'Existing zapime Compose project belongs to another installation; no data was adopted' >&2; exit 1; }
done
for volume in zapime_zapime-db zapime_zapime-caddy; do
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    owner=$(docker volume inspect --format '{{index .Labels "io.zapime.installation"}}' "$volume")
    [[ $owner == "$installation_id" ]] || { echo 'Existing ZapIME volume belongs to another installation; no data was adopted' >&2; exit 1; }
  fi
done
# Clone only the server and deployment tools; never download input-method models.
staging=$(mktemp -d "$root/releases/incoming.XXXXXX")
if [[ -n $verified_runtime ]]; then
  cp -R -- "$verified_runtime"/{deploy,services,scripts,manifest.json} "$staging/"
  sha=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["source"])' "$staging/manifest.json")
  bundle_id=$(sha256sum "$verified_runtime/zapime-runtime.zip")
  release="$root/releases/$sha-${bundle_id%% *}"
else
  git -C "$staging" init -q
  git -C "$staging" remote add origin https://github.com/ForceMind/ZapIME.git
  git -C "$staging" sparse-checkout init --cone
  git -C "$staging" sparse-checkout set services/sync deploy scripts
  git -C "$staging" fetch -q --depth 1 origin "$ref"
  git -C "$staging" checkout -q --detach FETCH_HEAD
  sha=$(git -C "$staging" rev-parse HEAD)
  release="$root/releases/$sha"
fi
if [[ -d $release ]]; then rm -rf "$staging"; else mv "$staging" "$release"; fi
export COMPOSE_PROJECT_NAME=zapime ZAPIME_ENV_FILE="$root/config.env"
compose=(docker compose --env-file "$root/config.env" -f "$release/services/sync/compose.yml")
if [[ -e $root/current ]]; then
  [[ -z $restore ]] || { echo 'Restore is only accepted on a fresh installation' >&2; exit 1; }
  # Back up with the currently installed image BEFORE building its replacement.
  bash "$root/current/deploy/manage.sh" backup
fi
if [[ -n $oidc_config ]]; then
  oidc_args=()
  [[ -z $android_signer ]] || oidc_args+=(--signer "$android_signer")
  [[ -z $oidc_account ]] || oidc_args+=(--account "$oidc_account")
  python3 "$release/deploy/oidc_setup.py" "$root" "$oidc_config" "$oidc_jwks" "$oidc_subject" "${oidc_args[@]}"
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
if [[ -z $verified_runtime ]]; then "${compose[@]}" build sync; fi
"${compose[@]}" run --rm --no-deps -T sync check-storage
"${compose[@]}" up -d --wait db
if [[ -n $restore ]]; then bash "$release/services/sync/restore.sh" "$restore"; fi
if [[ -f $root/oidc/binding.json ]]; then
  case $(cat "$root/oidc/binding-mode") in
    bootstrap) bind=bootstrap-oidc-account ;;
    bind) bind=bind-oidc-account ;;
    *) echo 'OIDC binding mode invalid' >&2; exit 1 ;;
  esac
  "${compose[@]}" run --rm --no-deps -T sync "$bind" < "$root/oidc/binding.json"
fi
if [[ $proxy == existing ]]; then "${compose[@]}" stop https; fi
"${compose[@]}" up -d --wait sync
if [[ ! -f $root/owner.json && ! -f $root/oidc/binding.json && -z $restore ]]; then
  owner=$(mktemp "$root/owner.json.incoming.XXXXXX")
  "${compose[@]}" exec -T sync zapime-sync provision 'Server owner' > "$owner"
  ln "$owner" "$root/owner.json"; rm -f "$owner"
fi
https_verified=0
domain=$(sed -n 's/^ZAPIME_HOST=//p' "$root/config.env")
verify_public_https() {
  local code
  code=$(curl --connect-timeout 3 --max-time 5 --fail --silent -o /dev/null -w '%{http_code}' \
    "https://$domain/.well-known/zapime-deploy/$installation_id") || return 1
  [[ $code == 204 ]] || return 1
  curl --connect-timeout 3 --max-time 5 --fail --silent "https://$domain/healthz" | \
    python3 -c 'import json,sys; v=json.load(sys.stdin); sys.exit(not (v.get("status")=="ready" and v.get("protocol")==1))' 2>/dev/null
}
if [[ $proxy == caddy ]]; then
  "${compose[@]}" --profile https up -d --wait
  # Verify the domain and certificate; do not claim success from localhost alone.
  for attempt in {1..30}; do
    if verify_public_https; then https_verified=1; break; fi
    sleep 2
  done
  [[ $https_verified == 1 ]] || { echo 'Local service is ready, but public HTTPS did not reach this instance. Check DNS, ports and Caddy logs, then rerun.' >&2; exit 1; }
elif verify_public_https; then
  https_verified=1
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
if [[ -f $root/owner.json ]]; then echo 'Access credentials: /opt/zapime/owner.json (root only; E2EE keys are client-owned)'; fi
if [[ $https_verified == 1 ]]; then printf 'HTTPS [PASS] https://%s\n' "$domain"
else echo 'HTTPS [PENDING] Configure existing reverse proxy upstream 127.0.0.1:8787.'; fi
if [[ -f $root/oidc/binding.json ]]; then
  echo 'OIDC [CONFIGURED] Account binding persisted. Validate actual browser login with the candidate APK.'
  echo 'Android deployment JSON: /opt/zapime/oidc/android.json'
  if [[ -f $root/oidc/assetlinks.json && $https_verified == 1 ]] &&
    curl --connect-timeout 3 --max-time 5 --fail --silent "https://$domain/.well-known/assetlinks.json" |
      python3 -c 'import json,sys; sys.exit(json.load(sys.stdin) != json.load(open(sys.argv[1])))' "$root/oidc/assetlinks.json" 2>/dev/null; then
    echo 'App Link [PASS] Public association matches the selected APK certificate.'
  else
    echo 'App Link [PENDING] Publish the association for the candidate APK signer and verify public HTTPS.'
  fi
else echo 'OIDC [PENDING] Provide the verified identity configuration for Android sign-in.'; fi
