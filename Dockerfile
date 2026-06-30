# syntax=docker/dockerfile:1.7
#
# Three-stage build for multistate-api: builder + extractor + distroless runtime.
# The repo root IS the multistate-api module, so the build context is ".".
#
# Build:
#   docker build \
#     --build-arg APP_VERSION=0.1.0 \
#     --build-arg GIT_SHA=$(git rev-parse --short HEAD) \
#     -t uptimecrew/multistate-api:0.1.0 .
#
# NOTE: all three runtime-lineage base images are pinned by digest (@sha256:…)
# below, not by tag. A tag is mutable - the same `:21-jre-jammy` reference can
# change bytes across days. A digest is content-addressed and immutable.
# Resolve a digest with: docker pull <ref> && docker images --digests <repo>.
# Refresh on the first business day of each month, or immediately if Trivy
# reports a newly-discovered HIGH/CRITICAL on a current digest.

# -------- 1. BUILD STAGE --------
# Full JDK + Gradle. Discarded after bootJar is produced. The project pins a
# Java 17 toolchain; the Foojay resolver (settings.gradle) fetches JDK 17 inside
# this JDK 21 image when no matching toolchain is already installed.
FROM eclipse-temurin:21-jdk-jammy@sha256:801b7e1a9c4befaf82bf9a2a58025ef43a7694bbc84779187ad0524d84742772 AS builder
WORKDIR /workspace

# Optional corporate CA injection. Engineers behind a TLS-intercepting proxy
# (Zscaler / Bluecoat / Netskope / …) pass --secret id=corp_ca,src=~/zscaler-root.pem
# at build time. CI doesn't sit behind such a proxy, so the secret mount is
# absent there and this RUN is a no-op (the `[ -s … ]` guard short-circuits).
# Trust store updates apply to BOTH the OS (for curl / apt) AND the JDK
# (for Gradle's HTTPS resolution against services.gradle.org and Maven Central).
RUN --mount=type=secret,id=corp_ca,target=/tmp/corp_ca.pem \
    if [ -s /tmp/corp_ca.pem ]; then \
      install -m 0644 /tmp/corp_ca.pem /usr/local/share/ca-certificates/corp.crt && \
      update-ca-certificates && \
      keytool -importcert -noprompt -alias corp-proxy \
              -file /tmp/corp_ca.pem \
              -keystore "$JAVA_HOME/lib/security/cacerts" \
              -storepass changeit ; \
    fi

# Cache wrapper + build files first (least-changing). Order matters:
# wrapper/gradle dir, then build files, THEN dependency pre-warm, THEN src.
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./

# Pre-warm the Gradle dep cache (no source code yet). The BuildKit cache mount
# keeps ~/.gradle (resolved deps + provisioned toolchain) across builds on top
# of the normal layer cache.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon dependencies

# Now copy source. Code changes invalidate only this layer and everything below.
COPY src/ src/

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    --mount=type=cache,target=/workspace/.gradle,sharing=locked \
    --mount=type=cache,target=/workspace/build,sharing=locked \
    ./gradlew --no-daemon --build-cache --configuration-cache bootJar -x test \
 && mkdir -p /staging \
 && cp build/libs/multistate-*.jar /staging/app.jar
# Three cache mounts make a code-only rebuild fast:
#   /root/.gradle      - resolved dependencies + the global build cache
#   /workspace/.gradle - per-project execution history + configuration cache
#                        (drives incremental compilation and up-to-date checks)
#   /workspace/build   - compile outputs / incremental-compile snapshot
# Together a one-file change recompiles just that file instead of the whole tree.
# A cache mount is NOT part of the image layer, so the bootJar is copied out to
# /staging (a real layer path) for the extractor stage to read.

# -------- 2. EXTRACT STAGE --------
# Run `layertools extract` on the bootJar. Tiny JRE only.
FROM eclipse-temurin:21-jre-jammy@sha256:199aebeb3adcde4910695cdebfe782ada38dadb6cc8013159b58d3724451befd AS extractor
WORKDIR /extract
# Project artifact is multistate-<version>.jar (rootProject.name = 'multistate'),
# copied out of the builder's build cache mount to /staging.
COPY --from=builder /staging/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination .

# -------- 2b. HEALTHCHECK PROBE STAGE --------
# Distroless has no shell/curl/wget, so the HEALTHCHECK cannot be a shell line.
# Compile a tiny static, dependency-free Go probe (stdlib only) that GETs the
# readiness endpoint and exits 0/1. Discarded after the binary is produced.
FROM golang:1.25-alpine AS healthcheck
WORKDIR /src
# Optional corporate CA hook (same as the builder stage). Alpine uses
# /usr/local/share/ca-certificates + update-ca-certificates from the
# ca-certificates package. ca-certificates is left unpinned because (a)
# it is the only package installed, (b) only inside the opt-in corp-CA
# branch, and (c) pinning would break the build on every Alpine patch
# without improving reproducibility (parent image's apk index is itself
# unpinned).
# hadolint ignore=DL3018
RUN --mount=type=secret,id=corp_ca,target=/tmp/corp_ca.pem \
    if [ -s /tmp/corp_ca.pem ]; then \
      apk add --no-cache ca-certificates && \
      install -m 0644 /tmp/corp_ca.pem /usr/local/share/ca-certificates/corp.crt && \
      update-ca-certificates ; \
    fi
COPY docker/healthcheck/go.mod ./
COPY docker/healthcheck/main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /healthcheck .

# -------- 3. RUNTIME STAGE --------
# Distroless JRE. No shell, no package manager, no user-management tools.
FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d AS runtime

ARG APP_VERSION=0.0.0
ARG GIT_SHA=unset
LABEL org.opencontainers.image.title="multistate-api"
LABEL org.opencontainers.image.version="${APP_VERSION}"
LABEL org.opencontainers.image.revision="${GIT_SHA}"
LABEL org.opencontainers.image.source="https://github.com/uptimecrew/multistate-api"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Distroless :nonroot pre-creates UID 65532. Explicit USER for clarity.
USER 65532
WORKDIR /home/nonroot

# Copy Spring Boot layered JAR dirs least-to-most-changing for optimal caching.
COPY --from=extractor /extract/dependencies/          ./
COPY --from=extractor /extract/spring-boot-loader/    ./
COPY --from=extractor /extract/snapshot-dependencies/ ./
COPY --from=extractor /extract/application/           ./

# Static Go health probe (world-executable 0755 from `go build`), used by the
# HEALTHCHECK below since distroless has no shell/curl/wget.
COPY --from=healthcheck /healthcheck /home/nonroot/healthcheck

EXPOSE 8080

# Exec-form CMD (no shell in distroless). A success during start-period flips
# the container to "healthy" immediately; failures during it don't count as
# unhealthy.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD ["/home/nonroot/healthcheck"]

# Distroless image has no shell - ENTRYPOINT MUST be exec form.
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
