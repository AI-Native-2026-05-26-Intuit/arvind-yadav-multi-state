# Live-reload dev loop

Two terminals, fast Java edit → JVM restart cycle. Targets < 3 s wall-time
between hitting save in IntelliJ and the new code being live on `:8081`.

## Prerequisites

- The W5 D1 image must be built locally first:
  `APP_VERSION=0.1.0 docker build --build-arg APP_VERSION=0.1.0 -t uptimecrew/multistate-api:0.1.0 .`
- `envs/multistate.env` must exist (copy from `envs/multistate.env.example`).
- `secrets/pg_password.txt` must exist (one line, dev-only password).

## The loop

**Terminal 1 (host)** — Gradle continuous-build watcher. Re-bundles `build/libs/multistate-*.jar` whenever a `.java` file changes:

```sh
./gradlew bootJar --continuous
```

**Terminal 2 (host)** — start the dev-profile container. Bind-mounts the
repo root at `/workspace` so the freshly-rebuilt JAR is visible
immediately. JDWP listens on host `:5006`; HTTP on host `:8081`:

```sh
docker compose --profile dev up -d multistate-api-dev
docker compose --profile dev logs -f multistate-api-dev
```

## Edit → live cycle

1. Edit any `.java` file.
2. Gradle in terminal 1 picks up the change (~1 s) and rewrites the JAR.
3. Spring Boot DevTools polls the JAR (`spring.devtools.restart.poll-interval=1s`)
   and triggers a restart. Look for `Restarting` in terminal 2 logs.
4. Confirm: `curl -s localhost:8081/actuator/health | jq .status` → `"UP"`.

Total target: well under 3 s end-to-end. If it drifts above 5 s the usual
culprit is Gradle's `--no-daemon` (don't set it for this loop) or a Boot
configuration class being slow to re-initialize.

## IntelliJ remote debug

Add a Remote JVM Debug run configuration:

- Host: `localhost`
- Port: `5006`
- Module classpath: this project
- Command-line arguments (read-only): the JDWP args are already baked into
  the container's `java` invocation; IntelliJ just attaches.

Then click Debug. Breakpoints in the running container fire as soon as
the next request hits the codepath.

## Cleanup

```sh
docker compose --profile dev down
```

Postgres, Redis, and Kafka stay up unless you also `docker compose down`
on the base file — by design, since cold-starting Kafka is the slowest
part of the cycle.
