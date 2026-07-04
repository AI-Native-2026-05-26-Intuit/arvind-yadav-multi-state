#!/usr/bin/env bash
# scripts/sam-verify.sh — Task 2 done-criteria checks (SnapStart, env vars, p99 alarm).
#
# Usage:
#   ./scripts/sam-verify.sh

set -euo pipefail

STAGE_NAME="${STAGE_NAME:-dev}"
STACK_NAME="${STACK_NAME:-multistate-lambda-dev}"
FUNCTION_NAME="${FUNCTION_NAME:-multistate-tenant-lookup-${STAGE_NAME}}"
ALARM_NAME="multistate-tenant-lookup-p99-${STAGE_NAME}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

echo "==> SnapStart (expect ApplyOn=PublishedVersions)"
aws lambda get-function-configuration \
    --function-name "${FUNCTION_NAME}" \
    --region "${REGION}" \
    --query 'SnapStart' \
    --output json

echo ""
echo "==> Aliases (expect live)"
aws lambda list-aliases \
    --function-name "${FUNCTION_NAME}" \
    --region "${REGION}" \
    --query 'Aliases[].Name' \
    --output json

echo ""
echo "==> Environment.Variables (expect TENANTS_TABLE from stack; no hardcoded region)"
aws lambda get-function-configuration \
    --function-name "${FUNCTION_NAME}" \
    --region "${REGION}" \
    --query 'Environment.Variables' \
    --output json

echo ""
echo "==> p99 latency alarm (expect OK or INSUFFICIENT_DATA)"
aws cloudwatch describe-alarms \
    --alarm-names "${ALARM_NAME}" \
    --region "${REGION}" \
    --query 'MetricAlarms[0].{Name:AlarmName,State:StateValue,Threshold:Threshold,Statistic:ExtendedStatistic}' \
    --output table
