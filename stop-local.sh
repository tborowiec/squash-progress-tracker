#!/usr/bin/env bash
set -e

DOCKER_CONTAINER="squash-postgres-dev"

if docker ps --format '{{.Names}}' | grep -q "^${DOCKER_CONTAINER}$"; then
  echo "Stopping $DOCKER_CONTAINER..."
  docker stop "$DOCKER_CONTAINER"
  echo "Stopped."
else
  echo "$DOCKER_CONTAINER is not running."
fi
