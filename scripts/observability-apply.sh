#!/usr/bin/env bash
# scripts/observability-apply.sh — one-shot apply of the W5 D5 observability layer.
set -euo pipefail

NS="multistate-dev"
OBS_NS="${OBS_NS:-observability}"
APP="multistate-api"
TAG="${TAG:-0.1.1}"
IMAGE="uptimecrew/multistate-api:${TAG}"
SLOTH_SPEC="slo/multistate-api.sloth.yaml"
RULES_FILE="manifests/observability/${APP}-prometheusrule.yaml"
DASH_JSON=".grafana/dashboards/${APP}-red.json"
SLOTH_IMAGE="${SLOTH_IMAGE:-ghcr.io/slok/sloth:v0.11.0}"
PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.54.0}"

sloth_generate() {
  docker run --rm -v "${PWD}:/work" -w /work "${SLOTH_IMAGE}" \
    generate -i "${SLOTH_SPEC}" -o "${RULES_FILE}"
}

promtool_check() {
  docker run --rm --entrypoint promtool \
    -v "${PWD}:/work" -w /work "${PROM_IMAGE}" \
    check rules "${RULES_FILE}"
}

apply_prometheusrule() {
  {
    cat <<EOF
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: ${APP}-slo
  namespace: ${NS}
  labels:
    release: kube-prometheus-stack
    team: multistate
spec:
EOF
    sed -n '/^groups:/,$p' "${RULES_FILE}" | sed 's/^/  /'
  } | kubectl apply -f -
}

ensure_otel_agent_jar() {
  if [ ! -f docker/otel/opentelemetry-javaagent.jar ]; then
    echo "==> Fetching OTel Java agent into docker/otel/"
    bash docker/otel/fetch-agent.sh
  fi
}

ensure_app_image() {
  ensure_otel_agent_jar
  echo "==> Building bootJar locally (Gradle in Docker may fail on TLS)"
  ./gradlew bootJar -x test --no-configuration-cache -q
  mkdir -p docker/staging
  cp build/libs/multistate-*.jar docker/staging/app.jar
  echo "==> Building ${IMAGE} from Dockerfile.prebuilt"
  docker build -f Dockerfile.prebuilt \
    --build-arg APP_VERSION="${TAG}" \
    --build-arg GIT_SHA="$(git rev-parse --short HEAD)" \
    -t "${IMAGE}" .
  if k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx multistate; then
    echo "==> Importing ${IMAGE} into k3d-multistate"
    k3d image import "${IMAGE}" -c multistate
  fi
}

import_k3d_image() {
  local img="$1"
  local tar="/tmp/k3d-import-$(echo "${img}" | tr '/:' '__').tar"
  docker pull "${img}"
  docker save "${img}" -o "${tar}"
  k3d image import "${tar}" -c multistate
  rm -f "${tar}"
}

ensure_app_prereqs() {
  echo "==> Ensuring namespace, secret, backing services, and ConfigMap"
  kubectl apply -f manifests/00-namespace.yaml
  kubectl apply -f manifests/observability/00-observability-namespace.yaml

  kubectl create secret generic multistate-api-secrets \
    -n "${NS}" \
    --from-literal=SPRING_DATASOURCE_PASSWORD="${PG_PASSWORD:-devpass}" \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl create configmap postgres-init-sql \
    -n "${NS}" \
    --from-file=db/V1__schema.sql \
    --from-file=db/V2__seed.sql \
    --from-file=db/V3__event_outbox.sql \
    --dry-run=client -o yaml | kubectl apply -f -

  if k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx multistate; then
    echo "==> Pre-importing backing-service images into k3d (avoids in-cluster TLS pulls)"
    for img in postgres:16 redis:7 mongo:7 apache/kafka:3.9.2; do
      import_k3d_image "${img}"
    done
  fi

  kubectl apply -f .github/k8s/ci-backing-services.yaml
  kubectl apply -f manifests/30-multistate-api.configmap.yaml
  kubectl apply -f manifests/20-multistate-api.service.yaml

  kubectl wait --for=condition=available deployment/postgres -n "${NS}" --timeout=180s
  kubectl wait --for=condition=available deployment/redis -n "${NS}" --timeout=120s
  kubectl wait --for=condition=ready pod -l app=mongo -n "${NS}" --timeout=180s
  kubectl wait --for=condition=available deployment/kafka -n "${NS}" --timeout=180s || true

  ensure_app_image
}

echo "==> App prerequisites"
ensure_app_prereqs

echo "==> Regenerating PrometheusRule from Sloth spec"
sloth_generate
if ! git diff --quiet -- "${RULES_FILE}"; then
  echo "ERROR: PrometheusRule drifted from Sloth spec. Re-commit the regenerated file." >&2
  git --no-pager diff -- "${RULES_FILE}" >&2
  exit 1
fi

echo "==> promtool check rules"
promtool_check

if kubectl get ns "${OBS_NS}" >/dev/null 2>&1; then
  echo "==> Dashboard ConfigMap (${OBS_NS})"
  kubectl -n "${OBS_NS}" create configmap "${APP}-grafana-dashboard" \
    --from-file="${APP}-red.json=${DASH_JSON}" \
    --dry-run=client -o yaml \
    | kubectl label --local -f - --dry-run=client -o yaml \
        grafana_dashboard=1 "app.kubernetes.io/name=${APP}" \
    | kubectl apply -f -
else
  echo "WARN: namespace ${OBS_NS} missing — skipping Grafana dashboard ConfigMap" >&2
fi

echo "==> ServiceMonitor, Deployment, SLO rules, AlertmanagerConfig"
kubectl apply -n "${NS}" -f "manifests/observability/${APP}-servicemonitor.yaml"
kubectl apply -f "manifests/observability/${APP}-deployment-patch.yaml"
apply_prometheusrule
kubectl apply -n "${NS}" -f "manifests/observability/${APP}-alertmanagerconfig.yaml"

kubectl -n "${NS}" rollout status "deployment/${APP}" --timeout=300s

echo "OK: ${APP} scraped, traced, and alarming."
