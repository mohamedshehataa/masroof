# Backend image (STORY-143, ADR-0004). From the repo root:
#   docker build -f backend/Dockerfile --build-arg GIT_SHA=$(git rev-parse HEAD | cut -c1-7) .
# The build stage runs on the builder's own architecture (--platform=$BUILDPLATFORM): the jar is architecture-
# independent, so a linux/arm64 image only emulates the small runtime stage (make build-images PLATFORMS=...).
FROM --platform=$BUILDPLATFORM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build
ARG GIT_SHA
RUN echo "${GIT_SHA}" | grep -Eqx '[0-9a-f]{7,40}' || { echo "ERROR: build arg GIT_SHA must match ^[0-9a-f]{7,40}\$ (got '${GIT_SHA}'); pass --build-arg GIT_SHA=\$(git rev-parse HEAD | cut -c1-7)" >&2; exit 1; }
WORKDIR /src/backend
COPY contracts/openapi.yaml /src/contracts/openapi.yaml
COPY backend/ ./
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon --console=plain -q bootJar -PgitSha="${GIT_SHA}" -PbuildTime="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

FROM eclipse-temurin:24-jre-alpine-3.22@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f
# Fixed HIGH in brotli-libs (CVE-2026-111497) until the base image digest is bumped; drop when trivy is clean without it.
RUN apk upgrade --no-cache brotli-libs
RUN addgroup -S -g 10001 masroof && adduser -S -D -H -u 10001 -G masroof masroof
WORKDIR /app
COPY --from=build /src/backend/build/libs/masroof-backend.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
