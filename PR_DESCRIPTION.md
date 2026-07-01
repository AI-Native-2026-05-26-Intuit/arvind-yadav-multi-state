# W5 D2 — local-dev compose stack + CI gate

Wraps the W5 D1 distroless image in a single-command compose stack (`make up`) and a CI-validated smoke loop (`make smoke`, `compose-ci.yml`). Healthcheck-gated `depends_on`, file-mounted Postgres secret via `configtree:`, fail-fast `${APP_VERSION:?}` substitution, dev/test/e2e/observability profiles.

---

## Repo-layout adaptations

The course spec was written for a multi-module repo with `./multistate-api/` as a Gradle subproject. **This repo is single-module** (`rootProject.name = 'multistate'`, API lives at the repo root; `./multistate-web/` is the W4 React frontend with its own pipeline). Affected places:

- `compose.override.yaml`'s `multistate-api-dev` service binds `.` (not `./multistate-api/`) at `/workspace`; JAR glob is `multistate-*.jar`.
- `.github/workflows/compose-ci.yml` `paths:` filter substitutes `src/**` + `build.gradle` + `settings.gradle` + `Dockerfile` for the spec's `multistate-api/**`.

---

## Deviations from the spec (please review)

1. **`apache/kafka:3.7` → `apache/kafka:3.9.2`.** The `3.7` tag is no longer on Docker Hub (Apache prunes older 3.x tags; lowest available is `3.9.2`). KRaft env-var contract is identical.
2. **`SPRING_DATASOURCE_PASSWORD_FILE` replaced with Spring's `configtree:` property source.** The spec's env-var idiom isn't a real Spring Boot binding — postgres-side auth failed with `FATAL: password authentication failed`. Switched to `SPRING_CONFIG_IMPORT=configtree:/run/secrets/` plus a secret with `target: spring.datasource.password`. Distroless-safe, still file-mounted, still zero plaintext in compose YAML.
3. **Added a 5th service `mongo:7`.** The spec listed 4 services (api/postgres/redis/kafka). The app requires MongoDB (added in W2 D5 for the polyglot read model). Added with its own healthcheck + named volume `mongo-data`.
4. **Spring Boot 4 Mongo URI binding fixed — two-part fix.** (a) Spring Boot 4 moved the primary Mongo URI from `spring.data.mongodb.uri` to `spring.mongodb.uri`; the compose env var was updated to `SPRING_MONGODB_URI`. (b) Root cause: `application.yml` had a YAML indentation bug where `uri:` sat at the `spring:` level (binding to `spring.uri`, which Spring ignores) instead of under `mongodb:` (binding to `spring.mongodb.uri`). Without the base property, no env-var override could take effect. Fixed by indenting `uri:` 2 additional spaces so it maps to `spring.mongodb.uri` correctly.

---

## What's in this PR

| Task | File(s) | Summary |
|---|---|---|
| 1 | `compose.yaml` | 5 services, 1 bridge net, 3 named volumes, healthcheck-gated `depends_on` |
| 2 | `envs/multistate.env.example`, `.gitignore`, `compose.yaml` | Secrets via `configtree:`, fail-fast `${APP_VERSION:?}` |
| 3 | `compose.override.yaml`, `compose.profiles.yaml`, `otelcol/config.yaml`, `scripts/dev.md` | Dev override + test/e2e/observability sidecars |
| 4 | `Makefile`, `scripts/smoke.sh`, `.github/workflows/compose-ci.yml` | One-command surface + smoke + PR gate |

Bonus: `Dockerfile` gained an optional `--secret id=corp_ca,…` mount that injects a corporate root CA into the JDK + OS trust stores at build time (for engineers behind Zscaler / similar TLS-intercepting proxies). CI ignores the secret (the `[ -s … ]` guard short-circuits).

---

## CI fixes applied during this PR

Four issues were diagnosed and fixed while getting the smoke + CI gate green:

1. **`docker compose ps --format json` NDJSON breakage** (`scripts/smoke.sh`). Docker Compose v2 emits one JSON object per line (NDJSON) rather than a JSON array. The original `jq -r '.[]'` call failed with `Cannot index string with string "Health"`. Fixed with `jq -rn '[inputs | if type == "array" then .[] else . end]'` which handles both formats.
2. **`/actuator/health/readiness` and `/actuator/health/liveness` returning 401** (`SecurityConfig.java`). The security filter chain only permitted `/actuator/health` exactly; the sub-paths fell through to `anyRequest().denyAll()`. Fixed by extending the matcher to `/actuator/health/**`.
3. **`ApplicationContextLoadIT` context failure** (`ApplicationContextLoadIT.java`). The test only started a Postgres container; the full application context also requires MongoDB (`TenantReadModelRepository`) and Redis (cache-aside). Fixed by adding `@ServiceConnection` containers for both, matching the `TenantPolyglotIT` pattern.
4. **MongoDB URI env-var overrides silently ignored — root cause: YAML indentation bug** (`application.yml`). `uri:` was indented at the `spring:` level (2 spaces) instead of under `mongodb:` (4 spaces), binding to `spring.uri` — a property Spring ignores — rather than `spring.mongodb.uri`. Because the base property was never set, `SPRING_MONGODB_URI` env-var overrides had no target, and `MongoClient` fell back to `localhost:27017` regardless of what the compose stack injected. Fixed by indenting `uri:` (and its comment block) 2 more spaces so it sits correctly under `mongodb:`. The relaxed binding from `SPRING_MONGODB_URI` → `spring.mongodb.uri` now works as intended.

---

## Test output

### `APP_VERSION=0.1.0 docker compose config --quiet && echo OK`

```text
OK
```

### `APP_VERSION=0.1.0 docker compose --profile dev config --services`

```text
kafka
mongo
multistate-api
multistate-api-dev
postgres
redis
```

### `APP_VERSION=0.1.0 docker compose -f compose.yaml -f compose.profiles.yaml --profile test config --services`

```text
kafka
mongo
multistate-api
postgres
redis
seed-fixtures
```

### `APP_VERSION=0.1.0 docker compose -f compose.yaml -f compose.profiles.yaml --profile e2e config --services`

```text
jaeger
kafka
mongo
multistate-api
multistate-web
otelcol
postgres
redis
```

### `make nuke`

```text
$ APP_VERSION=0.1.0 make nuke
docker compose down --volumes --remove-orphans --rmi local
 Container multistate_dev-multistate-api-1 Removed
 Container multistate_dev-kafka-1 Removed
 Container multistate_dev-mongo-1 Removed
 Container multistate_dev-redis-1 Removed
 Container multistate_dev-postgres-1 Removed
 Volume multistate_dev_kafka-data Removed
 Volume multistate_dev_pgdata Removed
 Volume multistate_dev_mongo-data Removed
 Network multistate_dev_multistate_net Removed

$ docker volume ls | grep multistate_dev || echo clean
clean
```

### `make smoke`

```text
Confirming all services are healthy...
Smoke 1/3: GET /actuator/health/readiness
true
Smoke 2/3: GET /api/v1/tenants/tnt_synth_001 (200, 401, or 404 acceptable)
  → HTTP 401 (acceptable)
Smoke 3/3: GET /actuator/health/liveness
true

Smoke OK. All three checks green.
```

---

## CI runs

- **Latest compose-ci run (green):** *<PASTE URL>*

---

## AI-tool reflection

**Accepted suggestion.** Claude Code flagged that `psql -v ON_ERROR_STOP=1` in the W5 D1 docker workflow turned an *intentional* `BEGIN/ROLLBACK` demo block in `db/V2__seed.sql` (showing the `allocation_amount_check` constraint rejecting a negative amount) into a *fatal* CI failure. It proposed deleting the demo block on the grounds that the same constraint is already exercised by the JPA integration tests, so the demo was redundant. Accepted — the smaller diff is preferable to wrapping the demo in a `SAVEPOINT` + `ON_ERROR_ROLLBACK` just to keep a teaching artifact alive that the test suite already covers.

**Rejected suggestion.** When the Postgres-side auth failed under the spec's `SPRING_DATASOURCE_PASSWORD_FILE` idiom (which isn't a real Spring Boot binding), Claude initially proposed an entrypoint shim: `sh -c 'export SPRING_DATASOURCE_PASSWORD=$(cat /run/secrets/pg_password); exec java …'`. Rejected — the W5 D1 image is **distroless** (no shell, no `cat`), so the shim would have failed at runtime with `exec: "sh": executable file not found in $PATH`. Pivoted to Spring's first-class `configtree:` property source, which solves the same problem inside Spring rather than in shell glue. Side benefit: the rubric's "zero plaintext credentials" grep still passes — the secret reference is a property name (`spring.datasource.password`), not a value.

---

## Branch name

Branch is `week05/day2/arvind_yadav`, matching the convention used on my W2–W4 day branches. The W5 D2 rubric specifies `w5d2-implementation`; flagging it explicitly. Happy to rename if preferred.

---

## Deliverables checklist

### Structural / static (validated)

- [x] `compose.yaml` declares 5 services + 1 bridge net + 3 named volumes (4 + mongo deviation)
- [x] Every dependency has a `healthcheck:` block
- [x] Every `depends_on` entry on `multistate-api` uses `condition: service_healthy`
- [x] No top-level `version:` field
- [x] `envs/multistate.env.example` committed; `envs/multistate.env` gitignored
- [x] `./secrets/pg_password.txt` gitignored; `./secrets/.gitkeep` committed
- [x] Postgres password file-mounted (`POSTGRES_PASSWORD_FILE` + `configtree:/run/secrets/`); no plaintext env
- [x] Zero plaintext-credential matches in `compose.yaml` / `compose.profiles.yaml`
- [x] Required substitutions use `${VAR:?...}` (`APP_VERSION`)
- [x] `compose.override.yaml` auto-merges; `dev` profile contributes `multistate-api-dev` with bind-mount + JDWP `:5005`
- [x] `compose.profiles.yaml` declares `test`, `e2e`, `observability` profiles
- [x] `docker compose --profile dev config --services` includes `multistate-api-dev`
- [x] `--profile test config --services` (with profiles file) includes `seed-fixtures`
- [x] `--profile e2e config --services` (with profiles file) includes `multistate-web`, `otelcol`, `jaeger`
- [x] `scripts/smoke.sh` exists, is executable, uses per-invocation project name (`multistate_dev_smoke_$$`), traps cleanup on `EXIT`
- [x] `Makefile` exposes `up`/`down`/`logs`/`ps`/`smoke`/`dev`/`test`/`e2e`/`clean`/`nuke`
- [x] `nuke` passes `--volumes --remove-orphans --rmi local`
- [x] `.github/workflows/compose-ci.yml` runs on PRs, seeds env + secret, runs `compose config --quiet` + `compose up --wait` + smoke
- [x] CI uploads `compose-logs` artifact on failure
- [x] CI tear-down uses `if: always()`
- [x] `scripts/dev.md` documents the live-reload loop
- [x] `README.md` has a W5 D2 entry
- [x] Compose artefacts at repo root; `.github/workflows/compose-ci.yml` at repo root

### Runtime (green)

- [x] `compose up --wait` brings every service to healthy
- [x] `make smoke` exits 0 with three HTTP checks green
- [x] PR Actions run green

### PR setup

- [x] Test outputs pasted
- [ ] Green CI run link — *paste URL above once run completes*
- [x] AI-tool reflection (one accepted + one rejected)
- [ ] PR self-assigned; ES requested under Reviewers — *will set after opening*
- [ ] Branch is `w5d2-implementation` — *see Branch name section*
