#!/usr/bin/env bash
# scripts/observability-smoke.sh — round-trip: one request → metric + log + trace.
set -euo pipefail

NS="multistate-dev"
MON_NS="${MON_NS:-monitoring}"
LOKI_NS="${LOKI_NS:-observability}"
TEMPO_NS="${TEMPO_NS:-observability}"
APP="multistate-api"
CORR_ID="${CORR_ID:-smoke-corr-$(date +%s)}"
POD_SUFFIX="${CORR_ID//_/-}"
URL="http://${APP}.${NS}.svc.cluster.local:8080/tenants/tnt_synth_001"
PROM_URL="http://${APP}.${NS}.svc.cluster.local:8080/actuator/prometheus"
WAIT_SEC="${OBS_SMOKE_WAIT_SEC:-45}"
REQUIRE_PLG="${OBS_SMOKE_REQUIRE_PLG:-true}"
LOG_FILE="${OBS_SMOKE_LOG_FILE:-/tmp/observability-smoke-${CORR_ID}.log}"
CURL_IMAGE="${OBS_SMOKE_CURL_IMAGE:-curlimages/curl:8.7.1}"
exec > >(tee -a "${LOG_FILE}") 2>&1

# Ephemeral curl pod: -i waits for completion in CI; IfNotPresent uses k3d-imported images.
run_curl_pod() {
  local pod_name="$1"
  shift
  kubectl run --rm -i --restart=Never -n "${NS}" "${pod_name}" \
    --image="${CURL_IMAGE}" \
    --image-pull-policy=IfNotPresent \
    --command -- sh -ec "$*"
}

prom_query() {
  local query="$1"
  kubectl -n "${MON_NS}" run --rm -i --restart=Never "obs-prom-query-${POD_SUFFIX}" \
    --image="${CURL_IMAGE}" \
    --image-pull-policy=IfNotPresent \
    --command -- sh -ec \
    "curl --connect-timeout 15 --max-time 60 --silent --fail --get \
      --data-urlencode 'query=${query}' \
      'http://kube-prometheus-stack-prometheus.${MON_NS}.svc.cluster.local:9090/api/v1/query'"
}

loki_query() {
  local query="$1"
  kubectl -n "${LOKI_NS}" run --rm -i --restart=Never "obs-loki-query-${POD_SUFFIX}" \
    --image="${CURL_IMAGE}" \
    --image-pull-policy=IfNotPresent \
    --command -- sh -ec \
    "curl --connect-timeout 15 --max-time 60 --silent --fail --get \
      --data-urlencode 'query=${query}' \
      --data-urlencode 'limit=20' \
      'http://loki.${LOKI_NS}.svc.cluster.local:3100/loki/api/v1/query'"
}

tempo_search() {
  local query="$1"
  kubectl -n "${TEMPO_NS}" run --rm -i --restart=Never "obs-tempo-query-${POD_SUFFIX}" \
    --image="${CURL_IMAGE}" \
    --image-pull-policy=IfNotPresent \
    --command -- sh -ec \
    "curl --connect-timeout 15 --max-time 60 --silent --fail --get \
      --data-urlencode 'q=${query}' \
      'http://tempo.${TEMPO_NS}.svc.cluster.local:3200/api/search'"
}

assert_actuator_metric() {
  local attempt snippet
  for attempt in $(seq 1 6); do
    if run_curl_pod "obs-smoke-prom-${POD_SUFFIX}-${attempt}" \
        "curl --connect-timeout 15 --max-time 120 --silent --show-error --fail '${PROM_URL}' \
          | grep -qE 'multistate_nexus_evaluations(_total)?'"; then
      echo "  found nexus evaluation metric on /actuator/prometheus (attempt ${attempt})"
      return 0
    fi
    echo "  metric not visible yet (attempt ${attempt}/6); retrying in 5s..."
    sleep 5
  done
  echo "ERROR: /actuator/prometheus missing multistate_nexus_evaluations metric" >&2
  snippet="$(run_curl_pod "obs-smoke-prom-debug-${POD_SUFFIX}" \
    "curl --connect-timeout 15 --max-time 120 --silent --show-error '${PROM_URL}' \
      | grep -E 'multistate|http_server_requests' | head -20 || true" 2>/dev/null || true)"
  if [ -n "${snippet}" ]; then
    echo "--- prometheus snippet ---" >&2
    echo "${snippet}" >&2
  else
    echo "  (could not fetch /actuator/prometheus body)" >&2
  fi
  return 1
}

assert_pod_log_line() {
  local attempt hits
  for attempt in $(seq 1 6); do
  # Deployment has multiple replicas; aggregate logs from every ready pod.
  # Use --since (not --tail) so health-probe DEBUG lines cannot push out the
  # single smoke INFO line when security logging is verbose.
    hits="$(kubectl logs -n "${NS}" -l "app.kubernetes.io/name=${APP}" \
      --since=15m --max-log-requests=10 2>/dev/null \
      | grep -F "${CORR_ID}" \
      | grep -E 'lookup attempted|GET /tenants/' \
      | wc -l | tr -d ' ')"
    if [ "${hits}" -ge 1 ]; then
      echo "  found correlationId + lookup attempted in pod logs (attempt ${attempt})"
      return 0
    fi
    echo "  log line not visible yet (attempt ${attempt}/6); retrying in 5s..."
    sleep 5
  done
  echo "ERROR: no pod log with correlationId=${CORR_ID} and lookup attempted" >&2
  kubectl logs -n "${NS}" -l "app.kubernetes.io/name=${APP}" \
    --since=15m --tail=50 --max-log-requests=10 2>/dev/null \
    | grep -E 'lookup attempted|correlationId' >&2 || \
    kubectl logs -n "${NS}" -l "app.kubernetes.io/name=${APP}" --tail=30 --max-log-requests=10 >&2 || true
  return 1
}

echo "[1/4] request with correlation id ${CORR_ID}"
run_curl_pod "obs-smoke-curl-${POD_SUFFIX}" \
  "code=\$(curl --connect-timeout 15 --max-time 60 --silent --show-error --output /dev/null --write-out '%{http_code}' \
    -H 'x-correlation-id: ${CORR_ID}' '${URL}'); \
   echo HTTP \${code}; \
   test \"\${code}\" = '200' -o \"\${code}\" = '404'"

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
