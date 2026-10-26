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
# Apply Alpine's published security fixes on top of the caddy:2-alpine base (so far brotli-libs CVE-2026-111497 and
# curl/libcurl CVE-2026-92392): the CI Trivy gate fails on any fixable HIGH/CRITICAL finding, and the upstream image
# lags behind Alpine's package repository. Remove once the base image is rebuilt and the gate passes without it.
RUN apk upgrade --no-cache
COPY --from=build /app/dist /usr/share/caddy
COPY Caddyfile /etc/caddy/Caddyfile
