#!/bin/bash
set -euo pipefail
: "${IMAGE:?Set IMAGE to the combined image}"
name="prayertime-smoke-${RANDOM}-$$"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# Ephemeral localhost ports; no production port or persistent data is touched.
docker run -d --name "$name" --cap-drop ALL --security-opt no-new-privileges \
  -e HABOUS_SYNC_ENABLED=false -p 127.0.0.1::8080 -p 127.0.0.1::8081 "$IMAGE"
calculator_port=$(docker port "$name" 8080/tcp | awk -F: '{print $NF}')
api_port=$(docker port "$name" 8081/tcp | awk -F: '{print $NF}')
ready=false
for _ in {1..90}; do
  if curl -fsS "http://127.0.0.1:${calculator_port}/api/v1/cities/casablanca/prayer-times?date=2026-07-01&source=calculated" >/dev/null \
    && curl -fsS "http://127.0.0.1:${api_port}/actuator/health" | grep -q 'UP'; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then docker logs "$name"; exit 1; fi
# Check that shutdown is handled gracefully by the combined entrypoint.
docker stop --time 20 "$name" >/dev/null
test "$(docker inspect --format '{{.State.ExitCode}}' "$name")" = 0
echo 'Both services responded from one image; graceful shutdown passed.'
