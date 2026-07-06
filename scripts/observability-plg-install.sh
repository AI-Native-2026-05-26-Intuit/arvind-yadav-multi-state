#!/usr/bin/env bash
# scripts/observability-plg-install.sh — kube-prometheus-stack + Loki + Tempo for local k3d.
#
# Prerequisites: k3d cluster "multistate", helm 3, kubectl.
# Pre-imports chart images to avoid in-cluster Docker Hub TLS failures.
set -euo pipefail

CLUSTER="${K3D_CLUSTER:-multistate}"
MON_NS="monitoring"
OBS_NS="observability"
HELM_TIMEOUT="${OBS_PLG_HELM_TIMEOUT:-20m}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

helm_repo_add() {
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
  helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
  helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null 2>&1 || true
  helm repo update
}

import_plg_images() {
  chmod +x "${REPO_ROOT}/scripts/k3d-import-images.sh"
  # Pod sandbox + admission hook — k3s cannot pull when Docker Hub TLS is intercepted (Rancher Desktop).
  "${REPO_ROOT}/scripts/k3d-import-images.sh" \
    rancher/mirrored-pause:3.6 \
    quay.io/kiwigrid/k8s-sidecar:2.8.1 \
    quay.io/prometheus-operator/prometheus-config-reloader:v0.77.2 \
    quay.io/prometheus-operator/prometheus-operator:v0.77.2 \
    quay.io/prometheus/prometheus:v2.54.1 \
    quay.io/prometheus/alertmanager:v0.27.0 \
    quay.io/prometheus/node-exporter:v1.8.2 \
    registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.13.0 \
    grafana/grafana:11.2.0 \
    docker.io/grafana/loki:2.6.1 \
    docker.io/grafana/promtail:2.9.3 \
    docker.io/grafana/tempo:2.6.1 \
    docker.io/otel/opentelemetry-collector-contrib:0.110.0
}

echo "==> Namespaces"
kubectl apply -f "${REPO_ROOT}/manifests/observability/00-monitoring-namespace.yaml"
kubectl apply -f "${REPO_ROOT}/manifests/observability/00-observability-namespace.yaml"

if k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${CLUSTER}"; then
  echo "==> Pre-importing PLG images into k3d-${CLUSTER}"
  import_plg_images
fi

helm_repo_add

# Clear half-installed releases only (not a healthy deployed stack).
if helm status kube-prometheus-stack -n "${MON_NS}" 2>/dev/null | grep -q 'pending-install'; then
  echo "==> Removing stuck kube-prometheus-stack release"
  helm uninstall kube-prometheus-stack -n "${MON_NS}" || true
  kubectl -n "${MON_NS}" delete job -l app.kubernetes.io/instance=kube-prometheus-stack --ignore-not-found
fi
if helm status loki -n "${OBS_NS}" 2>/dev/null | grep -q 'pending-install'; then
  echo "==> Removing stuck loki release"
  helm uninstall loki -n "${OBS_NS}" || true
fi
if helm status tempo -n "${OBS_NS}" 2>/dev/null | grep -q 'pending-install'; then
  echo "==> Removing stuck tempo release"
  helm uninstall tempo -n "${OBS_NS}" || true
fi

echo "==> kube-prometheus-stack (${MON_NS})"
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n "${MON_NS}" --create-namespace \
  -f "${REPO_ROOT}/manifests/observability/helm/kube-prometheus-stack-local.yaml" \
  --set grafana.sidecar.dashboards.enabled=true \
  --set grafana.sidecar.dashboards.label=grafana_dashboard \
  --set-string grafana.sidecar.dashboards.labelValue=1 \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false \
  --set prometheus.prometheusSpec.podMonitorSelectorNilUsesHelmValues=false \
  --set prometheus.prometheusSpec.ruleSelectorNilUsesHelmValues=false \
  --wait --timeout "${HELM_TIMEOUT}"

echo "==> Loki (${OBS_NS})"
helm upgrade --install loki grafana/loki-stack \
  -n "${OBS_NS}" --create-namespace \
  -f "${REPO_ROOT}/manifests/observability/helm/loki-stack-local.yaml" \
  --set grafana.enabled=false \
  --set promtail.enabled=true \
  --wait --timeout "${HELM_TIMEOUT}"

echo "==> Tempo (${OBS_NS}) — OTLP gRPC :4317"
helm upgrade --install tempo grafana/tempo \
  -n "${OBS_NS}" \
  -f "${REPO_ROOT}/manifests/observability/helm/tempo-local.yaml" \
  --set tempo.receivers.otlp.protocols.grpc.endpoint=0.0.0.0:4317 \
  --wait --timeout "${HELM_TIMEOUT}"

echo "==> OTel collector (${MON_NS}) — service otel-collector:4317"
helm upgrade --install otel-collector open-telemetry/opentelemetry-collector \
  -n "${MON_NS}" \
  -f "${REPO_ROOT}/manifests/observability/helm/otel-collector-values.yaml" \
  --wait --timeout "${HELM_TIMEOUT}"

echo ""
echo "OK: PLG-T installed."
echo "  Prometheus: kube-prometheus-stack-prometheus.${MON_NS}.svc.cluster.local:9090"
echo "  Grafana:    kube-prometheus-stack-grafana.${MON_NS}.svc.cluster.local"
echo "  Loki:       loki.${OBS_NS}.svc.cluster.local:3100"
echo "  Tempo:      tempo.${OBS_NS}.svc.cluster.local:3200 (search) / :4317 (OTLP)"
echo "  OTLP agent: otel-collector.${MON_NS}.svc.cluster.local:4317"
