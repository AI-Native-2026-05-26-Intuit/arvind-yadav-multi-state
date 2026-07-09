#!/usr/bin/env bash
# W6 D2 Task 1 — Argo CD install + Application bootstrap for multistate-dev k3d.
set -euo pipefail

ARGOCD_VERSION="${ARGOCD_VERSION:-v2.11.7}"
GITOPS_REPO="${GITOPS_REPO:-https://github.com/AI-Native-2026-05-26-Intuit/arvind-yadav-multistate-config.git}"
GITOPS_DIR="${GITOPS_DIR:-$(cd "$(dirname "$0")/../../arvind-yadav-multistate-config" 2>/dev/null && pwd || echo "$HOME/Desktop/arvind-yadav-multistate-config")}"

echo "==> k3d cluster multistate-dev"
if ! k3d cluster list 2>/dev/null | grep -qw multistate-dev; then
  k3d cluster create multistate-dev --servers 1 --agents 2 --port "8080:80@loadbalancer"
else
  k3d cluster start multistate-dev 2>/dev/null || true
fi
kubectl config use-context k3d-multistate-dev

echo "==> Argo CD ${ARGOCD_VERSION}"
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"

echo "==> Wait for core workloads (may need imagePullPolicy=IfNotPresent on Rancher Desktop)"
kubectl -n argocd rollout status deployment/argocd-server --timeout=10m || true

if [[ -d "$GITOPS_DIR/argocd" ]]; then
  kubectl apply -f "$GITOPS_DIR/argocd/projects/multistate.yaml"
  kubectl apply -f "$GITOPS_DIR/argocd/applications/multistate-api-dev.yaml"
else
  echo "WARN: gitops dir not found at $GITOPS_DIR — push repo first, then apply CRDs manually"
fi

echo "UI: kubectl -n argocd port-forward svc/argocd-server 8080:443"
echo "Verify: kubectl -n argocd get application multistate-api-dev"
