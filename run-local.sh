#!/usr/bin/env bash
set -e

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-squash}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-pass}"

export DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD

DOCKER_CONTAINER="squash-postgres-dev"
DOCKER_STARTED=false

if ! nc -z "$DB_HOST" "$DB_PORT" 2>/dev/null; then
  echo "No Postgres detected on $DB_HOST:$DB_PORT — starting Docker container..."
  if ! docker info &>/dev/null; then
    echo "ERROR: Docker is not running. Start Docker or provide a running Postgres instance." >&2
    exit 1
  fi
  if ! docker ps -a --format '{{.Names}}' | grep -q "^${DOCKER_CONTAINER}$"; then
    docker run -d --name "$DOCKER_CONTAINER" \
      -e POSTGRES_PASSWORD="$DB_PASSWORD" \
      -e POSTGRES_USER="$DB_USER" \
      -e POSTGRES_DB="$DB_NAME" \
      -p "${DB_PORT}:5432" \
      postgres:17
  else
    docker start "$DOCKER_CONTAINER"
  fi
  DOCKER_STARTED=true
  echo -n "Waiting for Postgres to be ready..."
  until nc -z "$DB_HOST" "$DB_PORT" 2>/dev/null; do
    echo -n "."
    sleep 1
  done
  echo " ready."
fi

echo "Building frontend..."
(cd frontend && npm install --silent && npm run build)

echo "Connecting to postgres://$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
exec ./mvnw spring-boot:run
