#!/usr/bin/env bash
# scripts/sam-smoke.sh - assert the deployed Lambda + HTTP API works.
# Runs identically on a laptop and in CI.

set -euo pipefail

STACK="${STACK:-multistate-lambda-${STAGE:-dev}}"
STAGE="${STAGE:-dev}"
REGION="${AWS_REGION:-us-east-1}"

URL="$(aws cloudformation describe-stacks \
    --stack-name "${STACK}" \
    --region "${REGION}" \
    --query 'Stacks[0].Outputs[?OutputKey==`HttpApiUrl`].OutputValue' \
    --output text)"

if [[ -z "${URL}" || "${URL}" == "None" ]]; then
    echo "ERROR: HttpApiUrl output not found on stack ${STACK}."
    exit 1
fi
echo "HttpApiUrl: ${URL}"

echo "Smoke 1/3: GET /tenants/tnt_synth_001 (expect 200 or 404)"
status="$(curl --silent --output /tmp/sam-smoke-body --write-out '%{http_code}' \
    -H "x-correlation-id: ci-smoke-$(date +%s)" \
    "${URL}/tenants/tnt_synth_001")"
if [[ "${status}" != "200" && "${status}" != "404" ]]; then
    echo "ERROR: unexpected status ${status} on known-good path."
    cat /tmp/sam-smoke-body
    exit 1
fi

echo "Smoke 2/3: GET /tenants/ (expect 404 from API Gateway route)"
status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "${URL}/tenants/")"
if [[ "${status}" != "404" ]]; then
    echo "ERROR: expected 404 from API Gateway route miss, got ${status}."
    exit 1
fi

echo "Smoke 3/3: at least one REPORT line in CloudWatch for the function"
FN_NAME="$(aws cloudformation describe-stacks \
    --stack-name "${STACK}" \
    --region "${REGION}" \
    --query 'Stacks[0].Outputs[?OutputKey==`FunctionName`].OutputValue' \
    --output text)"
sleep 4
start_ms=$(( ($(date +%s) - 120) * 1000 ))
report_count="$(aws logs filter-log-events \
    --region "${REGION}" \
    --log-group-name "/aws/lambda/${FN_NAME}" \
    --start-time "${start_ms}" \
    --filter-pattern '"REPORT"' \
    --query 'length(events)' --output text 2>/dev/null || echo 0)"
if [[ "${report_count}" -lt 1 ]]; then
    echo "ERROR: no REPORT lines in CloudWatch in the last 120s."
    exit 1
fi

echo ""
echo "Smoke OK. Function reachable; logs flowing; route table sane."
