#!/usr/bin/env bash
# scripts/spike-runner.sh
# TASK 4: posts synthetic events to multistate-nexus-jobs-dev
# so KEDA scales the worker from 0 to ~22 pods.
# Tenant tag is "tenant-synth" so the W6D4 cost
# pipeline can subtract these from the real spend.
#
# Uses SendMessageBatch (10 msgs/call) so a 4000-msg
# spike finishes in minutes rather than tens of minutes.
set -euo pipefail

QUEUE_URL="${QUEUE_URL:-https://sqs.us-east-1.amazonaws.com/$(aws sts get-caller-identity --query Account --output text)/multistate-nexus-jobs-dev}"
TENANT="tenant-synth"
FEATURE="summarize-nexus"
COUNT="${COUNT:-4000}"
BATCH_SIZE=10

if [[ ! "${COUNT}" =~ ^[0-9]+$ ]]; then
  echo "ERROR: COUNT must be a non-negative integer (got: ${COUNT})" >&2
  exit 1
fi

echo "Posting ${COUNT} synthetic events to ${QUEUE_URL}"
echo "tenant=${TENANT} feature=${FEATURE}"

tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT

posted=0
while (( posted < COUNT )); do
  entries="["
  batch=0
  while (( batch < BATCH_SIZE && posted + batch < COUNT )); do
    i=$((posted + batch + 1))
    body=$(printf '{"tenant":"%s","feature":"%s","tenantId":"synth-%s"}' "${TENANT}" "${FEATURE}" "${i}")
    # Escape quotes for JSON entry
    body_esc=${body//\"/\\\"}
    if (( batch > 0 )); then entries+=","; fi
    entries+=$(printf '{"Id":"%s","MessageBody":"%s"}' "${batch}" "${body_esc}")
    batch=$((batch + 1))
  done
  entries+="]"
  printf '%s\n' "${entries}" > "${tmp}"
  aws sqs send-message-batch --queue-url "${QUEUE_URL}" \
    --entries "file://${tmp}" \
    --region us-east-1 \
    >/dev/null
  posted=$((posted + batch))
  if (( posted % 250 == 0 || posted == COUNT )); then
    echo "  posted ${posted} / ${COUNT}"
  fi
done

echo "Spike posted. Now watch:"
echo "  kubectl -n multistate-dev get scaledobject multistate-worker-scaledobject -w"
echo "  kubectl -n multistate-dev get pods -l app=multistate-api-worker -w"
echo "Expect KEDA to scale worker from 0 to ~22 within 2-3 minutes."
