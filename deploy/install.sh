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
wizard=0
while (( $# )); do
  case "$1" in
    --ref) ref=${2:?Missing ref}; shift 2 ;;
    --restore) restore=${2:?Missing absolute backup path}; shift 2 ;;
    --verified-runtime) verified_runtime=${2:?Missing verified runtime directory}; shift 2 ;;
    --image-archive) image_archive=${2:?Missing image archive}; shift 2 ;;
    --existing-proxy) proxy=existing; proxy_explicit=1; shift ;;
    --wizard) wizard=1; shift ;;
    --oidc-config) oidc_config=${2:?Missing public Android OIDC JSON}; shift 2 ;;
    --oidc-jwks) oidc_jwks=${2:?Missing public JWKS}; shift 2 ;;
    --oidc-subject-file) oidc_subject=${2:?Missing issuer subject file}; shift 2 ;;
    --oidc-account) oidc_account=${2:?Missing existing account UUID}; shift 2 ;;
    --android-signer) android_signer=${2:?Missing Android certificate SHA-256}; shift 2 ;;
    --help)
      echo 'ZapIME: [--wizard] [--ref main|40-hex-sha] [--restore /absolute/file.dump] [--existing-proxy]'
      echo 'OIDC: --oidc-config JSON --oidc-jwks JSON --oidc-subject-file FILE [--oidc-account UUID] [--android-signer SHA256]'
      exit ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done
[[ $EUID == 0 && $(uname -s) == Linux ]] || { echo 'Run on your Linux server with sudo bash' >&2; exit 1; }
[[ $ref == main || $ref =~ ^[0-9a-f]{40}$ ]] || { echo 'Ref must be main or an exact commit SHA' >&2; exit 1; }
if (( wizard )); then
  [[ -n $verified_runtime || $ref != main ]] || { echo 'The setup command must name an exact release commit.' >&2; exit 1; }
  [[ -z $restore ]] || { echo 'Restore uses the separate recoverable backup entry.' >&2; exit 1; }
  [[ -z $verified_runtime || -f $verified_runtime/deploy/console_setup.py ]] || { echo 'This release predates the console setup wizard.' >&2; exit 1; }
  exec 3<>/dev/tty || { echo 'Please run this command in an interactive SSH terminal.' >&2; exit 1; }
  printf '\nZapIME 部署向导\n[1/5] 检查系统和 Docker Compose\n' >&3
fi
if [[ -n $verified_runtime ]]; then
  [[ $verified_runtime =~ ^/tmp/zapime-install\.[A-Za-z0-9]+$ && -d $verified_runtime && ! -L $verified_runtime &&
     $(stat -c '%u %a' "$verified_runtime") == '0 700' && -f $verified_runtime/manifest.json &&
     -f $verified_runtime/zapime-runtime.zip ]] || { echo 'Use the verified runtime bootstrap entry' >&2; exit 1; }
elif [[ -n $image_archive ]]; then
  echo 'An image archive requires the signed runtime entry.' >&2; exit 1
fi
if [[ -f $root/upgrade.pending && -z $verified_runtime ]]; then
  echo 'A signed upgrade is pending. Retry it with the same verified release.' >&2; exit 1
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
    fedora|rhel|centos|rocky|almalinux|ol)
      rpm_manager=dnf
      command -v dnf >/dev/null || rpm_manager=yum
      if "$rpm_manager" -q list --available docker-ce docker-compose-plugin >/dev/null 2>&1; then
        "$rpm_manager" install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
      elif [[ $ID == fedora ]]; then
        "$rpm_manager" install -y moby-engine docker-compose
      else
        echo 'Enable your Docker package repository (Engine and Compose v2), then rerun.' >&2; exit 1
      fi ;;
    opensuse*|sles) zypper --non-interactive install docker docker-compose ;;
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
    {
      read -r architecture
      while read -r reference expected; do
        metadata=$(docker image inspect --format '{{.Id}}' "$reference" 2>/dev/null) || metadata=
        [[ -z $metadata || $metadata == "$expected" ]] ||
          { echo 'Offline image tag belongs to a different image. Use distinct release tags.' >&2; return 1; }
      done
    } < "$verified_runtime/images.txt"
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
      [[ $metadata == "$candidate" ]] || {
        printf 'Container image verification failed for %s\nExpected %s\nObserved %s\n' "$reference" "$candidate" "${metadata:-unavailable}" >&2
        return 1
      }
    done
  } < "$verified_runtime/images.txt"
}
if [[ -n $verified_runtime ]]; then
  trust=$(sha256sum "$verified_runtime/release-public.pem")
  trust=${trust%% *}
  if [[ -f $root/release-trust.sha256 ]]; then
    [[ $(cat "$root/release-trust.sha256") == "$trust" ]] ||
      { echo 'Release signer differs from the existing trusted signer.' >&2; exit 1; }
  fi
  prepare_runtime_images
  if [[ ! -f $root/release-trust.sha256 ]]; then
    printf '%s\n' "$trust" > "$root/release-trust.sha256"
  fi
fi
check_caddy_ports() {
  python3 - <<'PY'
import socket
for port in (80, 443):
    with socket.socket() as listener:
        try:
            listener.bind(("0.0.0.0", port))
        except OSError:
            raise SystemExit(f"Port {port} is occupied. Use an existing HTTPS proxy; no service was stopped.")
PY
}
if [[ ! -f $root/config.env ]]; then
  exec 3<>/dev/tty || { echo 'First run needs a terminal to enter your R2 settings' >&2; exit 1; }
  if (( wizard )); then
    printf '\n[2/5] 设置访问域名和私有 R2\n使用 Tunnel 时，公开域名由 Cloudflare 处理，稍后向导会显示回源地址。\n' >&3
  fi
  printf '\nZapIME · private R2 storage\nCreate an R2 Standard PRIVATE bucket and bucket-scoped Object Read & Write credentials first.\nFree tier is metered, not an unlimited free plan.\n' >&3
  if (( wizard )); then
    read -r -p 'HTTPS domain (hostname only): ' domain <&3
  else
    read -r -p 'HTTPS domain (DNS must point to this server): ' domain <&3
  fi
  if (( wizard && !proxy_explicit )); then
    while :; do
      read -r -p 'HTTPS mode [caddy/tunnel] (tunnel): ' selected_proxy <&3 || exit 1
      case ${selected_proxy:-tunnel} in
        tunnel) proxy=existing; break ;;
        caddy) if check_caddy_ports; then proxy=caddy; break; fi ;;
        *) echo '请选择 caddy 或 tunnel。' >&2 ;;
      esac
    done
  elif [[ -n $verified_runtime && $proxy_explicit != 1 ]]; then
    read -r -p 'HTTPS mode [caddy/existing] (caddy): ' selected_proxy <&3
    proxy=${selected_proxy:-caddy}
    [[ $proxy == caddy || $proxy == existing ]] || { echo 'Select caddy or existing.' >&2; exit 1; }
  fi
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
if [[ $wizard == 0 && -n $verified_runtime && ! -e $root/current && ! -f $root/oidc/binding.json && -z $oidc_config ]] &&
  { exec 3<>/dev/tty; } 2>/dev/null; then
  echo 'Android sign-in requires a compatible OIDC provider.' >&3
  read -r -p 'Configure Android sign-in now? [y/N]: ' choose_oidc <&3
  if [[ $choose_oidc == y || $choose_oidc == Y ]]; then
    read -r -p 'Public Android OIDC configuration JSON path: ' oidc_config <&3
    read -r -p 'Verified public JWKS JSON path: ' oidc_jwks <&3
    read -r -p 'Verified OIDC subject file path: ' oidc_subject <&3
    read -r -p 'Android APK certificate SHA-256 (Enter if not signed yet): ' android_signer <&3
    if [[ -n $restore ]]; then read -r -p 'Existing ZapIME account UUID: ' oidc_account <&3; fi
    [[ -f $oidc_config && -f $oidc_jwks && -f $oidc_subject && ( -z $restore || -n $oidc_account ) ]] ||
      { echo 'OIDC inputs incomplete. Rerun with the same server configuration.' >&2; exit 1; }
  fi
fi
if [[ $proxy_explicit == 1 ]]; then
  if [[ -e $root/current && ( -n $verified_runtime || $wizard == 1 ) ]] &&
    [[ $(sed -n 's/^ZAPIME_HTTPS_MODE=//p' "$root/config.env") != existing ]]; then
    echo 'Change the HTTPS mode separately from a signed release update.' >&2; exit 1
  fi
  sed -i '/^ZAPIME_HTTPS_MODE=/d' "$root/config.env"
  printf 'ZAPIME_HTTPS_MODE=existing\n' >> "$root/config.env"
else
  proxy=$(sed -n 's/^ZAPIME_HTTPS_MODE=//p' "$root/config.env")
  proxy=${proxy:-caddy}
fi
[[ $proxy == caddy || $proxy == existing ]] || { echo 'Invalid HTTPS mode in protected configuration' >&2; exit 1; }
install -d -m 755 "$root/deployment-state"
if ! grep -Fxq "ZAPIME_DEPLOYMENT_STATE=$root/deployment-state" "$root/config.env"; then
  printf 'ZAPIME_DEPLOYMENT_STATE=%s/deployment-state\n' "$root" >> "$root/config.env"
fi
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
  current_release=$(readlink -f "$root/current")
  if (( wizard )) && [[ $current_release != "$release" ]]; then
    echo '检测到另一个已安装版本。请用现有升级入口升级，完成后再运行向导。' >&2
    exit 1
  fi
  if [[ -z $verified_runtime && $wizard == 0 ]]; then
    # The outer lock is held here; use the previous Compose file directly.
    COMPOSE_FILE="$root/current/services/sync/compose.yml" bash "$root/current/services/sync/backup.sh" \
      "$root/backups/legacy-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4).dump"
  elif [[ -n $oidc_config && $(readlink -f "$root/current") != "$release" ]]; then
    echo 'Apply OIDC configuration on the current release before changing its version.' >&2; exit 1
  fi
fi
if [[ -n $oidc_config ]]; then
  oidc_args=()
  [[ -z $android_signer ]] || oidc_args+=(--signer "$android_signer")
  [[ -z $oidc_account ]] || oidc_args+=(--account "$oidc_account")
  python3 "$release/deploy/oidc_setup.py" "$root" "$oidc_config" "$oidc_jwks" "$oidc_subject" "${oidc_args[@]}"
fi
if (( wizard )); then
  printf '\n[3/5] 配置身份提供方、管理员和后台托管密钥\n' >&3
  console_args=()
  [[ -z $android_signer ]] || console_args+=(--android-signer "$android_signer")
  python3 -I "$release/deploy/console_setup.py" "$root" "${console_args[@]}" <&3 >&3
  printf '\n[4/5] 安装并启动同步服务\n' >&3
fi
if [[ $proxy == caddy && ! -e $root/current && -z $("${compose[@]}" ps --status running -q https) ]]; then
  check_caddy_ports
fi
if [[ -z $verified_runtime ]]; then "${compose[@]}" build sync; fi
"${compose[@]}" run --rm --no-deps -T sync check-storage
echo 'Storage [PASS] S3 write, read and delete checked.'
if [[ -n $verified_runtime ]]; then
  source "$release/deploy/upgrade.sh"
  begin_upgrade
fi
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
local_sync_ready=0
for attempt in {1..20}; do
  if curl --noproxy 127.0.0.1 --connect-timeout 1 --max-time 2 --fail --silent \
      http://127.0.0.1:8787/healthz >/dev/null; then
    local_sync_ready=1
    break
  fi
  sleep 1
done
[[ $local_sync_ready == 1 ]] || { echo 'The sync container is running but its HTTP health check never became ready.' >&2; exit 1; }
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
  curl --connect-timeout 3 --max-time 5 --fail --silent --max-filesize 8192 -w '\n%{http_code}' "https://$domain/healthz" | \
    python3 -c 'import json,sys; body, status = sys.stdin.read(8200).rsplit("\n", 1); v=json.loads(body); sys.exit(not (status == "200" and v.get("status")=="ready" and v.get("protocol")==1))' 2>/dev/null
}
verify_public_app_link() {
  [[ -f $root/oidc/assetlinks.json && $https_verified == 1 ]] || return 1
  curl --connect-timeout 3 --max-time 5 --fail --silent --max-filesize 8192 -w '\n%{http_code}' \
    "https://$domain/.well-known/assetlinks.json" |
    python3 -c 'import json,sys; body, status = sys.stdin.read(8200).rsplit("\n",1); sys.exit(status != "200" or json.loads(body) != json.load(open(sys.argv[1])))' \
      "$root/oidc/assetlinks.json" 2>/dev/null
}
if [[ $proxy == caddy ]]; then
  "${compose[@]}" --profile https up -d --wait
  # Verify the domain and certificate; do not claim success from localhost alone.
  for attempt in {1..30}; do
    if verify_public_https; then https_verified=1; break; fi
    sleep 2
  done
  [[ $https_verified == 1 || $wizard == 1 ]] || { echo 'Local service is ready, but public HTTPS did not reach this instance. Check DNS, ports and Caddy logs, then rerun.' >&2; exit 1; }
else
  for attempt in {1..3}; do
    if verify_public_https; then https_verified=1; break; fi
    [[ $attempt == 3 ]] || sleep 1
  done
fi
if [[ -n $verified_runtime ]]; then
  if [[ ${upgrading:-0} == 1 && -f $root/public-https.verified && $https_verified != 1 ]]; then
    echo 'The previously verified public route is unavailable. The new release remains frozen.' >&2; exit 1
  fi
  if [[ ${upgrading:-0} == 1 && -f $root/public-app-link.verified ]] && ! verify_public_app_link; then
    echo 'The previously verified Android App Link is unavailable. The new release remains frozen.' >&2; exit 1
  fi
  verify_upgrade
fi
if [[ $https_verified == 1 ]]; then : > "$root/public-https.verified"; fi
app_link_verified=0
if verify_public_app_link; then
  : > "$root/public-app-link.verified"
  app_link_verified=1
fi
ln -sfn "$release" "$root/current.next"; mv -Tf "$root/current.next" "$root/current"
if [[ -n $verified_runtime ]]; then finish_upgrade; fi
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
  if [[ $app_link_verified == 1 ]]; then
    echo 'App Link [PASS] Public association matches the selected APK certificate.'
  else
    echo 'App Link [PENDING] Publish the association for the candidate APK signer and verify public HTTPS.'
  fi
else echo 'OIDC [PENDING] Provide the verified identity configuration for Android sign-in.'; fi

configure_tunnel_connector() {
  local name=zapime-tunnel folder="$root/tunnel" token_file staged token owner
  local replace=${1:-0} managed=0
  local image='cloudflare/cloudflared@sha256:55c7e3e8d89d5e8d20b75a82f3602cbf98e06db406e73e282073cfdc5a9a3af6'
  token_file="$folder/token"
  if docker container inspect "$name" >/dev/null 2>&1; then
    owner=$(docker container inspect --format '{{index .Config.Labels "io.zapime.installation"}}' "$name") || return 1
    if [[ $owner != "$installation_id" ]]; then
      echo '检测到自管 Tunnel 容器。请在 Cloudflare 中复用该连接器。' >&2
      return 0
    fi
    managed=1
  fi
  [[ ! -L $folder && ! -L $token_file ]] || { echo 'Tunnel 凭据路径不可使用符号链接。' >&2; return 1; }
  if [[ ! -f $token_file || $replace == 1 ]]; then
    printf '如果这台服务器尚未运行 Tunnel 连接器，可在 Cloudflare 创建 Tunnel 后粘贴连接器 token。已有连接器请直接回车。\n' >&3
    read -r -s -p 'Cloudflare Tunnel token (Enter to reuse an existing connector): ' token <&3 || return 1
    printf '\n' >&3
    [[ -n $token ]] || return 0
    [[ ${#token} -ge 32 && ${#token} -le 4096 && $token =~ ^[A-Za-z0-9_+/=-]+$ ]] ||
      { echo 'Tunnel token 格式不符，请输入 Cloudflare 的连接器 token。' >&2; return 1; }
    install -d -o root -g 65532 -m 0750 "$folder" || return 1
    staged=$(mktemp "$folder/token.incoming.XXXXXX") || return 1
    if ! printf '%s\n' "$token" > "$staged" || ! chown 0:65532 "$staged" || ! chmod 0640 "$staged"; then
      rm -f -- "$staged"
      return 1
    fi
    unset token
    if [[ $replace == 1 ]]; then
      if ! mv -Tf -- "$staged" "$token_file"; then rm -f -- "$staged"; return 1; fi
    else
      if ! ln -- "$staged" "$token_file"; then rm -f -- "$staged"; return 1; fi
      rm -f -- "$staged"
    fi
  fi
  if [[ $managed == 1 ]]; then
    if [[ $replace == 1 ]]; then
      docker restart "$name" >/dev/null
    else
      docker start "$name" >/dev/null
    fi
    return
  fi
  docker run -d --name "$name" --label "io.zapime.installation=$installation_id" \
    --restart unless-stopped --network host --user 65532:65532 --read-only --cap-drop ALL \
    --security-opt no-new-privileges --log-driver local \
    -v "$token_file:/run/zapime-tunnel-token:ro,Z" \
    "$image" tunnel --no-autoupdate run --token-file /run/zapime-tunnel-token >/dev/null
}

if (( wizard )); then
  printf '\n[5/5] 验证公开 HTTPS 和首位管理员登录\n' >&3
  if [[ $https_verified == 0 && $proxy == existing ]]; then
    printf '请在 Cloudflare 将公开主机 %s 指向 http://127.0.0.1:8787。\n' "$domain" >&3
    configure_tunnel_connector || echo 'Tunnel 连接器还未就绪，可重新输入 token 或检查现有连接器。' >&2
  fi
  until [[ $https_verified == 1 ]]; do
    if [[ $proxy == existing ]]; then
      printf '请将宿主机 Cloudflare Tunnel 的公开主机 %s 指向 http://127.0.0.1:8787。\n' "$domain" >&3
      echo '输入 r 可重新输入由本安装管理的连接器 token。' >&3
    else
      printf '请检查域名 %s 的 DNS 和入站 80/443。\n' "$domain" >&3
    fi
    read -r -p 'Press Enter to check HTTPS (q to exit): ' again <&3 || again=q
    [[ $again != q ]] || { echo 'HTTPS [PENDING] 可再次执行同一命令继续检查。' >&2; exit 2; }
    if [[ $again == r && $proxy == existing ]]; then
      configure_tunnel_connector 1 || echo 'Tunnel 连接器还未就绪，可重新输入 token 或检查现有连接器。' >&2
    fi
    if verify_public_https; then
      https_verified=1
      : > "$root/public-https.verified"
      printf 'HTTPS [PASS] https://%s\n' "$domain" >&3
    fi
  done
  admin_url="https://$domain/console/admin/"
  user_url="https://$domain/console/"
  if [[ $(curl -sS --connect-timeout 3 --max-time 8 -o /dev/null -w '%{http_code}' "$admin_url") != 200 ||
        $(curl -sS --connect-timeout 3 --max-time 8 -o /dev/null -w '%{http_code}' "https://$domain/console/auth/login?return_to=admin") != 302 ]]; then
    echo 'Console [PENDING] 后台或 OIDC 跳转未就绪。请检查公开路由后重试。' >&2
    exit 2
  fi
  printf 'Console [READY]\n用户中心 %s\n管理后台 %s\n' "$user_url" "$admin_url" >&3
  admin_account=$(python3 -I -c 'import json,sys,uuid; print(uuid.UUID(json.load(open(sys.argv[1]))["account"]))' "$root/oidc/binding.json")
  admin_session_ready() {
    local result
    result=$("${compose[@]}" exec -T db psql -X -U zapime -d zapime -Atq -v ON_ERROR_STOP=1 -c "
      SELECT EXISTS(
        SELECT 1 FROM zapime_console_sessions s
        JOIN zapime_devices d ON d.id=s.device_id AND d.account_id=s.account_id AND d.token_hash=s.device_token_hash
        JOIN zapime_accounts a ON a.id=s.account_id
        JOIN zapime_oidc_subjects b ON b.account_id=s.account_id AND b.issuer=s.issuer AND b.subject=s.subject
        WHERE s.account_id='$admin_account' AND s.expires_at>clock_timestamp()
          AND a.disabled_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>clock_timestamp()
          AND EXISTS(
            SELECT 1 FROM zapime_console_audit l
            WHERE l.actor_account_id=s.account_id AND l.target_account_id=s.account_id
              AND l.actor_issuer=s.issuer AND l.actor_subject=s.subject AND l.action='accounts.list'
              AND l.created_at>=s.expires_at-interval '30 minutes'
          )
      )" ) || return 1
    [[ $result == t ]]
  }
  until admin_session_ready; do
    printf '请打开管理后台，用已绑定的首位管理员账号完成一次登录，然后回到此窗口。\n' >&3
    read -r -p 'Press Enter after administrator login (q to exit): ' again <&3 || again=q
    [[ $again != q ]] || { echo 'Console login [PENDING] 可再次执行同一命令继续检查。' >&2; exit 2; }
  done
  printf 'Console login [PASS]\n运维入口 sudo zapime status / doctor / backup\n' >&3
fi
