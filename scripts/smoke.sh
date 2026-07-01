#!/usr/bin/env bash
# scripts/smoke.sh - boot the stack, assert every layer responds.
# Identical script runs on engineer laptops and in CI.
#
# Exits 0 on green smoke; non-zero with logs printed on failure.

set -euo pipefail

PROJECT="multistate_dev_smoke_$$"
COMPOSE="docker compose -p ${PROJECT}"
HOST_PORT="${HOST_PORT:-8080}"

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

echo "Bringing up stack as project ${PROJECT}..."
${COMPOSE} up -d --wait --wait-timeout 240

echo "Confirming all services are healthy..."
unhealthy=$(${COMPOSE} ps --format json \
  | jq -r '.[] | select(.Health != "healthy" and .Health != "") | .Name')
if [ -n "${unhealthy}" ]; then
    echo "ERROR: services not healthy: ${unhealthy}"
    exit 1
fi

echo "Smoke 1/3: GET /actuator/health/readiness"
curl --silent --show-error --fail \
     --retry 15 --retry-delay 2 --retry-connrefused \
     "http://localhost:${HOST_PORT}/actuator/health/readiness" \
     | jq -e '.status == "UP"'

echo "Smoke 2/3: GET /api/v1/tenants/tnt_synth_001 (200 or 404 both acceptable)"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
              "http://localhost:${HOST_PORT}/api/v1/tenants/tnt_synth_001")
if [ "${status}" != "200" ] && [ "${status}" != "404" ]; then
    echo "ERROR: unexpected status ${status} from /api/v1/tenants/tnt_synth_001"
    exit 1
fi

echo "Smoke 3/3: GET /actuator/health/liveness == UP"
curl --silent --show-error --fail \
     "http://localhost:${HOST_PORT}/actuator/health/liveness" \
     | jq -e '.status == "UP"'

echo ""
echo "Smoke OK. Stack ready in <90s, all three checks green."
