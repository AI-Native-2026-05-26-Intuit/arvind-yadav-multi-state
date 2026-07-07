# Pipeline reference

Repo-layout note: in this repo the multistate-api module **is** the repository root
(`gradlew`, `src/`, `Dockerfile` live here; `multistate-web/` has its own CI).
The course spec paths use a `multistate-api/` prefix — mapped to the repo root below.

## On every PR to `main`

1. **`build-test`** (`ci.yml`) runs `./gradlew build` on `ubuntu-24.04` with JDK 21
   (Temurin) via the `setup-build` composite action.
2. Test reports upload only on failure (`build/reports/tests/`, 7-day retention).
3. **`call-build-and-push` is skipped** on PRs (`if:` limits it to `push` + `main`).
4. Branch protection requires the **`build-test`** status check before merge.

## On merge to `main`

1. **`build-test`** re-runs against the merged SHA.
2. **`call-build-and-push`** invokes `_build-and-push.yml`, which:
   - runs in the **`dev`** GitHub Environment (no required reviewers),
   - assumes OIDC role
     `arn:aws:iam::279566174801:role/multistate-api-build-push`
     (no long-lived `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` anywhere in CI YAML),
   - logs in to ECR,
   - builds the image with Buildx + GHA cache,
   - runs Trivy (`HIGH,CRITICAL`, `.trivyignore` waivers),
   - pushes `uptimecrew/multistate-api:<git-sha>` and
     `uptimecrew/multistate-api:main` (immutable SHA tag for prod promotion;
     `main` tag for dev convenience).
3. W6 D3 will wire the kubectl rollout into the `dev` job.

## To deploy to prod

1. GitHub UI → **Actions** → **`deploy-prod`** → **Run workflow**.
2. Paste the image SHA confirmed in ECR (`aws ecr describe-images …`).
3. A **required reviewer** approves the run (GitHub **`prod`** environment gate).
4. The workflow assumes
   `arn:aws:iam::279566174801:role/multistate-api-prod-deploy`,
   confirms the image exists in ECR, then runs a placeholder rollout step.
   W6 D3 lands the real `kubectl apply` / rollout.

## GitHub Environments

| Environment | Protection | Used by |
|-------------|------------|---------|
| **`dev`** | No required reviewers; no branch restriction beyond `main` push path | `_build-and-push.yml` (`environment: dev`) |
| **`prod`** | Required reviewers (you + ES); deployment branches = `main` only; wait timer = 0 | `deploy-prod.yml` (`environment: prod`) |

Environment UI settings are **not** in version control — screenshot the `prod`
protection rules for the PR description.

## OIDC IAM roles

| Role | Trust `sub` claim | Inline policy (today) |
|------|-------------------|------------------------|
| `multistate-api-build-push` | `repo:AI-Native-2026-05-26-Intuit/arvind-yadav-multi-state:environment:dev` **and** `…:ref:refs/heads/main` | ECR push to `uptimecrew/multistate-api` (`infra/oidc/ecr-push-policy.json`) |
| `multistate-api-prod-deploy` | `repo:AI-Native-2026-05-26-Intuit/arvind-yadav-multi-state:environment:prod` **only** | `ecr:DescribeImages` on `uptimecrew/multistate-api` (`infra/oidc/ecr-describe-prod-policy.json`) |

Trust policy JSON lives under `infra/oidc/` for reproducibility.

## Why every action is SHA-pinned

`@v4` resolves to whatever bytes the maintainer most recently tagged. If that
tag is compromised, every workflow re-runs with the malicious code on its next
trigger. Pinning to a 40-char commit SHA freezes the bytes; Dependabot
(`.github/dependabot.yml`) opens grouped weekly PRs when upstream tags advance,
and the `# v<version>` comment next to each SHA tells reviewers which release
they are looking at. The October 2024 `tj-actions/changed-files` supply-chain
attack is the canonical reason this is non-optional.

## `github-actions-author` skill audit (W6 D2 Task 4)

The `/github-actions-author multistate-api` Claude Skill was **not available** in
this environment (no skill file installed). Manual review decisions documented
here instead:

| Topic | Skill tendency | Our choice | Rationale |
|-------|----------------|------------|-----------|
| Action pins | Sometimes `@v4` without SHA | Full SHA + `# vX.Y.Z` comment on every `uses:` | Done-when grep requires zero floating tags |
| Gradle cache | May add redundant `actions/cache@v4` | **Removed / never added** — `setup-java` `cache: gradle` in `setup-build` is sufficient | One cache path, fewer moving parts |
| Trivy version | May pick latest tag | **v0.36.0** SHA everywhere (`docker.yml`, `_build-and-push.yml`) | Matches existing `.trivyignore` waivers tested on PR docker workflow |
| `configure-aws-credentials` | Various v4 SHAs | **e3dd6a429…** (`# v4.0.2`) via `git ls-remote` | Canonical annotated-tag commit for `v4.0.2` |
| Repo paths | `multistate-api/` subdir | **Repo root** `.` context / `infra/oidc/` | This capstone repo layout (see top of this doc) |

## What this pipeline does NOT do (yet)

- `kubectl apply` against EKS — W6 D3.
- `sam deploy` for the LLM Lambda — W6 D4.
- Argo CD GitOps — W6 D2 (manifest-repo commit replaces push-pattern deploy).
- SLSA provenance / cosign signing — Week 7 security day.
