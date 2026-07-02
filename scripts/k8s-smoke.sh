#!/usr/bin/env bash
# scripts/k8s-smoke.sh - assert the deployed service serves traffic.
# Runs identically on a laptop and in CI.

set -euo pipefail

NAMESPACE="multistate-dev"
HOST="multistate.dev.uptimecrew.internal"
HOST_PORT="${HOST_PORT:-8080}"

# Retry an Ingress URL until HTTP 200 (nginx may need a few seconds after
# the Ingress resource is applied; curl --retry does not retry on 404).
ingress_curl_ok() {
    local path="$1"
    local attempt
    for attempt in $(seq 1 36); do
        local code
        code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
            -H "Host: ${HOST}" \
            "http://localhost:${HOST_PORT}${path}" || true)
        if [ "${code}" = "200" ]; then
            return 0
        fi
        echo "  ingress not ready (attempt ${attempt}/36): HTTP ${code} for ${path}"
        sleep 5
    done
    kubectl describe ingress/multistate-api -n "${NAMESPACE}" || true
    return 1
}

# 1. Wait for the Deployment's rollout to settle.
kubectl rollout status deploy/multistate-api \
    -n "${NAMESPACE}" --timeout=5m

# 2. EndpointSlice has at least one ready endpoint (catches label drift).
endpoints=$(kubectl get endpointslice \
    -n "${NAMESPACE}" \
    -l kubernetes.io/service-name=multistate-api \
    -o jsonpath='{.items[*].endpoints[?(@.conditions.ready==true)].addresses[0]}' \
    | tr ' ' '\n' | grep -c . || true)
if [ "${endpoints}" -lt 1 ]; then
    echo "ERROR: Service multistate-api has zero ready endpoints."
    echo "       Check selector/label match (Topic 4 silent failure)."
    kubectl describe svc/multistate-api -n "${NAMESPACE}" || true
    exit 1
fi

# 3. Smoke through the Ingress (catches end-to-end routing).
echo "Smoke 1/3: GET /actuator/health/readiness via Ingress"
ingress_curl_ok "/actuator/health/readiness"
curl --silent --show-error --fail \
     -H "Host: ${HOST}" \
     "http://localhost:${HOST_PORT}/actuator/health/readiness" \
     | grep -q '"status":"UP"'

echo "Smoke 2/3: GET /api/v1/tenants/tnt_synth_001 (200, 401, or 404 acceptable)"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    -H "Host: ${HOST}" \
    "http://localhost:${HOST_PORT}/api/v1/tenants/tnt_synth_001")
if [ "${status}" != "200" ] && [ "${status}" != "404" ] && [ "${status}" != "401" ]; then
    echo "ERROR: unexpected status ${status} from /api/v1/tenants/tnt_synth_001"
    exit 1
fi
echo "  → HTTP ${status} (acceptable)"

echo "Smoke 3/3: GET /actuator/health/liveness via Ingress"
ingress_curl_ok "/actuator/health/liveness"
curl --silent --show-error --fail \
     -H "Host: ${HOST}" \
     "http://localhost:${HOST_PORT}/actuator/health/liveness" \
     | grep -q '"status":"UP"'

echo ""
echo "Smoke OK. Deployment serving through the Ingress."
