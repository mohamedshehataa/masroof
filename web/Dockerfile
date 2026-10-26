# Web image (STORY-142): Node only in the build stage; runtime is non-root static nginx. Build from web/:
#   docker build -t masroof/web:dev web
# Build stage on the builder's architecture: the bundle is static, so linux/arm64 only emulates the runtime stage.
FROM --platform=$BUILDPLATFORM node:24.22.1-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npx vite build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
USER root
# Fixed HIGHs in brotli-libs (CVE-2026-111497) and curl/libcurl (CVE-2026-92392) until the base image digest is bumped; drop when trivy is clean without them.
RUN apk upgrade --no-cache brotli-libs curl libcurl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
