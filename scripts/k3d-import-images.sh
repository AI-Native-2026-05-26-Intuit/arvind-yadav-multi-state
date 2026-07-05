#!/usr/bin/env bash
# scripts/k3d-import-images.sh — pull on host, import into k3d via tarball (TLS-safe).
set -euo pipefail

CLUSTER="${K3D_CLUSTER:-multistate}"

if ! k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${CLUSTER}"; then
  echo "ERROR: k3d cluster '${CLUSTER}' not found" >&2
  exit 1
fi

for img in "$@"; do
  tar="/tmp/k3d-import-$(echo "${img}" | tr '/:' '__').tar"
  echo "==> ${img} → k3d-${CLUSTER}"
  docker pull "${img}"
  docker save "${img}" -o "${tar}"
  k3d image import "${tar}" -c "${CLUSTER}"
  rm -f "${tar}"
done
