#!/usr/bin/env bash
# scripts/llm-cost-spike.sh
#
# Synthetic spike: 200 high-token calls over ~5 minutes against
# the W3D1 LLM-proxy to force the CostPerRequestAlarm into ALARM
# state. Tenant tag is "tenant-synth" so finance can filter the
# spike out of the real spend tally; the script never tags a real
# tenant id.
#
# After the script exits, the alarm should return to OK within
# three more 5-minute periods; if it doesn't, the metric pipeline
# has a stuck data path (Section 9 sticking point #1).
set -euo pipefail

PROXY="${PROXY:-http://llm-proxy.multistate-dev.svc.cluster.local:8080}"
TENANT="tenant-synth"
FEATURE="summarize-nexus"

# A prompt large enough to push per-call cost over the 0.005 USD
# threshold for the dev modelId (anthropic.claude-3-5-sonnet-20241022-v2:0).
read -r -d '' PROMPT <<'PEOF'
You are a synthetic load generator for the multistate
capstone at uptimecrew. Produce a long-form summary of the
attached tenant record; repeat key phrases as
needed to reach approximately 3000 tokens of output. The exact
wording does not matter; the goal is to drive token-count and
per-call cost above the alarm threshold for the duration of
this spike.
PEOF

echo "Starting spike against ${PROXY} (tenant=${TENANT}, feature=${FEATURE})"
for i in $(seq 1 200); do
  curl -s -o /dev/null -X POST "${PROXY}/v1/completions" \
    -H "Content-Type: application/json" \
    -H "X-Tenant: ${TENANT}" \
    -H "X-Feature: ${FEATURE}" \
    -d "{\"prompt\": \"${PROMPT}\"}"
  sleep 1.5
done

echo "Spike complete; expect alarm transition to ALARM within ~5 minutes."
echo "Run 'aws cloudwatch describe-alarms --alarm-names multistate/cost-per-request-dev' to verify."
