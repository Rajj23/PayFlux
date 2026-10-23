#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/payflux}"
GIT_REPOSITORY="${GIT_REPOSITORY:-https://github.com/Rajj23/PayFlux.git}"

log() { printf '[%s] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die 'Run this script as root: sudo bash ./deploy.sh'
command -v docker >/dev/null || die 'Docker is not installed. Provision the server with terraform/server first.'
docker compose version >/dev/null || die 'Docker Compose V2 plugin is not installed.'

mkdir -p "$APP_DIR"
if [[ ! -d "$APP_DIR/.git" ]]; then
  [[ ! -e "$APP_DIR/docker-compose.yml" ]] || die "$APP_DIR exists and is not a Git checkout; back it up before continuing."
  log "Cloning $GIT_REPOSITORY into $APP_DIR"
  git clone --depth 1 "$GIT_REPOSITORY" "$APP_DIR"
else
  log "Using existing checkout in $APP_DIR"
fi

cd "$APP_DIR"
umask 077
SERVER_ENV="$APP_DIR/deploy/server.env"
mkdir -p "$APP_DIR/deploy"

random_secret() { openssl rand -hex 32; }

set_server_env() {
  python3 - "$SERVER_ENV" "$1" "$2" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
key, value = sys.argv[2:]
lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
updated = []
found = False
for line in lines:
    if line.partition("=")[0] == key:
        if not found:
            updated.append(f"{key}={value}")
            found = True
    else:
        updated.append(line)
if not found:
    updated.append(f"{key}={value}")
temporary = path.with_suffix(".tmp")
temporary.write_text("\n".join(updated) + "\n", encoding="utf-8")
temporary.chmod(0o600)
temporary.replace(path)
PY
}

if [[ ! -f "$SERVER_ENV" ]]; then
  {
    printf 'POSTGRES_DB=payflux\nDB_USERNAME=payflux\nDB_PASSWORD=%s\n' "$(random_secret)"
    printf 'KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092\nDATA_BIND_ADDRESS=0.0.0.0\n'
    printf 'KAFKA_HEAP_OPTS=-Xms128m -Xmx384m\n'
  } > "$SERVER_ENV"
  log "Created generated data-service credentials in $SERVER_ENV"
fi
chmod 600 "$SERVER_ENV"

PRIVATE_IP="$(hostname -I | awk '{print $1}')"
[[ "$PRIVATE_IP" =~ ^[0-9.]+$ ]] || die 'Unable to detect the EC2 private IPv4.'
set_server_env KAFKA_ADVERTISED_LISTENERS "PLAINTEXT://$PRIVATE_IP:9092"
set_server_env DATA_BIND_ADDRESS "0.0.0.0"
log "Deploying the EC2 data host at $PRIVATE_IP. Application workloads run in EKS."
docker compose --env-file "$SERVER_ENV" -f docker-compose.data.yml config --quiet
docker compose --env-file "$SERVER_ENV" --parallel 2 -f docker-compose.data.yml up --detach
docker compose --env-file "$SERVER_ENV" -f docker-compose.data.yml ps

log "Deployment finished: data services on $PRIVATE_IP"
log "Logs: cd $APP_DIR && sudo docker compose --env-file deploy/server.env -f docker-compose.data.yml logs --follow"