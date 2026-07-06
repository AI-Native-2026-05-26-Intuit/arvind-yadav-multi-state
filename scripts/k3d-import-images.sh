#!/usr/bin/env bash
# scripts/k3d-import-images.sh — pull on host, load into every k3d node's containerd.
#
# k3d image import often fails on Rancher Desktop (digest not found). Piping
# docker save → ctr images import inside each node is reliable.
set -euo pipefail

CLUSTER="${K3D_CLUSTER:-multistate}"

if ! k3d cluster list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "${CLUSTER}"; then
  echo "ERROR: k3d cluster '${CLUSTER}' not found" >&2
  exit 1
fi

cluster_nodes() {
  docker ps --format '{{.Names}}' \
    | grep "^k3d-${CLUSTER}-" \
    | grep -E '(server|agent)-' \
    | sort
}

# Normalise refs for loose ctr listing match (multi-arch manifests list oddly).
image_match_keys() {
  local ref="$1"
  ref="${ref%%@*}"
  local path="${ref#*://}"   # strip optional scheme if ever present
  path="${path#docker.io/}"
  path="${path#quay.io/}"
  path="${path#registry.k8s.io/}"
  path="${path#ghcr.io/}"
  local repo="${path%%:*}"
  local tag="${path#*:}"
  local base="${repo##*/}"
  printf '%s\n%s\n%s:%s\n' "${ref}" "${repo}" "${base}" "${base}:${tag}"
}

image_present_in_node() {
  local node="$1"
  local ref="$2"
  local listing key
  listing="$(docker exec "${node}" ctr -n k8s.io images ls -q 2>/dev/null || true)"
  while IFS= read -r key; do
    [ -n "${key}" ] || continue
    if echo "${listing}" | grep -Fq "${key}"; then
      return 0
    fi
  done < <(image_match_keys "${ref}")
  return 1
}

import_one() {
  local img="$1"
  local node tar
  tar="$(mktemp "${TMPDIR:-/tmp}/k3d-ctr-import-${CLUSTER}.XXXXXX")"
  trap 'rm -f "${tar}"' RETURN

  echo "==> ${img} → k3d-${CLUSTER} (ctr import on all nodes)"
  if docker image inspect "${img}" >/dev/null 2>&1; then
    echo "    using local image (skip pull)"
  else
    docker pull "${img}"
  fi
  docker save -o "${tar}" "${img}"

  while read -r node; do
    [ -n "${node}" ] || continue
    echo "    loading into ${node}"
    docker cp "${tar}" "${node}:/tmp/k3d-import.tar"
    docker exec "${node}" ctr -n k8s.io images import /tmp/k3d-import.tar
    docker exec "${node}" rm -f /tmp/k3d-import.tar
    if ! image_present_in_node "${node}" "${img}"; then
      echo "WARN: ${img} not listed in ${node}; showing ctr images:" >&2
      docker exec "${node}" ctr -n k8s.io images ls -q 2>/dev/null | grep -i "${img##*/}" || true
      echo "ERROR: ${img} not visible in ${node} after ctr import" >&2
      exit 1
    fi
  done < <(cluster_nodes)
}

for img in "$@"; do
  import_one "${img}"
done
