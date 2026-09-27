# Task 037 — Phase 3.2 #15 推薦系統 [Tier 3]

- Baseline: `advanced-v2` @ `ccf62b9`, branch `feature/phase32-15-recommendation`
- Risk: **Medium** — new read-only endpoints plus one index. No write path, no lock, nothing on the checkout/stock/payment path (019 §: recommendations stay Medium unless they touch order amounts).
- Original implementer: Claude Code (Sonnet 5), original self-review only. Snapshot follow-up: Codex implementation; Claude Code independent snapshot review PASS (2026-09-26), not a new full-feature/frontend review.
- Scope: item-based "customers who bought this also bought" (public) and a personalised "for you" list (JWT), both derived from order history. Out of scope: browse/click tracking, embedding or ML similarity, seller-facing analytics, email campaigns, caching, A/B ranking.

## Requirements

1. `GET /api/products/{id}/recommendations?limit=` — public, aggregate-only. Unknown or soft-deleted anchor → 404. `limit` 1–20 (default 6), otherwise 400.
2. `GET /api/recommendations?limit=` — requires a JWT (401 otherwise); the member is the verified token identity, no member id is accepted from the request.
3. Ranking is three tiers concatenated and de-duplicated, each item labelled with its strongest tier: `CO_PURCHASE` (bought in the same live order as the anchor / the member's purchased products), then `POPULAR` (most distinct live orders), then `NEW_ARRIVAL` (filler so a fresh shop still shows something). Order inside a tier is fully deterministic: score DESC, product id ASC.
4. Only live orders count (`order_status <> 'CANCELLED'`); candidates are sellable (not soft-deleted, `quantity > 0`) **in the request's first consistent-read snapshot**. User decision (2026-09-26): concurrent sell-out/soft-delete commits do not change an in-flight response; the next request sees them. Recommendations are not stock reservations. This supersedes the original contradictory promise that a product sold out between ranking and payload reads immediately drops out.
5. Privacy: a signal needs at least `recommendation.min-support` (default 2) distinct **buyers** (changed from distinct orders on 2026-09-27, see "RECOMMENDATION-SUPPORT-BUYERS"), so a public list cannot reveal one buyer's basket, even one repeated across several orders.
6. Personalised list excludes everything the member has bought (live orders) and everything already in their cart.
7. Frontend: a "為你推薦" strip for signed-in members, and a "買過「X」的人也買了" strip under the review panel of the product being viewed; "加入購物車" reuses the cart quantity path (stock clamping, server cart sync). Strips reload after each catalogue reload (checkout, stock change); a stale slower response never overwrites a newer one; malformed payloads render nothing.
8. Migration `15_recommendation.sql` is repeatable and only adds `idx_order_detail_product_order (product_id, order_id)`; `01_schema.sql` creates it for fresh installs.

## Design decisions and trade-offs

- **SQL co-occurrence, not ML.** A self-join of `order_detail` on `order_id` counted with `COUNT(DISTINCT order_id)` is exact, explainable ("2 orders") and needs no model, training job or new store. The `(product_id, order_id)` index turns the first hop into an index-only lookup.
- **Distinct orders, not quantities.** One bulk buyer cannot dominate a pair's score.
- **Read-only + one read-only transaction.** Both endpoint service methods explicitly request `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`. Ordinary SELECTs share the first consistent-read snapshot without taking product row locks. This contract covers controller-initiated calls, not hypothetical callers joining an existing outer transaction with different isolation.
- **Tier fallback instead of an empty list.** Cold start is the normal state of a young shop; showing an honest `NEW_ARRIVAL`/`POPULAR` label is better than a blank panel, and the label tells the user which signal they are looking at.
- **`min-support` as privacy, not just quality.** With support 1 the public list would expose a single customer's basket. Trade-off: on sparse data the co-purchase tier is empty and tiers 2–3 carry the list.
- **Personal list learns from the member's purchases, not their views.** No tracking table means no new PII and no write on every page view; browsing signals are the natural next step.
- **No cache.** The queries are bounded by `limit` and indexed; a cache would need invalidation on every order/stock change. Revisit with a measured latency problem.

## Verification (2026-09-24)

See "Results" in the handoff for the final counts. Coverage highlights:

- `RecommendationIntegrationTest` (real MySQL via Testcontainers, 6 cases): live-only counting, ordering and tie-break, min-support, sold-out/soft-deleted exclusion, cancel and stock changes reflected, cold-start fallback, 404/400, 401, identity from the token only (a `memberId` parameter is ignored), cart/purchased exclusion, migration run twice.
- `RecommendationServiceTest` (currently 7 cases): limit validation, 404, tier order/de-dup/labels, later tiers asked only for the remainder and never for taken ids, defensive missing-payload handling (not evidence of concurrent stock refresh), member anchors/exclusions.

## RECOMMENDATION-SNAPSHOT session gate — 2026-09-26

- MODE: IMPLEMENT; user selected consistent snapshot. Baseline advanced-v2 @ 176b190; existing unrelated edits preserved.
- Scope: RecommendationService annotations/comment, RecommendationServiceTest misleading test name, RecommendationIntegrationTest real interleaving cases; task/state/Notion records. No checkout, inventory mutation logic, schema, frontend, commit/push.
- Risk: transaction/isolation contract. Implementer Codex; independent reviewer Claude Code PASS. Acceptance: public/member HTTP requests retain pre-commit ranking and payload; separate writer really commits stock=0 and soft-delete before payload read; next request excludes both products. Same request must be a read-only RR transaction.
- Verification: one targeted recommendation Maven command initially; one narrow repair/reverification permitted if necessary. No full regression or coverage claim. Test synchronization uses a repository spy boundary only; returned data and SQL remain real.

### Snapshot verification evidence

- `mvn '-Dtest=RecommendationIntegrationTest,RecommendationServiceTest' '-Djacoco.skip=true' test`: **15/15 PASS**,
  0 failures/errors/skipped, exit 0, 44.592 s. Integration 8/8 (6 existing + public/member snapshot cases); service 7/7.
- The request executes through MockMvc and the real Spring service proxy. At the pre-payload boundary the test checks
  an active read-only RR transaction, opens a distinct MySQL connection (connection IDs differ), commits quantity=0 for B
  and soft-deletion for C, then calls the real payload method. Current result remains B/C/H with B quantity 5;
  committed DB state is checked outside the finished request, and the next request contains H but not B/C.
- No sleep, mocked SQL/results, or test-created outer read transaction. The spy only locates the interleaving boundary.
  Stock/deletion are direct test-fixture updates, not simulated checkout; no claim of checkout coverage.
- `git diff --check` PASS. No full suite, JaCoCo gate, frontend or browser rerun (bounded read-only recommendation change).
- Test command count 1, implementation round 1, repair rounds 0. Independent snapshot review PASS; no commit/push.
- Independent read-only review via `scripts/invoke-claude.ps1`, contract `docs/tasks/037-snapshot-review-request.md`;
  log `.git/codex-claude-runs/cd673a3a-7138-4488-bfe4-c8fa9c101f53/result.json`. Requirements/source inspected before
  execution evidence. No actionable blocker found. Reviewer did not rerun tests or review the controller layer; Codex's
  real HTTP tests exercised both controller paths. This is not a full frontend/feature audit.
- Reviewer telemetry: 72.231 s, 10 turns, reported USD 0.1701908. These are reviewer-only figures, not whole-task totals.
- User intervention: initial explicit selection of snapshot semantics; no mid-run intervention. Elapsed whole-turn time,
  Codex context/token/cost/five-hour usage delta unknown. Known test runtime recorded above. No new source repair required.
- Completion: chosen snapshot contract implemented, tested and independently reviewed. Follow-up only if user requests
  commit/push or a separately scoped broader acceptance. Snapshot consistency is not a promise of current stock/reservation.
- Closure status check observed additional concurrent work outside this task: CorsConfig, application.yml, production
  validator/profile/config tests. Those files were not modified or reviewed here; the 15-test result is evidence for the
  recommendation tree at test execution, not verification of these later production-configuration edits. Preserved untouched.
- esun-shopping-notion synchronization completed with readback: progress, historical prompt stage-work status/acceptance,
  execution strategy, Phase 3.2, Phase 3 index, #15 task page and execution plan. Snapshot follow-up marked tested/reviewed,
  uncommitted; no whole-project completion claim.
- Mutation check: replacing the cancelled-order filter with `1 = 1` made 3 of the 6 integration cases fail; restored.
- Frontend: `RecommendationStrip.spec.js` (7), `App.spec.js` (+2: personal strip + add-to-cart, guest has no personal request + per-product strip). Two existing assertions that counted *all* GETs now exclude `/recommendations`.

## RECOMMENDATION-PAYLOAD — 2026-09-27 07:46 Asia/Taipei

- MODE: IMPLEMENT; small direct fix, baseline branch feature/phase33-18-b1-prod-hardening @ 58d1b07 (corrected 2026-09-27: this commit is not on advanced-v2; advanced-v2 contains 3509923). Implementer/self-check: Codex; no independent review required for this bounded UI validation change.
- Scope/gate: RecommendationStrip.vue and RecommendationStrip.spec.js only, plus existing task/state and Notion records; two targeted test commands, one implementation round. No backend, cart workflow, dependency, build configuration, commit/push or broad audit changes. Pre-existing changes preserved.
- Card contract (price boundary corrected at 08:11): nonblank string ID/name, finite numeric price >= 0.01, positive safe-integer quantity; optional rating is finite and within 0–5, optional reviewCount is a nonnegative safe integer. Null/missing rating fields retain zero defaults. Unknown recommendation reasons retain the generic label; unused score is not validated. This is display validation, not inventory reservation or checkout authority.
- Tests first: `npm run test:components -- src/components/RecommendationStrip.spec.js` exited 1 with 8 passing / 2 failing, reproducing malformed cards and null-response error display.
- After fix: `npm run test:components -- src/components/RecommendationStrip.spec.js src/App.spec.js` exited 0, **40/40 PASS** (10 component, 30 App), 1.69 s. Covers invalid fields/types/non-finite values, mixed payloads, no invalid add buttons, valid add event, boundary/default values, unknown reason fallback, null response, existing request race protection and App recommendation integration.
- Static diff self-check and `git diff --check` PASS. Backend/full regression/coverage/build/browser E2E not executed (local UI-only scope); no independent-review claim for this follow-up. Snapshot review above applies only to snapshot changes.
- Measurements: two test rounds (RED/GREEN), zero repair rounds after implementation, one previously reviewed defect resolved; initial user implementation authorization, no mid-run intervention. Whole-turn elapsed/context/token/cost/five-hour delta unknown; measured test runtime above. No scope expansion into another module.
- esun-shopping-notion governs targeted progress synchronization; local state and existing online status/acceptance rows updated, preserving historical evidence. No whole-phase completion claim. Next action only if requested: commit the reviewed/scoped changes or separately bound the remaining audit.

## RECOMMENDATION-SUPPORT-BUYERS — 2026-09-27

- MODE: IMPLEMENT, Claude Code, user-authorized after a review finding. Branch feature/phase33-18-b1-prod-hardening @ 4b1b30d (working tree with earlier uncommitted #15 edits preserved); not on advanced-v2.
- Defect (found by code reading, then reproduced): the minimum-support floor used `COUNT(DISTINCT order_id)`. One buyer placing the same basket in two live orders satisfied `min-support` = 2, so the public per-product endpoint could reveal that buyer's basket, and one account could inflate rankings.
- Test first: new `RecommendationIntegrationTest.oneBuyerRepeatingTheSameBasketNeverReachesMinimumSupport` (two live orders by one buyer must yield neither CO_PURCHASE nor POPULAR; a second distinct buyer then yields CO_PURCHASE with score 2). RED reproduced: 1 failure at the first assertion.
- Fix: `RecommendationRepository.coPurchased` and `popular` now count and filter on `COUNT(DISTINCT o.member_id)` (`shop_order.member_id` is NOT NULL, so no order is skipped). `score` therefore means number of buyers. No schema, service, controller or frontend change.
- Verification: `mvn -Dtest=RecommendationIntegrationTest,RecommendationServiceTest -Djacoco.skip=true test` exit 0, integration 9/9 (8 existing + 1 new), service 7/7, 0 failures/errors/skips. Existing fixtures already used distinct buyers per live order, so no existing expectation changed. `git diff --check` PASS.
- Not run: full backend suite/JaCoCo, frontend, browser, independent review. Author self-check only; uncommitted.
- Also corrected in this change: the "advanced-v2 @ 58d1b07" baseline wording above and in `docs/project-state.md`.
- Engineering note: a privacy threshold must count the unit you are protecting (people), not an event that one person can repeat (orders).

## Known follow-ups (not blocking)

### RECOMMENDATION-PRICE-MIN correction — 2026-09-27 08:11 Asia/Taipei

- User explicitly authorized MODE: IMPLEMENT. Small task, branch feature/phase33-18-b1-prod-hardening @ 58d1b07 (corrected 2026-09-27: not on advanced-v2); Codex implementation/self-check. Allowed: RecommendationStrip.vue/spec.js plus existing progress records; at most two targeted test commands, one fix round. No backend/cart-flow/full regression/commit/push. All pre-existing edits preserved.
- Prior review found price 0.001 accepted and rendered NT$0.00 although backend DTO minimum is 0.01. Changed the comparison to >= 0.01; extended invalid fixtures with 0.001 and 0.009 and asserted that the 0.01 card emits its ID on add.
- `npm run test:components -- src/components/RecommendationStrip.spec.js`: before production fix exit 1, 9 pass/1 fail; after fix exit 0, **10/10 PASS**, 723 ms. `git diff --check` PASS. Source/test self-check confirms exact boundary and unchanged unrelated behavior. Previous 40-test evidence is historical, not a rerun on this correction.
- Status: implemented/tested/self-checked, not independently reviewed or committed. No backend/full suite/build/browser checks (isolated UI predicate). One valid review defect resolved; two test executions, one correction round, no failed repair; one initial user authorization, no mid-run intervention. Whole-task elapsed/context/model-effort/token/cost/usage delta unknown.
- Engineering note: validation must compare against the business minimum before formatting; a positive sub-cent value can still display as zero.
- esun-shopping-notion: synchronize existing status/acceptance cells and related pages with readback. The broad through-Phase-3.2 audit is not declared complete.

### Original nonblocking follow-ups

- No rate limit on the public per-product endpoint (each call is an indexed self-join bounded by `limit`).
- The personal query passes the member's purchased ids as an `IN` list; unbounded only in theory (bounded by catalogue size).
- No browse/click signals, no seller-specific or category-aware ranking, no time decay (all-time counts).
- Original delivery had no independent audit; the 2026-09-26 snapshot contract follow-up has independent Claude review PASS.
