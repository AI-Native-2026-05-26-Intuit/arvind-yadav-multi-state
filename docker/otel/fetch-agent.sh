#!/usr/bin/env bash
# docker/otel/fetch-agent.sh — download OTel Java agent v2.5.0 into the build context.
set -euo pipefail
DEST="$(cd "$(dirname "$0")" && pwd)/opentelemetry-javaagent.jar"
URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.5.0/opentelemetry-javaagent.jar"
if [ -f "${DEST}" ]; then
  echo "OTel agent already present: ${DEST}"
  exit 0
fi
curl -fsSL "${URL}" -o "${DEST}" || curl -fsSL --insecure "${URL}" -o "${DEST}"
echo "Downloaded ${DEST} ($(wc -c < "${DEST}") bytes)"
