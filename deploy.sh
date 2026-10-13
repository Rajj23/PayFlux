#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/payflux}"
DEPLOY_REF="${DEPLOY_REF:-main}"
GIT_REPOSITORY="${GIT_REPOSITORY:-https://github.com/Rajj23/PayPal-Clone.git}"
DEPLOY_PROFILE="${DEPLOY_PROFILE:-server}"
APP_DOMAIN="${APP_DOMAIN:-}"

log() { printf '[%s] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die 'Run this script as root: sudo bash ./deploy.sh'
command -v docker >/dev/null || die 'Docker is not installed. Provision the server with terraform/server first.'
docker compose version >/dev/null || die 'Docker Compose V2 plugin is not installed.'

mkdir -p "$APP_DIR"
if [[ ! -d "$APP_DIR/.git" ]]; then
  [[ ! -e "$APP_DIR/docker-compose.yml" ]] || die "$APP_DIR exists and is not a Git checkout; back it up before continuing."
  log "Cloning $GIT_REPOSITORY into $APP_DIR"
  git clone --branch "$DEPLOY_REF" --depth 1 "$GIT_REPOSITORY" "$APP_DIR"
else
  log "Updating application checkout in $APP_DIR"
  git -C "$APP_DIR" fetch --depth 1 origin "$DEPLOY_REF"
  git -C "$APP_DIR" checkout -B "$DEPLOY_REF" FETCH_HEAD
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
    printf 'DB_URL=jdbc:postgresql://postgres:5432/payflux\nJWT_SECRET=%s\n' "$(random_secret)"
    printf 'REDIS_HOST=redis\nREDIS_PORT=6379\nKAFKA_BOOTSTRAP_SERVERS=kafka:9092\n'
    printf 'USER_SERVICE_URL=http://user-service:8081\n'
    printf 'TRANSACTION_SERVICE_URL=http://transaction-service:8082\n'
    printf 'WALLET_SERVICE_URL=http://wallet-service:8083/api/v1/wallets\n'
    printf 'WALLET_GATEWAY_URL=http://wallet-service:8083\n'
    printf 'NOTIFICATION_SERVICE_URL=http://notification-service:8084\n'
    printf 'REWARD_SERVICE_URL=http://reward-service:8089\n'
    printf 'FRONTEND_URL=http://localhost\nAPI_INTERNAL_URL=http://api-gateway:8080\n'
    printf 'DB_POOL_SIZE=5\nDB_POOL_MINIMUM_IDLE=1\n'
    printf 'JAVA_TOOL_OPTIONS=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m\n'
    printf 'KAFKA_HEAP_OPTS=-Xms128m -Xmx384m\n'
    printf 'POSTGRES_MEMORY_LIMIT=384m\nREDIS_MEMORY_LIMIT=128m\nKAFKA_MEMORY_LIMIT=512m\n'
    printf 'SERVICE_MEMORY_LIMIT=512m\nFRONTEND_MEMORY_LIMIT=384m\nPROXY_MEMORY_LIMIT=64m\n'
  } > "$SERVER_ENV"
  log "Created generated application secrets in $SERVER_ENV"
fi
chmod 600 "$SERVER_ENV"

if [[ "$DEPLOY_PROFILE" == "tls" ]]; then
  [[ -n "$APP_DOMAIN" ]] || die 'Set APP_DOMAIN to a domain pointing to this server before enabling HTTPS.'
  set_server_env APP_DOMAIN "$APP_DOMAIN"
  set_server_env FRONTEND_URL "https://$APP_DOMAIN"
  log "Deploying PayFlux at https://$APP_DOMAIN"
  docker compose --env-file "$SERVER_ENV" --profile tls -f docker-compose.yml -f docker-compose.server.yml -f docker-compose.tls.yml config --quiet
  docker compose --env-file "$SERVER_ENV" --profile tls --parallel 2 -f docker-compose.yml -f docker-compose.server.yml -f docker-compose.tls.yml up --detach --build --remove-orphans
  docker compose --env-file "$SERVER_ENV" --profile tls -f docker-compose.yml -f docker-compose.server.yml -f docker-compose.tls.yml ps
  DEPLOY_URL="https://$APP_DOMAIN"
elif [[ "$DEPLOY_PROFILE" == "server" ]]; then
  log 'Deploying the HTTP server preview. Set DEPLOY_PROFILE=tls and APP_DOMAIN for HTTPS.'
  PUBLIC_IP="$(curl --fail --silent --show-error --max-time 5 https://checkip.amazonaws.com || true)"
  [[ "$PUBLIC_IP" =~ ^[0-9.]+$ ]] || die 'Unable to detect the server public IPv4; retry or configure it manually.'
  set_server_env FRONTEND_URL "http://$PUBLIC_IP"
  docker compose --env-file "$SERVER_ENV" --profile server -f docker-compose.yml -f docker-compose.server.yml config --quiet
  docker compose --env-file "$SERVER_ENV" --profile server --parallel 2 -f docker-compose.yml -f docker-compose.server.yml up --detach --build --remove-orphans
  docker compose --env-file "$SERVER_ENV" --profile server -f docker-compose.yml -f docker-compose.server.yml ps
  DEPLOY_URL="http://$PUBLIC_IP"
else
  die "DEPLOY_PROFILE must be 'server' or 'tls', not '$DEPLOY_PROFILE'."
fi

log "Deployment finished: $DEPLOY_URL"
log "Logs: cd $APP_DIR && sudo docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml logs --follow --tail=100"