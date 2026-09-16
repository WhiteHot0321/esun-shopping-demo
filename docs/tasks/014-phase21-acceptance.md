# Phase 2.1 acceptance — LLM product support chat (2026-09-16)

## Task

Close out Phase 2.1: merge the already-implemented read-only product/FAQ AI support chat
(origin/claude/phase-2-1-ilfbgw, PR #3) onto `advanced-v2`, and give it the "full Docker suite
and real Ollama acceptance" that `docs/project-state.md` had flagged as still missing.

## Problem

`docs/project-state.md` recorded Phase 2.1 as implemented but unaccepted: the branch existed,
but nobody had run the backend suite with Docker available end-to-end, or exercised the feature
against a real Ollama model. Separately, `codex/phase21-acceptance` (a dedicated worktree at
`C:/GitHub/esun-shopping-phase21`) already had this merge half-done: conflicts against the
branch's JWT/App.vue work were resolved and staged, plus an uncommitted resilience fix to
`EmbeddingIndexRunner`, but the merge commit itself was never finished, and a
`SupportServiceTest` was still missing.

## Root Cause

Two separate issues surfaced while finishing the acceptance, both pre-existing and unrelated to
each other:

1. **Double HTML-escaping.** `SupportService.answer()` HTML-escaped the LLM's answer before
   returning it, but `SupportChat.vue` renders it through `{{ answer }}` text interpolation,
   which Vue already escapes on output. The combination double-encoded `&`, `<`, `>`, `"`, `'`
   in any answer that happened to contain them (e.g. a brand name like "H&M").
2. **MySQL seed data double-encoding.** `mysql:8.0` defaults `character_set_client` to `latin1`
   even though `character_set_server` is `utf8mb4`. `docker-entrypoint-initdb.d` runs
   `02_data.sql`/`04_faq.sql` over that latin1 connection, so every multi-byte UTF-8 Chinese
   character gets reinterpreted as Latin-1 and re-encoded — classic double-encoding. This is
   project-wide, not a Phase 2.1 regression: the long-running `esun-mysql` container (up since
   the Phase 1.5 session, used throughout Phase 1.5/2/2.5) has the exact same corrupted bytes for
   `osii 舒壓按摩椅` etc. (verified with `HEX(product_name)`). No existing test ever caught it
   because none assert on Chinese string content — only IDs, prices, quantities, HTTP status.
   It surfaced now because Phase 2.1 is the first feature whose *output* (RAG answers and
   source titles) is built from that text and shown to a user.

## Solution

- Finished the in-progress merge in the `codex/phase21-acceptance` worktree (commit `9875a6c`),
  keeping the existing conflict resolutions (`/api/support/ask` public alongside
  `/api/auth/register|login`, `04_faq.sql` loaded before `04_member.sql` in the Testcontainers
  fixture) and the already-drafted `EmbeddingIndexRunner` DB-unavailable resilience fix
  (`llm.indexing.enabled`/`LLM_INDEXING_ENABLED`, catches `DataAccessException` around startup
  indexing instead of crashing `ApplicationRunner`).
- Removed `SupportService.escapeHtml()` (commit in the merge) and added `SupportServiceTest`
  (commit `4f67a9b`) covering context assembly/source lookup, the unescaped-answer behavior, and
  the `OllamaUnavailableException` → 503 mapping.
- Added `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
  --character-set-client-handshake=FALSE` to `docker-compose.yml`'s `mysql` service and the
  equivalent `.withCommand(...)` on `AbstractMySqlIntegrationTest`'s `MySQLContainer` (commit
  `a5cc731`). `--character-set-client-handshake=FALSE` makes the server ignore whatever charset
  the (unauthenticated, init-script) client connection declares and always use the server's
  utf8mb4 — the standard fix for this mysql-image default.
- Merged `codex/phase21-acceptance` into `advanced-v2` (merge commit `6bd4c13`), re-ran the full
  backend and frontend suites against the merged tree (not just the source branch), and pushed.

### Environment note (not a code change)

Real-Ollama acceptance initially failed 100% of embedding calls ("Embedding 產生失敗", ~15ms
each — too fast to be a real network round trip). Root cause: this machine runs **two** Ollama
instances on port 11434 — a native Ollama Desktop app bound specifically to `127.0.0.1:11434`
(with unrelated models, no `llama3.1`/`nomic-embed-text`), and the `esun-ollama` Docker container
from this task's `docker-compose.yml` addition, bound to the wildcard `0.0.0.0`/`[::]:11434`
(with the correct models pulled). Windows routes traffic to `127.0.0.1` to the more specific
listener, so both `localhost` and explicit `127.0.0.1` from a plain Java client landed on the
wrong (native, model-less) instance; only `curl` happened to resolve `localhost` to `::1` and hit
the right one. This is a two-Ollama-instance collision specific to this dev machine, not a defect
in `OllamaLlmClient`/`docker-compose.yml` — a normal environment with only the compose-managed
Ollama container would not hit this. Worked around for this session's acceptance run by setting
`OLLAMA_BASE_URL=http://[::1]:11434` explicitly.

## Engineering Concept

RAG-with-a-refusal-guardrail: the system prompt explicitly instructs the model to say "don't
know" rather than invent numbers, and the live test confirmed it — asking about a nonexistent
"waterproof product" correctly returned `不知道` instead of a hallucinated answer, with the
(irrelevant) retrieved sources still shown for transparency. Provider abstraction
(`LlmClient` interface, `@ConditionalOnProperty(llm.provider)` switching between `OllamaLlmClient`
and a `ClaudeLlmClient` stub) means swapping to a real hosted model later is a config change, not
a code change.

## Test Result

- Backend (merged `advanced-v2` tree, real Testcontainers MySQL, Docker available): `mvn clean
  test` — **81/81, 0 failures/errors/skipped**, exit 0 (up from the pre-Phase-2.1 baseline of
  76/76; +2 `JwtAuthFilterTest` cases for `/api/support/ask` public-access and blank-question-400,
  +3 new `SupportServiceTest` cases). Existing four-service JaCoCo line gate (OrderService,
  OrderTransactionService, StockCacheService, ProductService, all ≥80%) unaffected —
  `SupportService`/`VectorSearchService` are outside that gate's scope, matching the pattern
  already used for other ungated services.
- Frontend (merged tree): `npm test` — checkout.test.js 3/3 + App.spec.js 9/9, all passing;
  `npm run build` clean.
- Real Ollama acceptance (manual, against a from-scratch `esun-phase21-mysql` container and the
  real `esun-ollama` Docker container with `llama3.1`/`nomic-embed-text` pulled):
  - Startup: `EmbeddingIndexRunner` indexed 13/13 docs (3 products + 10 FAQs) on first boot;
    `doc_embedding` table confirmed populated (3 product + 10 faq rows).
  - `POST /api/support/ask` with a real question ("可以開立發票嗎？付款方式有哪些？") → 200,
    answer directly grounded in the matching FAQ entries, sources returned with correct titles
    and cosine-similarity scores, top match exactly the right FAQ row.
  - Blank question → 400; a 600-character question → 400 (`@Size(max=500)`).
  - Ollama stopped mid-session → 503 with the expected message; restarted and recovered.
  - Browser UI (Vite dev server, `SupportChat.vue`): submitted "有沒有防水的商品？退貨政策是什麼？"
    (no matching product/FAQ exists) → answer correctly returned `不知道` instead of fabricating
    one, with four (irrelevant, low-similarity) sources still listed — confirms the anti-
    hallucination system prompt works end-to-end, not just in the happy path.
  - `POST /api/products/available`, `/api/auth/register`, `/api/auth/login` smoke-tested on the
    same running instance to confirm no regression from the JWT filter / App.vue changes in the
    merge — all 200 with expected payloads.
- Charset fix verified independently: recreated `esun-phase21-mysql` from scratch with the fix;
  `HEX(product_name)` now round-trips correctly, product/FAQ Chinese text renders correctly in
  both the API JSON and the browser UI (screenshots taken, not attached here).

## Trade-offs

- The charset fix is applied to `docker-compose.yml` and the Testcontainers fixture (both take
  effect on the *next* fresh container), not to the already-running `esun-mysql` container's
  existing volume — its stored Chinese text stays corrupted until someone runs `docker-compose
  down -v && docker-compose up -d` (or otherwise recreates it) to force re-seeding. Not done in
  this session: that container is shared, long-running dev/test infra other sessions may depend
  on, and wiping it wasn't this task's scope.
- `ClaudeLlmClient` remains an intentional stub (`UnsupportedOperationException` → 501); wiring a
  real Anthropic API call is out of scope for this phase, as originally planned in
  `docs/prompts/phase-2-1-llm-support-bot.md`.
- No dedicated `VectorSearchServiceTest` (e.g. asserting the cosine-similarity math in isolation)
  was added — `SupportServiceTest` covers it indirectly through mocked search results, and the
  live acceptance run exercised the real cosine-similarity ranking end-to-end. A follow-up unit
  test for that math in isolation would be a reasonable next Phase 2 testing item, not required
  to close this phase.

## Next Step

- Optional, not blocking: recreate the shared `esun-mysql` container (`docker-compose down -v &&
  docker-compose up -d`) next time a clean slate is convenient, to pick up correctly-encoded seed
  data project-wide.
- Optional: apply the same `character_set_client` fix pattern if any other ad-hoc MySQL
  container/script in this repo bypasses `docker-compose.yml`/`AbstractMySqlIntegrationTest`.
- Phase 2.2 (order lookup) and 2.3 (guided checkout) remain unscheduled per
  `docs/prompts/phase-2-1-SUMMARY.md`'s original three-phase split; 2.3 is explicitly gated on
  the deadlock work already closed out in Phase 1.5/2.5.
