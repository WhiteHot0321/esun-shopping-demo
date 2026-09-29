# 063 — Phase 3.3 #21 SEC-01: repository supply-chain gate

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Scope source: `056` (SEC-01, gap G5/G6).

## What was added

- `.github/workflows/ci.yml`:
  - `supply-chain-gate` job (Trivy v0.74.0 via `aquasecurity/setup-trivy`, **pinned to a commit SHA**): a self-test that the
    gate still rejects the deliberately vulnerable fixture `.github/security-fixtures/vulnerable-npm`, then `trivy fs` for
    vulnerabilities + secrets, failing on fixable HIGH/CRITICAL. `backend/pom.xml` is skipped there (see Findings); backend
    dependencies are gated through the image scan.
  - `frontend-build`: `npm audit --omit=dev --audit-level=high` (runtime dependencies only; vite/vitest tooling is build-time).
  - `docker-build`: now needs the gate, loads the built image and scans it (`trivy image`, blocking) before anything can be published.
  - `publish-image`: `sbom: true` and `provenance: mode=max` attach BuildKit attestations to the pushed manifest, then the exact
    pushed digest is scanned again, a CycloneDX SBOM is generated for that digest and kept 90 days as `sbom-<digest>`, and the
    digest is written to the job summary.
- `.trivyignore.yaml`: the only way to accept a finding; entries must state why, an owner and an `expired_at` date. Currently empty.
- `backend/Dockerfile`: `mvn dependency:go-offline` in its own layer so a source-only change no longer re-downloads every dependency
  (Maven Central answered repeated full downloads with HTTP 429 during this work).

## Findings the gate produced, and what was done

1. **Frontend runtime dependencies:** axios 1.14.0 (10 HIGH), form-data 4.0.5, nanoid 3.3.11 (3), postcss 8.5.8 (2), all with fixed
   versions inside the declared semver ranges. `npm update` moved them to axios 1.20.0, form-data 4.0.6, nanoid 3.3.19, postcss 8.5.28
   (lockfile only); `npm audit --omit=dev` now reports 0. Vitest 83/83 and the production build pass. Three findings remain in dev-only
   tooling (2 moderate, 1 high) and are outside the gate by design.
2. **Backend image: 41 fixable HIGH/CRITICAL findings on Spring Boot 3.3.5** (Tomcat 10.1.31 with 7 CRITICAL, Netty CRITICAL, Spring
   Framework/Security/Data HIGH, Jackson, Micrometer). Spring Boot was upgraded to **3.5.16** (springdoc 2.6.0 → 2.8.17), which left 6;
   those were closed by overriding three managed versions in `pom.xml`: `tomcat.version` 10.1.60, `jackson-bom.version` 2.21.6,
   `netty.version` 4.1.137.Final. The image scan is now 0 for the OS layer, the jar and the bundled `pebble` binary.
3. **Trivy and `pom.xml`:** scanning the Maven manifest makes Trivy resolve BOMs from Maven Central, which rate-limited this machine
   (429, `Retry-After` 1800 s). The jar inside the image carries the same information without network resolution, so the manifest is skipped.

## Verification (2026-09-29)

- Gate self-test locally: the fixture scan exits 1 (lodash 4.17.15, four HIGH findings).
- Real tree, before fixes: `trivy fs` exit 1 (frontend), `trivy image` 41 findings. After fixes: `trivy fs` exit 0 (0 vulnerabilities, secret scan
  clean), `trivy image` exit 0.
- Backend after the upgrade: `mvn -B clean test` **303/303 PASS**, 0 failures/errors/skips, JaCoCo "All coverage checks have been met"
  (run after the version overrides; an earlier run on plain 3.5.16 was also 303/303).
- `actionlint` on the workflow: no findings. The workflow itself was not run locally; its first real run is the push that contains it.

## Not covered

- Behaviour of the upgraded stack was tested by the automated suite only; no browser/E2E pass on Spring Boot 3.5 and no k6 rerun.
- The published-digest scan, SBOM upload and BuildKit attestations execute only on a push to `advanced-v2`/`main`/`v*`; they were linted, not run
  locally. Whether GHCR's package settings allow reading the attestations was not checked.
- Trivy's vulnerability database changes daily, so a green run today can turn red tomorrow without any code change; that is the intended behaviour
  but it needs an owner (exceptions need an expiry, see `.trivyignore.yaml`).
- The `tomcat.version`/`jackson-bom.version`/`netty.version` overrides must be removed once the Boot BOM catches up; nothing enforces that.
- Dev-only npm tooling findings (3) and GitHub Actions other than `setup-trivy` are not SHA-pinned (Dependabot covers Actions updates).
- No independent review.
