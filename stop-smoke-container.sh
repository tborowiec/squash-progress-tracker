#!/usr/bin/env bash
set -euo pipefail

SMOKE_NETWORK="squash-smoke-net"
POSTGRES_CONTAINER="squash-smoke-postgres"
APP_CONTAINER="squash-smoke-app"

docker rm -f "$APP_CONTAINER" 2>/dev/null && echo "Removed $APP_CONTAINER" || echo "$APP_CONTAINER not found, skipping."
docker rm -f "$POSTGRES_CONTAINER" 2>/dev/null && echo "Removed $POSTGRES_CONTAINER" || echo "$POSTGRES_CONTAINER not found, skipping."
docker network rm "$SMOKE_NETWORK" 2>/dev/null && echo "Removed network $SMOKE_NETWORK" || echo "Network $SMOKE_NETWORK not found, skipping."

exit 0
