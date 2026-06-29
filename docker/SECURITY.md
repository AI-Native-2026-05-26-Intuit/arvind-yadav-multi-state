# Runtime image hardening — `multistate-api`

Covers the non-root user, the HEALTHCHECK approach, the no-secrets policy and
the OCI labels for the distroless runtime image
(`uptimecrew/multistate-api`). Base-image digest pinning, Trivy scan results
and CI wiring are documented in the Task 4 / Task 5 follow-ups.

## 1. Non-root (USER 65532)

The runtime stage declares `USER 65532` **before** the `ENTRYPOINT`, so the JVM
runs as the unprivileged `nonroot` user the distroless base pre-creates —
never root.

```
$ docker inspect --format '{{.Config.User}}' uptimecrew/multistate-api:0.1.0
65532
$ docker top multistate-api
PID     USER    COMMAND
31850   65532   java org.springframework.boot.loader.launch.JarLauncher ...
```

**Why `docker exec multistate-api id` does not work — and that is the point.**
The distroless runtime has no shell and no coreutils, so there is no `id`
binary:

```
$ docker exec multistate-api id
OCI runtime exec failed: exec: "id": executable file not found in $PATH
```

That is the hardening, not a regression: no shell means no `sh`/`bash` for an
attacker to pivot through, and a tiny attack surface. Non-root is instead
verified host-side with `docker top` (process UID `65532`) and
`docker inspect {{.Config.User}}`. If an environment strictly requires
`docker exec … id` to succeed, that implies a shell-bearing base (the staging
Temurin-Jammy variant, see §2) — at the cost of the distroless attack-surface
reduction and the < 250 MB size budget. We keep distroless for production.

## 2. HEALTHCHECK — static Go probe (path 1)

Distroless has no `curl`/`wget`/shell, so the HEALTHCHECK cannot be a shell
line. We chose **path 1**: a tiny, static, dependency-free Go probe
([`docker/healthcheck/main.go`](healthcheck/main.go)) compiled in a dedicated
build stage (`golang:1.23-alpine`) and copied into the image at
`/home/nonroot/healthcheck`. It GETs the health endpoint and exits 0 (2xx/3xx)
or 1 (anything else / request failed).

```dockerfile
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD ["/home/nonroot/healthcheck"]
```

```
$ docker inspect --format '{{.Config.Healthcheck.Test}}' uptimecrew/multistate-api:0.1.0
[CMD /home/nonroot/healthcheck]
$ docker inspect --format '{{.State.Health.Status}}' multistate-api
healthy        # within ~21 s
```

**Probe target = `/actuator/health` (the public aggregate).** The W3 resource
server secures the `/actuator/health/{liveness,readiness}` sub-paths (they
return `401` unauthenticated), while the aggregate `/actuator/health` is
public and reports both groups:

```
GET /actuator/health            -> 200 {"groups":["liveness","readiness"],"status":"UP"}
GET /actuator/health/readiness  -> 401 (secured)
```

A container HEALTHCHECK must be unauthenticated, so the aggregate is the
correct target. Override with the `HEALTHCHECK_URL` env var if a deployment
exposes the probe sub-paths unauthenticated (e.g. behind a Kubernetes-only
management port).

Trade-off vs path 2 (shell `HEALTHCHECK` on a Temurin-Jammy runtime): path 2
is simpler but requires a shell-bearing base, enlarging the attack surface and
image size. We accept the small extra build complexity of compiling a probe
binary to keep the runtime distroless.

## 3. No secrets baked into the image

The Dockerfile contains no credentials — grep returns zero matches:

```
$ grep -niE 'password|token|secret|api[_-]?key' Dockerfile   # 0 matches
```

All sensitive config (DB password, JWT issuer, Anthropic API key, etc.) is
read from **runtime environment** at `docker run` time via Spring's relaxed
binding / `SPRING_APPLICATION_JSON`, never `ARG`/`ENV`-baked.
`.dockerignore` keeps secret material out of the build context entirely:

```
.env        .env.*      *.pem      *.key      *.p12
secrets/    credentials.json       docker-compose.override.yml
```

Build context transferred is under 2 MB (`.git`, `build/`, `multistate-web/`
all ignored), confirming no stray secret files or large artifacts enter the
image.

## 4. OCI image labels

The runtime stage sets the standard OCI labels; `APP_VERSION` and `GIT_SHA`
build args populate `version` and `revision`:

```
org.opencontainers.image.title=multistate-api
org.opencontainers.image.version=0.1.0          # <- ARG APP_VERSION
org.opencontainers.image.revision=64029c0       # <- ARG GIT_SHA
org.opencontainers.image.source=https://github.com/uptimecrew/multistate-api
org.opencontainers.image.licenses=Apache-2.0
```

Verify on a built image:

```
$ docker inspect --format '{{json .Config.Labels}}' uptimecrew/multistate-api:0.1.0 | jq
{
  "org.opencontainers.image.licenses": "Apache-2.0",
  "org.opencontainers.image.revision": "64029c0",
  "org.opencontainers.image.source": "https://github.com/uptimecrew/multistate-api",
  "org.opencontainers.image.title": "multistate-api",
  "org.opencontainers.image.version": "0.1.0"
}
```

## 5. Operator commands

```bash
# 1. Build (version + git sha populate the OCI labels).
docker build \
  --build-arg APP_VERSION=0.1.0 \
  --build-arg GIT_SHA=$(git rev-parse --short HEAD) \
  -t uptimecrew/multistate-api:0.1.0 .

# 2. Run (non-root inside, host port 8080, resource caps).
docker run -d --name multistate-api \
  --memory=512m --cpus=1.0 -p 8080:8080 \
  uptimecrew/multistate-api:0.1.0

# 3. Confirm the hardening.
docker inspect --format '{{.Config.User}}' uptimecrew/multistate-api:0.1.0
docker inspect --format '{{.Config.Healthcheck.Test}}' uptimecrew/multistate-api:0.1.0
# Wait ~30 s for the start-period to elapse:
docker inspect --format '{{.State.Health.Status}}' multistate-api  # -> healthy
```
