# Production frontend image: builds the Vite SPA, then serves it (and reverse-proxies
# /api and /uploads to the backend) from the same Caddy container that terminates HTTPS.
# This is the only container that publishes ports in docker-compose.prod.yml.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

# Same-origin API calls: Caddy reverse-proxies /api/* to the backend on the same domain,
# so the built SPA never needs an absolute backend URL baked in at build time.
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM caddy:2-alpine
# Pull in Alpine's patched brotli-libs (CVE-2026-111497, fixed in 1.2.1-r0) until the upstream caddy image is rebuilt;
# the Trivy gate in CI fails on fixable HIGH/CRITICAL findings. Remove once the base image ships >= 1.2.1-r0.
RUN apk upgrade --no-cache brotli-libs
COPY --from=build /app/dist /usr/share/caddy
COPY Caddyfile /etc/caddy/Caddyfile
