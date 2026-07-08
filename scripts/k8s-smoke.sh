#!/usr/bin/env bash
# scripts/k8s-smoke.sh - assert the deployed service serves traffic.
# Runs identically on a laptop and in CI.

set -euo pipefail

NAMESPACE="multistate-dev"
HOST="multistate.dev.uptimecrew.internal"
HOST_PORT="${HOST_PORT:-8080}"

# Retry an Ingress URL until HTTP 200 and optional body match (nginx may need
# time to program upstreams after a rollout; a single 200 is not enough).
ingress_curl_ok() {
    local path="$1"
    local body_pattern="${2:-}"
    local attempt
    for attempt in $(seq 1 36); do
        local body code
        body=$(curl --silent --show-error --write-out '\n%{http_code}' \
            -H "Host: ${HOST}" \
            "http://localhost:${HOST_PORT}${path}" 2>/dev/null || true)
        code=$(echo "${body}" | tail -n1)
        body=$(echo "${body}" | sed '$d')
        if [ "${code}" = "200" ]; then
            if [ -z "${body_pattern}" ] || echo "${body}" | grep -q "${body_pattern}"; then
                return 0
            fi
            echo "  ingress HTTP 200 but body mismatch (attempt ${attempt}/36) for ${path}"
        else
            echo "  ingress not ready (attempt ${attempt}/36): HTTP ${code} for ${path}"
        fi
        sleep 5
    done
    kubectl describe ingress/multistate-api -n "${NAMESPACE}" || true
    kubectl get endpoints multistate-api -n "${NAMESPACE}" -o wide || true
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
ingress_curl_ok "/actuator/health/readiness" '"status":"UP"'
echo "  → readiness UP"

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
ingress_curl_ok "/actuator/health/liveness" '"status":"UP"'
echo "  → liveness UP"

echo ""
echo "Smoke OK. Deployment serving through the Ingress."
