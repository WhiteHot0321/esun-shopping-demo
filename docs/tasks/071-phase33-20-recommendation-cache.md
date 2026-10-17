# 071 — Phase 3.3 #20: short-TTL cache for the public recommendation endpoint

Date: 2026-10-17 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Owner decision: follow the recommended option (cache), see 062.

## Decision and contract change

`GET /api/products/{id}/recommendations` is unauthenticated and costs ~0.4-0.55 s of DB CPU per call (062). `PublicRecommendationCache` (in-process, no new dependency) now fronts it.

- **Contract change vs #15:** a response may be up to `recommendation.cache-ttl-seconds` old (default 30; `0` disables). Each cached entry is still one REPEATABLE_READ snapshot; new orders, restocks and price changes show after expiry.
- Single-flight per `(productId, limit)`: a burst at expiry makes one DB call.
- Failures (404 unknown product, 400) are never cached; invalid `limit` bypasses the cache; the map is capped at 2000 entries (expired entries evicted first, otherwise requests fall through uncached).
- The personalised `/api/recommendations` is never cached.
- Cache is per backend instance (the production stack runs one).

## Not done (deliberate)

- **Rate limiting** was recommended as a complement but not implemented: the backend has no trusted-proxy / forwarded-header configuration, so behind Caddy every request has the same remote address and a per-IP limit would be either useless or a global throttle. Needs Caddy-level limiting (rate-limit plugin) or `server.forward-headers-strategy` with a trusted proxy; left as a follow-up.
- Roll-up table and time window were not chosen.

## Verification

- New `PublicRecommendationCacheTest` (6 cases: hit inside TTL, expiry and key independence, failures not cached, TTL 0 disables, 8-thread concurrent miss loads once, invalid limit bypass).
- `RecommendationIntegrationTest` clears the cache per call (it asserts live data); `OpenApiDocsTest` mocks the new bean.
- Targeted run `PublicRecommendationCacheTest, RecommendationServiceTest, RecommendationIntegrationTest, OpenApiDocsTest`: exit 0. Full `mvn clean test`: **314/314**, 0 failures/errors/skips, BUILD SUCCESS (JaCoCo gates run as part of the build).
- Not measured: actual latency/CPU under load with the cache; no independent review.
