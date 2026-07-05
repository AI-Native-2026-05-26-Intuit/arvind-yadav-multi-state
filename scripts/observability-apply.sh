#!/usr/bin/env bash
# scripts/observability-apply.sh — one-shot apply of the W5 D5 observability layer.
set -euo pipefail

NS="multistate-dev"
MON_NS="${MON_NS:-monitoring}"
OBS_NS="${OBS_NS:-observability}"
APP="multistate-api"
TAG="${TAG:-0.1.1}"
IMAGE="uptimecrew/multistate-api:${TAG}"
CLUSTER="${K3D_CLUSTER:-multistate}"
SLOTH_SPEC="slo/multistate-api.sloth.yaml"
RULES_FILE="manifests/observability/${APP}-prometheusrule.yaml"
DASH_JSON=".grafana/dashboards/${APP}-red.json"
DEPLOY_PATCH="/tmp/${APP}-deployment-${TAG}.yaml"
SLOTH_IMAGE="${SLOTH_IMAGE:-ghcr.io/slok/sloth:v0.11.0}"
PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.54.0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SLOTH_REGEN="sloth-out/$(basename "${RULES_FILE}")"

sloth_generate() {
  mkdir -p sloth-out
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "${PWD}:/work" -w /work "${SLOTH_IMAGE}" \
    generate -i "${SLOTH_SPEC}" -o "${SLOTH_REGEN}"
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
  if k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${CLUSTER}"; then
    echo "==> Importing ${IMAGE} into k3d-${CLUSTER}"
    "${SCRIPT_DIR}/k3d-import-images.sh" "${IMAGE}"
  fi
}

ensure_app_prereqs() {
  echo "==> Ensuring namespace, secret, backing services, and ConfigMap"
  kubectl apply -f manifests/00-namespace.yaml
  kubectl apply -f manifests/observability/00-monitoring-namespace.yaml
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

  if k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${CLUSTER}"; then
    echo "==> Pre-importing backing-service images into k3d (avoids in-cluster TLS pulls)"
    "${SCRIPT_DIR}/k3d-import-images.sh" \
      postgres:16 redis:7 mongo:7 apache/kafka:3.9.2
  fi

  kubectl apply -f .github/k8s/ci-backing-services.yaml
  kubectl apply -f manifests/30-multistate-api.configmap.yaml
  kubectl apply -f manifests/20-multistate-api.service.yaml

  kubectl patch configmap multistate-api-config -n "${NS}" --type merge -p '{
    "data": {
      "SPRING_MONGODB_URI": "mongodb://mongo.multistate-dev.svc.cluster.local:27017/statetrack",
      "OTEL_SDK_DISABLED": "false",
      "SPRING_KAFKA_LISTENER_AUTO_STARTUP": "false",
      "MANAGEMENT_HEALTH_KAFKA_ENABLED": "false"
    }
  }' || true

  kubectl wait --for=condition=available deployment/postgres -n "${NS}" --timeout=300s
  kubectl wait --for=condition=available deployment/redis -n "${NS}" --timeout=180s
  kubectl wait --for=condition=ready pod -l app=mongo -n "${NS}" --timeout=300s
  kubectl wait --for=condition=available deployment/kafka -n "${NS}" --timeout=300s || true

  ensure_app_image
}

apply_dashboard() {
  if kubectl get ns "${MON_NS}" >/dev/null 2>&1; then
    echo "==> Dashboard ConfigMap (${MON_NS})"
    kubectl -n "${MON_NS}" create configmap "${APP}-grafana-dashboard" \
      --from-file="${APP}-red.json=${DASH_JSON}" \
      --dry-run=client -o yaml \
      | kubectl label --local -f - --dry-run=client -o yaml \
          grafana_dashboard=1 "app.kubernetes.io/name=${APP}" \
      | kubectl apply -f -
  else
    echo "WARN: namespace ${MON_NS} missing — run ./scripts/observability-plg-install.sh first" >&2
  fi
}

apply_deployment_patch() {
  sed "s|uptimecrew/multistate-api:0.1.1|${IMAGE}|g" \
    "manifests/observability/${APP}-deployment-patch.yaml" > "${DEPLOY_PATCH}"
  kubectl apply -f "${DEPLOY_PATCH}"
}

echo "==> App prerequisites"
ensure_app_prereqs

echo "==> Regenerating PrometheusRule from Sloth spec"
sloth_generate
if ! diff -q "${RULES_FILE}" "${SLOTH_REGEN}" >/dev/null 2>&1; then
  echo "ERROR: PrometheusRule drifted from Sloth spec. Re-commit the regenerated file." >&2
  diff -u "${RULES_FILE}" "${SLOTH_REGEN}" >&2
  exit 1
fi

echo "==> promtool check rules"
promtool_check

apply_dashboard

echo "==> ServiceMonitor, Deployment, SLO rules, AlertmanagerConfig"
kubectl apply -n "${NS}" -f "manifests/observability/${APP}-servicemonitor.yaml"
apply_deployment_patch
apply_prometheusrule
kubectl apply -n "${NS}" -f "manifests/observability/${APP}-alertmanagerconfig.yaml"

kubectl -n "${NS}" rollout status "deployment/${APP}" --timeout=600s

echo "OK: ${APP} scraped, traced, and alarming."
echo "Next: ./scripts/observability-plg-install.sh (if PLG not installed), then OBS_SMOKE_REQUIRE_PLG=true ./scripts/observability-smoke.sh"
