#!/usr/bin/env bash
# Deploy the suite WITHOUT building on the box and WITHOUT depending on local Docker/buildkit:
#   1. build the bootJar LOCALLY with Gradle (no Docker),
#   2. ship just the jar + a thin Dockerfile to the box,
#   3. build a thin runtime image ON the box (FROM the cached JRE + COPY jar) and recreate the app.
#
# Why: the box is a shared 4 GB instance — a Gradle build there OOM-thrashes it and takes the suite
# down (2026-06-24 incident). Building the FULL image locally instead hit a local Docker *buildkit*
# hang (couldn't resolve base-image metadata even with the registry reachable). Shipping the jar and
# doing a trivial COPY-only build on the box avoids BOTH: no Gradle on the box (no OOM), and no
# buildkit/docker.io dependency for the image build (the box already has the JRE cached; --pull=false).
# Flyway applies new migrations on app boot.
#
# Usage: ./deploy-suite.sh    ·    EC2_HOST=... EC2_KEY=... ./deploy-suite.sh
set -euo pipefail

EC2_KEY="${EC2_KEY:-/Users/zacharyzink/MSFG/Security/msfg-mortgage-key.pem}"
EC2_HOST="${EC2_HOST:-ubuntu@52.203.186.217}"
EC2_DIR="${EC2_DIR:-/home/ubuntu/apps/msfg-suite}"
SSH="ssh -i $EC2_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25"
cd "$(dirname "$0")"

echo "▸ SSH preflight…"; $SSH "$EC2_HOST" 'true'

echo "▸ Building the bootJar locally (Gradle, no Docker)…"
./gradlew :app:bootJar -x test --no-daemon -q
JAR="app/build/libs/app.jar"
[ -f "$JAR" ] || { echo "✗ $JAR not found"; exit 1; }

echo "▸ Shipping jar ($(du -h "$JAR" | cut -f1)) + thin Dockerfile to the box…"
scp -i "$EC2_KEY" -o StrictHostKeyChecking=accept-new "$JAR" Dockerfile.runtime "$EC2_HOST:$EC2_DIR/"

echo "▸ Thin-build on the box (cached JRE, --pull=false → no docker.io, no Gradle, no OOM) + recreate…"
$SSH "$EC2_HOST" "cd $EC2_DIR && docker build --pull=false -f Dockerfile.runtime -t msfg-suite:latest . && docker compose up -d --force-recreate app"

echo "▸ Waiting for suite health (Flyway runs on boot → new migrations apply here)…"
$SSH "$EC2_HOST" 'for i in $(seq 1 40); do
    curl -fsS http://127.0.0.1:8082/actuator/health >/dev/null 2>&1 && { echo "  ✓ /actuator/health UP"; exit 0; }
    sleep 3
  done; echo "  ✗ health timed out — recent logs:"; docker logs --tail 40 msfg-suite-app; exit 1'

echo "✓ Suite deployed (jar built locally, thin image on the box — box never compiled, no buildkit dependency)."
