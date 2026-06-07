#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=docker-helpers.sh
. "$(dirname "$0")/docker-helpers.sh"

SMOKE_NETWORK="squash-smoke-net"
POSTGRES_CONTAINER="squash-smoke-postgres"
APP_CONTAINER="squash-smoke-app"
APP_IMAGE="squash-app:smoke"
APP_PORT=8080

DB_NAME="squash"
DB_USER="postgres"
DB_PASSWORD="smoke-pass"

PASS=0
FAIL=0

cleanup() {
  docker rm -f "$APP_CONTAINER" "$POSTGRES_CONTAINER" 2>/dev/null || true
  docker network rm "$SMOKE_NETWORK" 2>/dev/null || true
}
trap cleanup EXIT

# ── network ──────────────────────────────────────────────────────────────────
echo "==> Creating docker network $SMOKE_NETWORK..."
docker network create "$SMOKE_NETWORK" 2>/dev/null || echo "Network already exists, reusing."

# ── postgres ─────────────────────────────────────────────────────────────────
echo "==> Starting Postgres container..."
docker run -d \
  --name "$POSTGRES_CONTAINER" \
  --network "$SMOKE_NETWORK" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -e POSTGRES_DB="$DB_NAME" \
  postgres:17

wait_for_postgres "$POSTGRES_CONTAINER" "$DB_USER" "$DB_NAME"

# ── image build ───────────────────────────────────────────────────────────────
echo "==> Building Docker image $APP_IMAGE..."
docker build -t "$APP_IMAGE" .

# ── app container ─────────────────────────────────────────────────────────────
echo "==> Starting app container..."
docker run -d \
  --name "$APP_CONTAINER" \
  --network "$SMOKE_NETWORK" \
  -e DB_HOST="$POSTGRES_CONTAINER" \
  -e DB_PORT=5432 \
  -e DB_NAME="$DB_NAME" \
  -e DB_USER="$DB_USER" \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e PORT="$APP_PORT" \
  -e LLM_API_KEY="dummy-smoke-key" \
  -p "${APP_PORT}:${APP_PORT}" \
  "$APP_IMAGE"

# We use docker exec so assertions work regardless of whether the host's
# IP forwarding / docker-proxy path is functional (dev boxes vary).
wait_for_app "$APP_CONTAINER" "$APP_PORT"

# ── helper: run curl inside the app container ─────────────────────────────────
app_curl() {
  docker exec "$APP_CONTAINER" curl -s "$@"
}

# ── assertions ────────────────────────────────────────────────────────────────
assert_pass() {
  PASS=$((PASS + 1))
  echo "  [PASS] $1"
}

assert_fail() {
  FAIL=$((FAIL + 1))
  echo "  [FAIL] $1"
}

echo ""
echo "==> Running smoke assertions..."

# 1. /actuator/health returns 200 + "UP"
HEALTH_CODE=$(app_curl -o /dev/null -w "%{http_code}" "http://localhost:${APP_PORT}/actuator/health" 2>/dev/null || echo "000")
HEALTH_JSON=$(app_curl "http://localhost:${APP_PORT}/actuator/health" 2>/dev/null || echo "")
if [ "$HEALTH_CODE" = "200" ] && echo "$HEALTH_JSON" | grep -q '"status":"UP"'; then
  assert_pass "GET /actuator/health → 200 + UP"
else
  assert_fail "GET /actuator/health → 200 + UP (got HTTP $HEALTH_CODE, body: $HEALTH_JSON)"
fi

# 2. / returns 200 + SPA shell marker
ROOT_CODE=$(app_curl -o /tmp/smoke_root.html -w "%{http_code}" "http://localhost:${APP_PORT}/" 2>/dev/null || echo "000")
ROOT_BODY=$(docker exec "$APP_CONTAINER" cat /tmp/smoke_root.html 2>/dev/null || echo "")
if [ "$ROOT_CODE" = "200" ] && echo "$ROOT_BODY" | grep -q 'id="root"'; then
  assert_pass "GET / → 200 + SPA shell (<div id=\"root\">)"
else
  assert_fail "GET / → 200 + SPA shell (got HTTP $ROOT_CODE)"
fi

# 3. /api/auth/me returns 401
ME_CODE=$(app_curl -o /dev/null -w "%{http_code}" "http://localhost:${APP_PORT}/api/auth/me" 2>/dev/null || echo "000")
if [ "$ME_CODE" = "401" ]; then
  assert_pass "GET /api/auth/me → 401"
else
  assert_fail "GET /api/auth/me → 401 (got HTTP $ME_CODE)"
fi

# ── summary ───────────────────────────────────────────────────────────────────
echo ""
echo "==> Results: $PASS passed, $FAIL failed."
if [ "$FAIL" -gt 0 ]; then
  echo "SMOKE FAILED."
  exit 1
fi
echo "SMOKE PASSED."
