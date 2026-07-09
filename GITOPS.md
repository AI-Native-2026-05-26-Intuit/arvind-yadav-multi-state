# GITOPS.md

How GitOps deploys multistate-api and what overrode the defaults.

## Repo layout

- **arvind-yadav-multi-state** — application repo. Java source, Dockerfile, `ci.yml`,
  `_build-and-push.yml`, `_bump-config.yml`. No cluster credentials after W6 D2.
- **arvind-yadav-multistate-config** — gitops config repo. `base/` holds W5 D3
  manifests; `overlays/{dev,staging,prod}/` hold Kustomize overlays.
  `argocd/projects/`, `argocd/applications/`, `argocd/applicationsets/` live here.

## Reconcile loop

1. CI builds and pushes `uptimecrew/multistate-api:<sha>` to ECR.
2. CI `_bump-config` opens a PR against `arvind-yadav-multistate-config` bumping
   `overlays/dev/kustomization.yaml` image tag.
3. Human merges the bump PR.
4. Argo CD polls the gitops repo (~3 min); detects new SHA on `main`.
5. Controller renders `overlays/dev` with Kustomize, diffs, server-side applies.
6. On sync failure or degraded health, `multistate-deploys` Slack channel alerts.

## Drift behaviour

Dev Application has `automated.selfHeal: true`. Deliberate `kubectl scale` drift
is reverted within ~3 min. Incident fixes must be committed to gitops — not
`kubectl edit`.

## Project-scoped RBAC

- **developers** — sync `multistate-api-dev` + `multistate-api-staging` only.
- **releasers** — sync all apps including prod. Neither role can delete Applications.

## Local dev notes (Rancher Desktop / k3d)

- Image pulls from registries fail with `x509: certificate signed by unknown authority`.
  Workaround: pre-import images + `imagePullPolicy: IfNotPresent` on Argo CD workloads.
- GitHub clone from Argo repo-server needs `bootstrap/argocd-repo-secret.yaml`
  (`insecure: "true"`). Use corporate CA in real installs.
- Argo CD vs Kubernetes 1.35 HPA may show `Sync: Unknown` due to
  `.status.terminatingReplicas` structured-merge diff — manifests still apply.

## preserveResourcesOnDeletion (T3)

Removing an env from the ApplicationSet list stops management but does **not**
delete workloads (`preserveResourcesOnDeletion: true`). Human review before teardown.

## argocd-author Skill audit

**Accepted:** Mandatory `resources-finalizer.argocd.argoproj.io` on Application,
ApplicationSet, and AppProject — prevents orphaned workloads on CR delete.

**Rejected:** `spec.project: default` (Skill quirk) — all apps use `multistate`;
`default` bypasses AppProject allow-lists.

**Rejected:** Scaffolded Argo Rollout CR before W6 D5 — Rollouts controller not
installed; kept Deployment until canary lesson lands.

## Delivery-path kubeconfig audit (T5)

Assignment requires a **repository-wide** search, not only the GitOps delivery
workflows:

```bash
grep -RIn 'kubeconfig\|EKS_KUBECONFIG\|aws eks update-kubeconfig' .
```

**GitOps delivery path** (`ci.yml` → `_build-and-push.yml` → `_bump-config.yml`):
**0 matches** — no cluster credentials on the post-merge deploy loop.

**Other matches (pre-existing, out of scope for W6 D2):**

| File | Purpose |
|------|---------|
| `.github/workflows/k8s-ci.yml` | W5 D3 lab — ephemeral k3d cluster for integration tests |
| `.github/workflows/observability.yml` | W5 D5 lab — k3d cluster for observability stack CI |

Those workflows spin up disposable k3d clusters in CI runners; they are unrelated
to the GitOps cutover and were not modified in W6 D2.

## What this layer does NOT do (yet)

- External Secrets Operator — W6 D3.
- Argo Rollouts canary gated on p99 SLI — W6 D5.
- Multi-cluster ApplicationSet — matrix matches one labelled cluster today.
