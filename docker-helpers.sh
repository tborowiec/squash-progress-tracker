#!/usr/bin/env bash
# Sourced by smoke-container.sh and start-e2e-app.sh — not run directly.

wait_for_postgres() {
  local container="$1" user="$2" db="$3" max="${4:-30}"
  echo -n "==> Waiting for Postgres ($container) to be ready..."
  local tries=0
  until docker exec "$container" pg_isready -U "$user" -d "$db" -q 2>/dev/null; do
    tries=$((tries + 1))
    if [ "$tries" -ge "$max" ]; then
      echo " TIMEOUT"
      echo "ERROR: Postgres did not become ready in time." >&2
      exit 1
    fi
    echo -n "."; sleep 1
  done
  echo " ready."
}

wait_for_app() {
  local container="$1" port="${2:-8080}" max="${3:-30}"
  echo -n "==> Waiting for app ($container) to be healthy..."
  local tries=0
  until docker exec "$container" curl -s "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    tries=$((tries + 1))
    if [ "$tries" -ge "$max" ]; then
      echo " TIMEOUT"
      echo "ERROR: App did not become healthy in time. Docker logs:" >&2
      docker logs "$container" >&2
      exit 1
    fi
    echo -n "."; sleep 2
  done
  echo " UP."
}
