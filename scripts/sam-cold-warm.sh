#!/usr/bin/env bash
# scripts/sam-cold-warm.sh — cold-vs-warm latency probe for the tenant-lookup API.
# Call 1 is cold (post-SnapStart restore); calls 2–5 are warm.
#
# Usage:
#   HTTP_API_URL=https://....execute-api.us-east-1.amazonaws.com/dev ./scripts/sam-cold-warm.sh
#   ./scripts/sam-cold-warm.sh https://....execute-api.us-east-1.amazonaws.com/dev

set -euo pipefail

STAGE_NAME="${STAGE_NAME:-dev}"
FUNCTION_NAME="${FUNCTION_NAME:-multistate-tenant-lookup-${STAGE_NAME}}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

HTTP_API_URL="${HTTP_API_URL:-${1:-}}"
if [[ -z "${HTTP_API_URL}" ]]; then
    STACK_NAME="${STACK_NAME:-multistate-lambda-dev}"
    HTTP_API_URL="$(aws cloudformation describe-stacks \
        --stack-name "${STACK_NAME}" \
        --region "${REGION}" \
        --query "Stacks[0].Outputs[?OutputKey=='HttpApiUrl'].OutputValue | [0]" \
        --output text)"
fi

if [[ -z "${HTTP_API_URL}" || "${HTTP_API_URL}" == "None" ]]; then
    echo "ERROR: set HTTP_API_URL or deploy the stack first." >&2
    exit 1
fi

echo "Probing ${HTTP_API_URL}/tenants/tnt_synth_001 (5 calls, 0.5s apart)"
echo ""

for i in 1 2 3 4 5; do
    echo "--- call ${i} (x-correlation-id: cold-${i}) ---"
    time curl -s -o /dev/null \
        -H "x-correlation-id: cold-${i}" \
        "${HTTP_API_URL}/tenants/tnt_synth_001"
    sleep 0.5
done

echo ""
echo "==> REPORT lines (Init Duration + Duration) — last 10m"
aws logs tail "/aws/lambda/${FUNCTION_NAME}" \
    --since 10m \
    --filter-pattern REPORT \
    --region "${REGION}"
