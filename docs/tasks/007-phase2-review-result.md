# Task 007 — Phase 2 independent review result

- Reviewer: Claude Code (interactive session, not the read-only invoke-claude.ps1 launcher).
- Baseline reviewed: codex/phase2-acceptance worktree at a804d42, plus the uncommitted Phase 2
  acceptance changes described in docs/tasks/007-phase2-acceptance.md (App.vue `submitAuth`
  fix, AuthPanel/ShopWorkspace busy-state guards, App.spec.js component tests, AuthIntegrationTest,
  JwtServiceTest blank-subject case, OrderRollbackIntegrationTest hardening, CI frontend test step).
- Method: derived expected behavior from docs/tasks/007-phase2-review-request.md's requirements,
  then read code (JwtService, JwtAuthFilter, AuthController, AuthService, MemberRepository,
  GlobalExceptionHandler, RegisterRequest/LoginRequest DTOs, member table schema, App.vue,
  AuthPanel.vue, ShopWorkspace.vue, stores/auth.js, router.js) before reading the implementer's
  own notes, then executed the tests rather than trusting static review alone.

## Findings

- Static defect from a804d42 (`App.vue` catch block calling an undefined `errorMessage`) is
  fixed: it now reads `error.response?.data?.message` inline. Confirmed by reading the current
  file, not just the diff.
- JWT boundary matches the spec exactly: `JwtAuthFilter.isPublic` allows only OPTIONS, POST
  `/api/auth/register`, POST `/api/auth/login`, and GET `/api/products/available`; everything
  else needs a valid Bearer token. `JwtService` rejects blank/null subjects on both issuance and
  extraction (`IllegalArgumentException` / `JwtException`), and the new
  `JwtServiceTest.blankSubjectsCannotBeIssuedOrAccepted` covers null/empty/whitespace.
- `AuthService.register` throws 409 on duplicate email before insert; `member.email` also has a
  DB-level `UNIQUE` constraint (backend/DB/04_member.sql) as a second line of defense against a
  concurrent-registration race between the pre-check and the insert. `login` returns the same
  401/message for "no such account" and "wrong password", preventing email enumeration.
  Passwords are BCrypt-hashed before storage; `AuthIntegrationTest` asserts the stored hash is
  not the raw password and verifies it with `BCryptPasswordEncoder`.
- Frontend now uses `AuthPanel` + `ShopWorkspace` behind a single Pinia `auth` store and one
  shared axios instance (`api.js`, with request/response interceptors for the bearer token and
  401-driven logout); `router.js` wraps `App.vue`. This matches the required Phase 2 frontend
  structure.
- Double-submit is now guarded on both the auth form (`isAuthenticating` disables submit/toggle)
  and the checkout form (pre-existing `checkout.js` lifecycle), and `App.spec.js` exercises the
  busy-state, retry-after-lost-response, and definitive-409-no-retry paths that the earlier
  static-only pass could not verify.
- No blockers found. No regressions found in the existing product/checkout error and retry
  paths; `App.spec.js`'s "shop UI" suite specifically re-verifies the pre-existing checkout
  retry/idempotency behavior still works with the new auth layer in front of it.

## Test evidence (executed, not just read)

- Backend, targeted: `mvn test -Dtest=AuthIntegrationTest,JwtServiceTest,OrderRollbackIntegrationTest,JwtAuthFilterTest`
  — real MySQL Testcontainers, all four classes passed (exit 0).
- Backend, full regression on this branch: `mvn clean test` — 57 tests, 0 failures/errors/skipped
  (surefire aggregate), JaCoCo `check-core-services` gate (OrderService/ProductService >=80% line
  coverage on this branch's pom.xml, which predates the Phase 1.5/2.5
  OrderTransactionService/StockCacheService gate additions on advanced-v2) passed — build exited 0.
- Frontend: `npm ci` then `npm test` (`node --test src/checkout.test.js && vitest run`) — 3/3
  checkout unit tests and 9/9 App.spec.js component tests passed.
- Frontend: `npm run build` — production build succeeded (93 modules, no errors).

## Branch divergence check (before merge)

codex/phase2-acceptance forked from advanced-v2 at 5574f05. advanced-v2 has since advanced with
Phase 1.5/2.5 work (1188b9c..b335a0f: idempotency, Redis, StockCacheService/OrderTransactionService,
the JDBC login fix, coverage-gate pom changes). Diffed every file this branch touches
(pom.xml, OrderRollbackIntegrationTest.java, ci.yml, JwtService/JwtAuthFilter/JwtServiceTest,
App.vue and the frontend files) between 5574f05 and current advanced-v2: zero overlap — the two
branches' work is disjoint. Merging is additive with no expected conflicts beyond
package-lock.json's own regeneration noise.

## Verdict

Phase 2 acceptance criteria in docs/tasks/007-phase2-review-request.md are met. Recommend
merging codex/phase2-acceptance into advanced-v2 and re-running the full suite once on the
merged tree before closing out Phase 2.
