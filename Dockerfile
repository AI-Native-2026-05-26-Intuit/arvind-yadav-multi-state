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
# NOTE: base images are referenced by tag here. Task 4 pins each FROM to a real
# @sha256 digest for reproducibility.

# -------- 1. BUILD STAGE --------
# Full JDK + Gradle. Discarded after bootJar is produced. The project pins a
# Java 17 toolchain; the Foojay resolver (settings.gradle) fetches JDK 17 inside
# this JDK 21 image when no matching toolchain is already installed.
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

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
FROM eclipse-temurin:21-jre-jammy AS extractor
WORKDIR /extract
# Project artifact is multistate-<version>.jar (rootProject.name = 'multistate'),
# copied out of the builder's build cache mount to /staging.
COPY --from=builder /staging/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination .

# -------- 3. RUNTIME STAGE --------
# Distroless JRE. No shell, no package manager, no user-management tools.
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime

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

EXPOSE 8080

# Distroless image has no shell - ENTRYPOINT MUST be exec form.
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
