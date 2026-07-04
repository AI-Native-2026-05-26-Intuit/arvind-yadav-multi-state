#!/usr/bin/env bash
# scripts/sam-deploy.sh — build the tenant-lookup Lambda and deploy/update the SAM stack.
# SnapStart + AutoPublishAlias: live are defined in template.yaml; first enable can
# take ~3–5 min while Lambda publishes a version and creates the snapshot.
#
# Usage:
#   ./scripts/sam-deploy.sh
#   STAGE_NAME=dev STACK_NAME=multistate-lambda-dev ./scripts/sam-deploy.sh

set -euo pipefail

STACK_NAME="${STACK_NAME:-multistate-lambda-dev}"
STAGE_NAME="${STAGE_NAME:-dev}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

if [[ -S "${HOME}/.rd/docker.sock" ]]; then
    export DOCKER_HOST="unix://${HOME}/.rd/docker.sock"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}"

echo "==> Building Lambda JAR (./gradlew tenantLookupJar)"
./gradlew tenantLookupJar

echo "==> SAM build (container)"
sam build --use-container

echo "==> SAM deploy stack=${STACK_NAME} stage=${STAGE_NAME} region=${REGION}"
sam deploy \
    --stack-name "${STACK_NAME}" \
    --parameter-overrides "StageName=${STAGE_NAME}" \
    --capabilities CAPABILITY_IAM \
    --resolve-s3 \
    --no-confirm-changeset \
    --no-fail-on-empty-changeset \
    --region "${REGION}"

echo ""
echo "==> Stack outputs"
aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --region "${REGION}" \
    --query 'Stacks[0].Outputs' \
    --output table

HTTP_API_URL="$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --region "${REGION}" \
    --query "Stacks[0].Outputs[?OutputKey=='HttpApiUrl'].OutputValue | [0]" \
    --output text)"

echo ""
echo "HttpApiUrl: ${HTTP_API_URL}"
echo ""
echo "Next:"
echo "  ./scripts/sam-verify.sh"
echo "  HTTP_API_URL=${HTTP_API_URL} ./scripts/sam-cold-warm.sh"
