#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=docker-helpers.sh
. "$(dirname "$0")/docker-helpers.sh"

E2E_NETWORK="squash-e2e-net"
POSTGRES_CONTAINER="squash-e2e-postgres"
APP_CONTAINER="squash-e2e-app"
APP_IMAGE="squash-app:smoke"
APP_PORT=8080

DB_NAME="squash"
DB_USER="postgres"
DB_PASSWORD="e2e-pass"

docker network create "$E2E_NETWORK"

docker run -d \
  --name "$POSTGRES_CONTAINER" \
  --network "$E2E_NETWORK" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -e POSTGRES_DB="$DB_NAME" \
  postgres:17

wait_for_postgres "$POSTGRES_CONTAINER" "$DB_USER" "$DB_NAME"

docker run -d \
  --name "$APP_CONTAINER" \
  --network "$E2E_NETWORK" \
  -e DB_HOST="$POSTGRES_CONTAINER" \
  -e DB_PORT=5432 \
  -e DB_NAME="$DB_NAME" \
  -e DB_USER="$DB_USER" \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e PORT="$APP_PORT" \
  -e LLM_API_KEY="dummy-e2e-key" \
  -p "${APP_PORT}:${APP_PORT}" \
  "$APP_IMAGE"

wait_for_app "$APP_CONTAINER" "$APP_PORT"
