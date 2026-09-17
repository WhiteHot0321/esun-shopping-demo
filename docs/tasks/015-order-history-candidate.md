# Task 015 (candidate, not yet scheduled) — order history view

Recorded: 2026-09-17 Asia/Taipei, Claude Code (MODE: read-only investigation, no implementation).

## Problem

After placing an order, the user has no way to look it up again. Confirmed by reading
current code, not assumed:

- Backend: `OrderController` (`backend/src/main/java/com/esun/shop/controller/OrderController.java`)
  exposes only `POST /api/orders`. No `GET` endpoint exists for a single order or a
  member's order list.
- `OrderRepository` (`backend/src/main/java/com/esun/shop/repository/OrderRepository.java`)
  only has write methods (`insertOrder`, `insertOrderDetail`, `claimRequest`,
  `findRequestById` for idempotency replay). `shop_order` stores `member_id` per order,
  but nothing ever reads it back.
- Frontend: `ShopWorkspace.vue`'s `submitOrder` posts to `/orders` and shows the new
  `orderId` in a one-shot success toast (`ShopWorkspace.vue:108`). There is no order-list
  page or component; the id is gone once the toast dismisses.

## Proposed scope (for whoever picks this up)

- `GET /api/orders` (current member's orders, paginated) + `GET /api/orders/{orderId}`
  (single order detail, reject/404 if it does not belong to the authenticated member).
- Repository query methods joining `shop_order` + `order_detail`.
- Frontend: an order-history view/route showing past orders (status, items, total,
  created time), reachable from the shell after login.
- Auth: must scope by the JWT-authenticated member id (Phase 2's JWT/member work is
  already in place per `docs/project-state.md`), not just `member_id` passed by the
  client.

## Not done in this session

No code was written. This is a backlog entry only — actual implementation should follow
the normal risk-based routing in `AGENTS.md` (this touches auth-scoped data access, so
treat query scoping as a correctness-sensitive change worth independent review, not a
trivial CRUD add).
