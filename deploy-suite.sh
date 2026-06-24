#!/usr/bin/env bash
# Deploy the suite by building the image LOCALLY and shipping it — NEVER build on the box.
#
# The box is a shared 4 GB instance (suite + Postgres + mortgage-app + others). The Dockerfile
# runs a Gradle build in its first stage; doing that on the box OOM-thrashes it and takes the whole
# instance down (2026-06-24 incident). So we `docker build` here, `docker save | ssh docker load`
# the image to the box, and `docker compose up -d` the existing `image: msfg-suite:latest` — the box
# never compiles anything. Flyway runs on app boot, so a new migration (e.g. V26) applies on deploy.
#
# Usage: ./deploy-suite.sh         (build + ship + recreate + health-check)
#        EC2_HOST=... EC2_KEY=... ./deploy-suite.sh   (override target)
set -euo pipefail

EC2_KEY="${EC2_KEY:-/Users/zacharyzink/MSFG/Security/msfg-mortgage-key.pem}"
EC2_HOST="${EC2_HOST:-ubuntu@52.203.186.217}"
EC2_DIR="${EC2_DIR:-/home/ubuntu/apps/msfg-suite}"
IMAGE="msfg-suite:latest"
SSH="ssh -i $EC2_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25"
cd "$(dirname "$0")"

echo "▸ SSH preflight…"
$SSH "$EC2_HOST" 'true'

echo "▸ Building $IMAGE locally (Gradle bootJar inside the build stage — on THIS machine)…"
docker build -t "$IMAGE" .

echo "▸ Shipping image to the box (save | gzip | ssh | docker load)…"
docker save "$IMAGE" | gzip | $SSH "$EC2_HOST" 'gunzip | docker load'

echo "▸ Recreating the suite app on the box (Flyway runs on boot → applies new migrations)…"
$SSH "$EC2_HOST" "cd $EC2_DIR && docker compose up -d app"

echo "▸ Waiting for suite health…"
$SSH "$EC2_HOST" 'for i in $(seq 1 40); do
    if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then echo "  ✓ /actuator/health UP"; exit 0; fi
    sleep 3
  done
  echo "  ✗ health timed out — recent logs:"; docker logs --tail 40 msfg-suite-app; exit 1'

echo "✓ Suite deployed (built locally, image shipped — box never compiled)."
