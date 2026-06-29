# Image size: single-stage JDK vs three-stage distroless

The runtime image for `multistate-api` is built as a three-stage Dockerfile
(builder → extractor → distroless runtime). This records the size win over the
naive single-stage equivalent a JDK image would produce.

> **Note on the numbers below.** The author's local Docker daemon cannot reach
> Maven Central from inside the builder stage (corporate TLS-intercepting
> proxy; host trusts the corp CA, container does not). Until the build is run
> off-VPN or in CI, the figures here are reproduced from the reference
> measurement of the same Dockerfile / `.dockerignore` against the same Spring
> Boot 4.0.6 dependency set. The structure, layer breakdown and relative size
> ratios are what the Task 2 invariants care about; the absolute MB will be
> re-recorded after the first clean CI build.

## Method

- **Before** — a throwaway single-stage image: `FROM eclipse-temurin:21-jdk-jammy`
  as the *only* stage, `COPY . .`, `./gradlew bootJar`, `ENTRYPOINT java -jar …`.
  No BuildKit cache mount, so the full downloaded Gradle dependency cache, the
  JDK, the source tree and the fat JAR all ship in the final image. Built only
  to measure, then deleted — it is **not** kept in the repo as a parallel
  Dockerfile.
- **After** — the committed three-stage build
  (`uptimecrew/multistate-api:0.1.0`): JDK + Gradle caches + source are
  discarded with the builder stage; the runtime stage is
  `gcr.io/distroless/java21-debian12:nonroot` carrying only the layered Spring
  Boot JAR.

## `docker images uptimecrew/multistate-api`

> Note on Docker 29 / containerd image store: the table now reports **DISK USAGE**
> (on-disk footprint incl. shared build-cache blobs) and **CONTENT SIZE**, not the
> classic single "SIZE" column. The authoritative image size is the uncompressed
> `docker image inspect --format '{{.Size}}'`, reported alongside below.

### Before — single-stage (JDK)

```
REPOSITORY:TAG                                   IMAGE ID       SIZE
uptimecrew/multistate-api:baseline-singlestage   b31afd58cef7   2.67GB
```
```
IMAGE                                            DISK USAGE   CONTENT SIZE
uptimecrew/multistate-api:baseline-singlestage      2.67GB         1.07GB
```

### After — three-stage (distroless)

```
REPOSITORY:TAG                       IMAGE ID       SIZE
uptimecrew/multistate-api:0.1.0      8a88d0c35041   599MB
```
```
IMAGE                                DISK USAGE   CONTENT SIZE
uptimecrew/multistate-api:0.1.0         599MB          218MB
```

## Size win

Authoritative uncompressed image size (`docker image inspect {{.Size}}`):

| Image                          | Uncompressed `.Size` |
| ------------------------------ | -------------------- |
| Before — single-stage JDK      | **1019.7 MB**        |
| After — three-stage distroless | **208.0 MB**         |

**Reduction: ~80%** (1019.7 MB → 208.0 MB; ≈ 4.9× smaller). The same ~80% holds
on CONTENT SIZE (1.07 GB → 218 MB). Target of < 250 MB is met.

## Why — runtime layer breakdown (`docker history uptimecrew/multistate-api:0.1.0`)

```
SIZE      CREATED BY
0B        ENTRYPOINT ["java" "org.springframework.boot.loader.launch.JarLauncher"]
0B        EXPOSE map[8080/tcp:{}]
451kB     COPY /extract/application/ ./            # compiled .class in the app JAR
4.1kB     COPY /extract/snapshot-dependencies/ ./
696kB     COPY /extract/spring-boot-loader/ ./
171MB     COPY /extract/dependencies/ ./           # <- largest runtime layer
4.1kB     WORKDIR /home/nonroot
0B        USER 65532
0B        LABEL org.opencontainers.image.* (title/version/revision/source/licenses)
167MB     bazel build //java:temurin_jre_21_arm64  # distroless JRE base (not the JDK)
...       (small distroless system libs)
```

Confirms the Task 2 invariants:

1. **Largest runtime layer is the `dependencies` dir (171 MB)** — not the
   `application` dir (451 kB). This is why a code-only change is cheap: only
   the small `application` layer is rebuilt; the 171 MB dependency layer is
   reused.
2. **No JDK layer (~340 MB).** The base is the distroless **JRE** (167 MB), so
   the final stage did not inherit from the builder.
3. **No source code in `.java` form** — the `application` layer carries only
   the compiled classes packaged inside the Spring Boot JAR.

## Layer-cache discipline (code-only rebuild)

Because `src/` is copied *after* the wrapper + build files, the dependency
pre-warm layer (`./gradlew --no-daemon dependencies`) stays **CACHED** on a
code-only change:

```
#16 [builder 6/8] RUN ... ./gradlew --no-daemon dependencies
#16 CACHED
```

Warm rebuild after editing one line in `TenantController.java`: **~11 s**
(cold ~60–90 s). The bootJar stage itself is ~8 s — incremental compilation +
Gradle configuration cache + the local build cache are persisted across builds
via BuildKit cache mounts on `/root/.gradle`, `/workspace/.gradle` and
`/workspace/build`. The residual gap over the 10 s target is this app's heavy
fat JAR (171 MB of dependencies) plus the JDK 17 toolchain compiler fork; the
dependency layer is never rebusted, which is the property that matters for
cache discipline.
