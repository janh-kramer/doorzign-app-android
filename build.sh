#!/usr/bin/env bash
# Führt Gradle im Build-Container aus (JDK 17, Android SDK) – gleiches Image wie DoorZign Edge.
# Beispiel: ./build.sh :api:test   |   ./build.sh :app:assembleDebug
set -euo pipefail
cd "$(dirname "$0")"
EDGE_DOCKER="$(cd .. && pwd)/doorzign-edge/docker"
# Docker-Speicher (8 GB) teilen sich alle Sessions: nicht parallel zu einem anderen Gradle-Build bauen.
# Dauerläufer auf demselben Image (z. B. dz-dev mit devhost/Simulator) zählen nicht.
other_builds() {
  docker ps --filter ancestor=doorzign-build:1 --format '{{.Command}}' --no-trunc | grep -c gradlew || true
}
while [ "$(other_builds)" -gt 0 ]; do
  echo "Warte auf laufenden doorzign-build-Container einer anderen Session …" >&2
  sleep 15
done
docker image inspect doorzign-build:1 >/dev/null 2>&1 || docker build --platform linux/amd64 -t doorzign-build:1 "$EDGE_DOCKER"
exec docker run --rm --platform linux/amd64 \
  -v "$PWD":/workspace -v doorzign-app-android-gradle:/gradle-cache \
  -v "$(cd .. && pwd)/doorzign-app-coordination/contract":/contract:ro \
  -e DOORZIGN_CONTRACT=/contract/dist/mobile-v1.yaml -e PRISM_URL="${PRISM_URL:-}" -e E2E_EDGE_URL="${E2E_EDGE_URL:-}" -e E2E_EDGE_FP="${E2E_EDGE_FP:-}" -e E2E_CLOUD_URL="${E2E_CLOUD_URL:-}" \
  -e GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false" \
  doorzign-build:1 ./gradlew "$@"
