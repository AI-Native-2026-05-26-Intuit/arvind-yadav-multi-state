#!/usr/bin/env bash
# scripts/sam-verify-task3.sh — Task 3 done-criteria checks (IAM, logs, correlation, EMF).
#
# Usage:
#   ./scripts/sam-verify-task3.sh
#   HTTP_API_URL=https://... ./scripts/sam-verify-task3.sh

set -euo pipefail

STAGE_NAME="${STAGE_NAME:-dev}"
STACK_NAME="${STACK_NAME:-multistate-lambda-dev}"
FUNCTION_NAME="${FUNCTION_NAME:-multistate-tenant-lookup-${STAGE_NAME}}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

HTTP_API_URL="${HTTP_API_URL:-}"
if [[ -z "${HTTP_API_URL}" ]]; then
    HTTP_API_URL="$(aws cloudformation describe-stacks \
        --stack-name "${STACK_NAME}" \
        --region "${REGION}" \
        --query "Stacks[0].Outputs[?OutputKey=='HttpApiUrl'].OutputValue | [0]" \
        --output text 2>/dev/null || true)"
fi

echo "==> IAM inline policy (expect dynamodb:GetItem/BatchGetItem/… on table ARN, NOT dynamodb:*)"
ROLE_ARN="$(aws lambda get-function-configuration \
    --function-name "${FUNCTION_NAME}" \
    --region "${REGION}" \
    --query 'Role' \
    --output text)"
ROLE_NAME="$(echo "${ROLE_ARN}" | awk -F/ '{print $NF}')"

INLINE_NAMES="$(aws iam list-role-policies \
    --role-name "${ROLE_NAME}" \
    --query 'PolicyNames' \
    --output text)"

for POLICY_NAME in ${INLINE_NAMES}; do
    echo "--- ${ROLE_NAME} / ${POLICY_NAME} ---"
    aws iam get-role-policy \
        --role-name "${ROLE_NAME}" \
        --policy-name "${POLICY_NAME}" \
        --output json
done

echo ""
echo "==> Recent log lines (expect JSON envelopes when LogFormat: JSON is active)"
aws logs tail "/aws/lambda/${FUNCTION_NAME}" \
    --since 10m \
    --region "${REGION}" \
    --format short 2>/dev/null | tail -5 || echo "(no recent logs)"

echo ""
if [[ -n "${HTTP_API_URL}" && "${HTTP_API_URL}" != "None" ]]; then
    echo "==> Correlation-id echo (expect x-correlation-id: probe-123 in response headers)"
    curl -s -D - -o /dev/null \
        -H "x-correlation-id: probe-123" \
        "${HTTP_API_URL}/tenants/tnt_synth_001" | grep -i x-correlation-id || true
else
    echo "==> Skipping correlation curl (set HTTP_API_URL or deploy stack)"
fi

echo ""
echo "==> Custom metrics namespace MultistateDev"
aws cloudwatch list-metrics \
    --namespace MultistateDev \
    --region "${REGION}" \
    --output table
