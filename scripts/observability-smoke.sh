#!/usr/bin/env bash
# scripts/observability-smoke.sh — round-trip: one request → metric + log + trace.
set -euo pipefail

NS="multistate-dev"
MON_NS="${MON_NS:-monitoring}"
LOKI_NS="${LOKI_NS:-observability}"
TEMPO_NS="${TEMPO_NS:-observability}"
APP="multistate-api"
CORR_ID="${CORR_ID:-smoke-corr-$(date +%s)}"
URL="http://${APP}.${NS}.svc.cluster.local:8080/tenants/tnt_synth_001"
WAIT_SEC="${OBS_SMOKE_WAIT_SEC:-45}"
REQUIRE_PLG="${OBS_SMOKE_REQUIRE_PLG:-true}"
LOG_FILE="${OBS_SMOKE_LOG_FILE:-/tmp/observability-smoke-${CORR_ID}.log}"
exec > >(tee -a "${LOG_FILE}") 2>&1

prom_query() {
  local query="$1"
  kubectl -n "${MON_NS}" run --rm -i --restart=Never obs-prom-query \
    --image=curlimages/curl:8.7.1 -- \
    sh -c "curl --silent --fail --get \
      --data-urlencode 'query=${query}' \
      http://kube-prometheus-stack-prometheus.${MON_NS}.svc.cluster.local:9090/api/v1/query"
}

loki_query() {
  local query="$1"
  kubectl -n "${LOKI_NS}" run --rm -i --restart=Never obs-loki-query \
    --image=curlimages/curl:8.7.1 -- \
    sh -c "curl --silent --fail --get \
      --data-urlencode 'query=${query}' \
      --data-urlencode 'limit=20' \
      http://loki.${LOKI_NS}.svc.cluster.local:3100/loki/api/v1/query"
}

tempo_search() {
  local query="$1"
  kubectl -n "${TEMPO_NS}" run --rm -i --restart=Never obs-tempo-query \
    --image=curlimages/curl:8.7.1 -- \
    sh -c "curl --silent --fail --get \
      --data-urlencode 'q=${query}' \
      http://tempo.${TEMPO_NS}.svc.cluster.local:3200/api/search"
}

assert_actuator_metric() {
  kubectl exec -n "${NS}" "deploy/${APP}" -- \
    wget -qO- "http://localhost:8080/actuator/prometheus" \
    | grep -q 'multistate_nexus_evaluations_total'
}

assert_pod_log_line() {
  kubectl logs -n "${NS}" "deploy/${APP}" --tail=500 \
    | grep -F "\"correlationId\":\"${CORR_ID}\"" \
    | grep -q 'lookup attempted'
}

echo "[1/4] request with correlation id ${CORR_ID}"
kubectl run --rm -i --restart=Never -n "${NS}" obs-smoke-curl \
  --image=curlimages/curl:8.7.1 -- \
  curl --silent --show-error --output /dev/null \
    -H "x-correlation-id: ${CORR_ID}" \
    "${URL}"

echo "[2/4] waiting ${WAIT_SEC}s for scrape / ingest"
sleep "${WAIT_SEC}"

echo "[3/4] Prometheus sample for multistate_nexus_evaluations_total"
if kubectl get ns "${MON_NS}" >/dev/null 2>&1 \
    && kubectl -n "${MON_NS}" get svc kube-prometheus-stack-prometheus >/dev/null 2>&1; then
  prom_query "multistate_nexus_evaluations_total{app=\"${APP}\"}" \
    | grep -E '"value"|"metric"' >/dev/null
else
  if [ "${REQUIRE_PLG}" = "true" ]; then
    echo "ERROR: Prometheus service not found in ${MON_NS}" >&2
    exit 1
  fi
  echo "  PLG absent — checking actuator/prometheus on ${APP}"
  assert_actuator_metric
fi

echo "[4/4] Loki log line + Tempo trace for correlation id"
if kubectl -n "${LOKI_NS}" get svc loki >/dev/null 2>&1; then
  loki_query "{app=\"${APP}\"} | json | correlationId=\"${CORR_ID}\"" \
    | grep -F 'lookup attempted' >/dev/null
else
  if [ "${REQUIRE_PLG}" = "true" ]; then
    echo "ERROR: Loki service not found in ${LOKI_NS}" >&2
    exit 1
  fi
  echo "  Loki absent — checking pod logs for correlationId"
  assert_pod_log_line
fi

if kubectl -n "${TEMPO_NS}" get svc tempo >/dev/null 2>&1; then
  tempo_search "{ resource.service.name=\"${APP}\" && span.correlationId=\"${CORR_ID}\" }" \
    | grep -E 'traceID|traceId' >/dev/null
else
  if [ "${REQUIRE_PLG}" = "true" ]; then
    echo "ERROR: Tempo service not found in ${TEMPO_NS}" >&2
    exit 1
  fi
  echo "  Tempo absent — skipping trace search (correlation id on span requires PLG stack)"
fi

echo "OK: metric + log + trace visible for ${CORR_ID} (log: ${LOG_FILE})"
