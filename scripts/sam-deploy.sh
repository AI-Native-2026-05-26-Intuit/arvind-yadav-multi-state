#!/usr/bin/env bash
# scripts/sam-deploy.sh - one-shot validate + build + deploy.
# Idempotent: re-running just re-applies the change set.

set -euo pipefail

STACK="${STACK:-multistate-lambda-${STAGE:-dev}}"
STAGE="${STAGE:-dev}"
REGION="${AWS_REGION:-us-east-1}"

if [[ -S "${HOME}/.rd/docker.sock" ]]; then
    export DOCKER_HOST="unix://${HOME}/.rd/docker.sock"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}"

echo "==> sam validate --lint"
sam validate --lint

echo "==> ./gradlew tenantLookupJar"
./gradlew tenantLookupJar

echo "==> sam build --use-container"
sam build --use-container

if [[ ! -f samconfig.toml ]]; then
    echo "==> sam deploy --guided (first-time setup)"
    sam deploy --guided \
        --stack-name "${STACK}" \
        --region "${REGION}" \
        --parameter-overrides "StageName=${STAGE}" \
        --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND
else
    echo "==> sam deploy"
    sam deploy \
        --no-confirm-changeset \
        --no-fail-on-empty-changeset \
        --stack-name "${STACK}" \
        --region "${REGION}" \
        --parameter-overrides "StageName=${STAGE}" \
        --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND \
        --resolve-s3
fi

echo ""
echo "==> Outputs:"
aws cloudformation describe-stacks \
    --stack-name "${STACK}" \
    --region "${REGION}" \
    --query 'Stacks[0].Outputs' \
    --output table
