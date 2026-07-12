#!/usr/bin/env bash
# scripts/spike-runner.sh
# TASK 4: posts synthetic events to multistate-nexus-jobs-dev
# so KEDA scales the worker from 0 to ~22 pods.
# Tenant tag is "tenant-synth" so the W6D4 cost
# pipeline can subtract these from the real spend.
set -euo pipefail

QUEUE_URL="${QUEUE_URL:-https://sqs.us-east-1.amazonaws.com/$(aws sts get-caller-identity --query Account --output text)/multistate-nexus-jobs-dev}"
TENANT="tenant-synth"
FEATURE="summarize-nexus"
COUNT="${COUNT:-4000}"

echo "Posting ${COUNT} synthetic events to ${QUEUE_URL}"
echo "tenant=${TENANT} feature=${FEATURE}"

for i in $(seq 1 "${COUNT}"); do
  aws sqs send-message --queue-url "${QUEUE_URL}" \
    --message-body "{\"tenant\":\"${TENANT}\",\"feature\":\"${FEATURE}\",\"tenantId\":\"synth-${i}\"}" \
    --region us-east-1 \
    >/dev/null
  if (( i % 250 == 0 )); then
    echo "  posted ${i} / ${COUNT}"
  fi
done

echo "Spike posted. Now watch:"
echo "  kubectl -n multistate-dev get scaledobject multistate-worker-scaledobject -w"
echo "  kubectl -n multistate-dev get pods -l app=multistate-api-worker -w"
echo "Expect KEDA to scale worker from 0 to ~22 within 2-3 minutes."
