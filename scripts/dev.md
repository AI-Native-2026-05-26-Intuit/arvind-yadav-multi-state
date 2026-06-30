# Live-reload dev loop

Two terminals, fast Java edit -> JVM restart cycle. Target < 3 s wall-time
between hitting save in your IDE and the new code being live on `:8080`.

This loop runs the app **on the host JVM** (via `./gradlew bootRun`) with
its backing services in the compose stack. That's simpler than a
bind-mounted dev container, restarts faster, and gives you native IDE
debugging without JDWP plumbing.

## Prerequisites

- The W5 D1 image must be built locally first:
  `docker build --build-arg APP_VERSION=0.1.0 -t uptimecrew/multistate-api:0.1.0 .`
- `envs/multistate.env` exists (copy from `envs/multistate.env.example`).
- `secrets/pg_password.txt` exists (one line, dev-only password).

## The loop

**Terminal 1 (host)** - start only the backing services from the stack
(Postgres, Mongo, Redis, Kafka). The full base stack also starts the app
container; for live-reload we want the app on the host JVM instead, so
bring up only the dependencies:

```sh
APP_VERSION=0.1.0 docker compose up -d --wait postgres mongo redis kafka
```

**Terminal 2 (host)** - run the app under Gradle's continuous mode:

```sh
SPRING_DATA_MONGODB_URI=mongodb://localhost:27017/multistate \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/multistate_dev \
./gradlew bootRun --continuous
```

(Adjust ports to match how the services are published if you publish them
to the host in `compose.override.yaml` or via the `dev` profile.)

## Edit -> live cycle

1. Edit any `.java` file.
2. Gradle picks up the change (~1 s) and re-runs `bootRun`.
3. Spring Boot DevTools restarts the JVM (~1-2 s).
4. Confirm: `curl -s localhost:8080/actuator/health | jq .status` -> `"UP"`.

Total target: well under 3 s end-to-end.

## IDE remote debug

Native debugger attaches to the `bootRun` JVM directly - no JDWP env var
needed. In IntelliJ: Run > Debug > Application (main class
`com.uptimecrew.multistate.Application`).

## Cleanup

```sh
APP_VERSION=0.1.0 docker compose down
```

Stops everything; named volumes persist so a subsequent `make up` doesn't
re-bootstrap Postgres / Mongo from scratch.

## See also

- `make smoke` - boot the full stack (including the app container), run
  three HTTP checks, tear down. CI uses the same script
  ([scripts/smoke.sh](smoke.sh)).
- `make nuke` - wipe named volumes + locally-built images (forces a
  Flyway clean slate on the next `make up`).
- [`compose.profiles.yaml`](../compose.profiles.yaml) - `test` / `e2e` /
  `observability` sidecars (Jaeger UI lands on http://localhost:16686
  once the `observability` profile is active).
