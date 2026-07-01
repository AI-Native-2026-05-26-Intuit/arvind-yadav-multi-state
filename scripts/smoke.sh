#!/usr/bin/env bash
# scripts/smoke.sh — assert every layer of the stack responds.
# Identical script runs on engineer laptops and in CI.
#
# Modes:
#   default          — spin up a fresh isolated compose project, smoke, tear down.
#   SMOKE_REUSE=1    — smoke the already-running stack on $HOST_PORT; no up/down.
#                      Used by CI, where the workflow has already brought the
#                      stack up via `docker compose up --wait`.
#
# Exits 0 on green smoke; non-zero with logs printed on failure.
#
# Usage:
#   ./scripts/smoke.sh                        # default port 8082, isolated stack
#   HOST_PORT=8081 ./scripts/smoke.sh         # isolated stack on 8081
#   SMOKE_REUSE=1 HOST_PORT=8080 ./scripts/smoke.sh   # reuse existing stack

set -euo pipefail

HOST_PORT="${HOST_PORT:-8082}"
SMOKE_REUSE="${SMOKE_REUSE:-0}"

if [ "${SMOKE_REUSE}" = "1" ]; then
    # Smoke against the stack the caller already brought up. No new compose
    # project, no teardown — the caller owns the lifecycle.
    echo "SMOKE_REUSE=1 → probing existing stack on http://localhost:${HOST_PORT}"
else
    PROJECT="multistate_dev_smoke_$$"
    COMPOSE="docker compose -p ${PROJECT}"

    cleanup() {
        rc=$?
        echo ""
        echo "--- Final state ---"
        ${COMPOSE} ps || true
        if [ "${rc}" != "0" ]; then
            echo ""
            echo "--- Last 200 log lines per service ---"
            ${COMPOSE} logs --no-color --tail=200 || true
        fi
        ${COMPOSE} down --volumes --remove-orphans || true
        exit ${rc}
    }
    trap cleanup EXIT

    echo "Bringing up stack as project ${PROJECT} on host port ${HOST_PORT}..."
    # multistate-api healthcheck has start_period 180s for CI cold starts;
    # the wait timeout must exceed that or smoke fails before the JVM is ready.
    API_PORT=${HOST_PORT} ${COMPOSE} up -d --wait --wait-timeout 240

    echo "Confirming all services are healthy..."
    # Docker Compose v2 outputs NDJSON (one object per line); older versions output
    # a JSON array. jq -n + [inputs] handles both safely.
    unhealthy=$(${COMPOSE} ps --format json \
        | jq -rn '[inputs | if type == "array" then .[] else . end]
                  | .[] | select(.Health != null and .Health != "" and .Health != "healthy") | .Name' \
        2>/dev/null || true)
    if [ -n "${unhealthy}" ]; then
        echo "ERROR: services not healthy: ${unhealthy}"
        exit 1
    fi
fi

echo "Smoke 1/3: GET /actuator/health/readiness"
curl --silent --show-error --fail \
     --retry 15 --retry-delay 2 --retry-connrefused \
     "http://localhost:${HOST_PORT}/actuator/health/readiness" \
     | jq -e '.status == "UP"'

echo "Smoke 2/3: GET /api/v1/tenants/tnt_synth_001 (200, 401, or 404 acceptable)"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
              "http://localhost:${HOST_PORT}/api/v1/tenants/tnt_synth_001")
if [ "${status}" != "200" ] && [ "${status}" != "401" ] && [ "${status}" != "404" ]; then
    echo "ERROR: unexpected status ${status} from /api/v1/tenants/tnt_synth_001"
    exit 1
fi
echo "  → HTTP ${status} (acceptable)"

echo "Smoke 3/3: GET /actuator/health/liveness"
curl --silent --show-error --fail \
     "http://localhost:${HOST_PORT}/actuator/health/liveness" \
     | jq -e '.status == "UP"'

echo ""
echo "Smoke OK. All three checks green."
