# CODEX → CLAUDE CORRECTION TASK

TASK_ID: 023-phase31-order-query-correction
PARENT_TASK_ID: 022-phase31-final-acceptance

## OBSERVED_DEFECT

Phase 3.1-C requires regression of the Phase 3.0 order-query experience, but the current worktree has no GET order list/detail endpoint, repository query methods, response DTO, frontend order-history view, or ownership regression tests.

## EXPECTED_BEHAVIOR

An authenticated buyer can list only their own orders and retrieve their own order detail including line items. Another buyer cannot read the order. Optional payment-status filtering and pagination must follow the Phase 3.0 specification. The browser must never select a member ID; JWT identity is authoritative.

## EVIDENCE

`rg` across `backend/src/main`, `backend/src/test`, and `frontend/src` found only `POST /api/orders`, payment form, and callback-related uses. No `GET /api/orders`, `findByMemberId`, or `findDetailsByOrderId` implementation/test exists. Task 022 therefore cannot execute the required regression.

## ALLOWED_SCOPE

- Backend order read DTO/model, `OrderRepository`, `OrderService`, `OrderController`, required query index migration, and direct tests.
- Frontend order-history/detail component, minimal App integration, and direct Vitest tests.
- Preserve Tasks 017–021 payment and product behavior; derive identity only from `authenticatedEmail`.

## ACCEPTANCE

- `GET /api/orders` returns only the authenticated member's orders, supports specified pagination and optional `payStatus` filter.
- `GET /api/orders/{orderId}` returns the authenticated member's order and line items.
- Another member receives 403; missing order receives 404; unauthenticated requests receive 401.
- Frontend shows the authenticated user's list/detail and handles loading/empty/error states.
- Direct backend and frontend tests pass without weakening existing assertions.

## REQUIRED_TEST

One focused backend command covering repository/service/controller ownership and one focused frontend Vitest command covering list/detail/error behavior.

## OUT_OF_SCOPE

ECPay changes, product management, Redis outage repair, populated migration execution, full regression, Notion, commit/push/merge, and unrelated order-status/cancellation features.
