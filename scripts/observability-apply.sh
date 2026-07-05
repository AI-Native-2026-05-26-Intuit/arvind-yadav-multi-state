#!/usr/bin/env bash
# scripts/observability-apply.sh — one-shot apply of the W5 D5 observability layer.
set -euo pipefail

NS="multistate-dev"
OBS_NS="${OBS_NS:-observability}"
APP="multistate-api"
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

kubectl get ns "${OBS_NS}" >/dev/null

echo "==> Regenerating PrometheusRule from Sloth spec"
sloth_generate
if ! git diff --quiet -- "${RULES_FILE}"; then
  echo "ERROR: PrometheusRule drifted from Sloth spec. Re-commit the regenerated file." >&2
  git --no-pager diff -- "${RULES_FILE}" >&2
  exit 1
fi

echo "==> promtool check rules"
promtool_check

echo "==> Dashboard ConfigMap"
kubectl -n "${OBS_NS}" create configmap "${APP}-grafana-dashboard" \
  --from-file="${APP}-red.json=${DASH_JSON}" \
  --dry-run=client -o yaml \
  | kubectl label --local -f - --dry-run=client -o yaml \
      grafana_dashboard=1 "app.kubernetes.io/name=${APP}" \
  | kubectl apply -f -

echo "==> ServiceMonitor, Deployment, SLO rules, AlertmanagerConfig"
kubectl apply -n "${NS}" -f "manifests/observability/${APP}-servicemonitor.yaml"
kubectl apply -f "manifests/observability/${APP}-deployment-patch.yaml"
apply_prometheusrule
kubectl apply -n "${NS}" -f "manifests/observability/${APP}-alertmanagerconfig.yaml"

kubectl -n "${NS}" rollout status "deployment/${APP}" --timeout=180s

echo "OK: ${APP} scraped, traced, and alarming."
