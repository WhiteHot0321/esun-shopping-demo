# Phase 3.1 #10 — 賣家商品管理後台 [Seller]

## Session gate

- Task ID: Phase 3.1 #10
- Baseline: `advanced-v2` @ `103b75a63c6ebb314fba4e3d9d28dd91b5c8b989`
- Class / risk: medium implementation; authorization and ownership are critical and require independent review
- Goal: integrate seller-owned product listing, creation, editing, soft deletion/filtering, and atomic restocking into the current `advanced-v2` tree without regressing product reviews.
- Implementer: Codex
- Reviewer: Claude Code read-only audit after targeted verification
- Allowed scope: product schema/migration/stored procedures, product controller/service/repository/DTO/model, seller product-management UI and focused tests, direct workspace wiring, this task ledger, project state, and required Notion status pages.
- Explicit exclusions: payment/order behavior changes and unrelated refactors. The first slice (2026-09-20) also excluded image upload and bulk operations; the canonical Notion prompt requires them, so they were completed on 2026-09-21 and those exclusions no longer apply.
- Change limit: user-approved expansion beyond the initial 3-file limit, bounded to the files necessary to port and reconcile historical commit `3fceaee` with the current review-enabled product model.
- Verification: focused backend product tests; focused frontend product-management tests; frontend production build; one final backend clean suite; `git diff --check`; independent read-only authorization/ownership audit.
- Repair limit: one automatic repair round unless a concrete blocker requires a new decision.

## Acceptance

- SELLER can list and manage only products owned by the authenticated JWT principal.
- ADMIN behavior is explicit and does not silently bypass ownership unless the API contract says so.
- BUYER cannot access seller write endpoints.
- Create, update, soft-delete, and restock validate inputs and surface missing, forbidden, deleted, and concurrent-change cases with appropriate HTTP semantics.
- Public catalog, ordering, indexing, and review behavior do not expose or transact against soft-deleted products.
- Restock is an atomic database increment rather than a read-modify-write sequence.
- Frontend never sends an ownership identifier; it derives access from the authenticated session.

## Result

Status: **PASS — canonical Phase 3.1 #10 acceptance is complete (first slice 2026-09-20 Codex; remaining slice 2026-09-21 Claude Code).**

### First slice (Codex, 2026-09-20)

- Implemented seller/admin-gated `/api/admin/products` list/create/read/update/soft-delete/restock APIs. Identity is taken only from the verified JWT email; ADMIN follows the same ownership rule and has no implicit cross-owner bypass.
- Added `deleted_at`, a repeatable existing-database migration (`10_product_management.sql`), owner-scoped conditional writes, atomic `quantity = quantity + ?` restock, and soft-delete filters for public catalog, order lookup, stock snapshots, stored procedures, and AI indexing.
- `ProductManagement.vue` replaced the standalone create form in the seller workspace.
- Codex audit of that slice: PASS (BUYER denial, SELLER/ADMIN gate, server-derived ownership, cross-seller denial, owner-aware conditional writes, atomic restock, soft-delete exclusion, frontend role gating).

### Remaining canonical scope (Claude Code, 2026-09-21)

- **Search + pagination:** `GET /api/{admin,seller}/products/search?keyword&status&page&size`. Always scoped by `creator_id = ?` (JWT principal); keyword matches product id/name case-insensitively with `%`, `_` and `\` escaped (`ESCAPE`); status whitelist `all|active|deleted`; `page >= 0`, `1 <= size <= 100`, `page*size` overflow rejected (400).
- **Image upload:** `POST /api/{admin,seller}/products/{id}/images` (multipart part `images`, 1-5 files per request, max 10 per product, 5 MB each, container limit 5 MB / 25 MB). Server-generated `UUID.ext` filenames only (the client filename never touches a path), content-type + extension + magic-byte check (JPEG/PNG/WEBP), `normalize().startsWith(root)` guard, partial-write and batch cleanup, DB metadata in `product_image` (ordered by `display_order`), rows and files rolled back together on failure. Public serving at `/uploads/products/{uuid}.{jpg|png|webp}` only; the JWT filter exposes exactly that pattern (GET) and nothing else under `/uploads`.
- **Bulk operations:** `POST /api/{admin,seller}/products/bulk` `{productIds, action: DELETE|RESTOCK, amount}`. 1-100 unique ids; one transaction does the ownership/active count check plus the conditional UPDATE and rolls back the whole batch on any mismatch (403 for foreign/missing/deleted, 409 for concurrent change). Vector-index side effects run only after the transaction commits.
- **Frontend:** search box, active/deleted filter, server pagination, checkbox bulk delete/restock (with confirmation), per-row multi-file upload, thumbnails resolved against the API origin, and a multipart `Content-Type` override (the axios instance default is JSON, which would serialize `FormData` and drop the files).
- **Defects found and fixed during this work:** the JWT filter returned 401 for public product images; unescaped LIKE wildcards made `%` match every product; missing static resource / bad request parameter / oversized upload were all reported as HTTP 500 (now 404 / 400 / 413).

### Independent review (Claude Code Explore agent, read-only, 2026-09-21)

Verdict **PASS** on all six rules (role gate, JWT-derived ownership, all-or-nothing bulk, upload safety, public serving surface, owner-scoped parameterized search). It reported seven implementation defects, all repaired in one round: D1 upload DB rows not rolled back on later failure (`@Transactional`); D2 index side effects ran before commit (moved after the transaction; a naive `afterCommit` hook was rejected because writes made there are not committed); D3 orphan file when `transferTo` fails midway; D4 static-resource location missing trailing `/` on a fresh deployment; D5 `page*size` int overflow and type-mismatch 500; D6 oversized upload 500 (now 413); D7 unbounded restock amount (capped at 1,000,000). A 10-image-per-product cap was added as hardening. Left as documented follow-ups (not defects): no image-delete endpoint / orphan-file sweeper, no per-seller upload rate limit, no `X-Content-Type-Options: nosniff` header, single-product endpoints distinguish 404/403 (bulk is uniformly 403), pre-existing HTML-escaping of names versus Vue escaping.

### Verification (final, after repairs)

- Backend `mvn -q clean test`: **137/137 PASS**, 0 failures/errors/skips, JaCoCo gate PASS; `ProductService` 108/110 lines (98.2%), `ProductImageStorageService` 47/48. New tests: `ProductImageStorageServiceTest` (7: traversal filenames, type/extension/magic-byte forgery, size, count, batch and partial-write cleanup), `ProductServiceTest` (search, bulk ownership/atomicity, restock cap, overflow, image cap, upload compensation), `AdminProductControllerTest` (role gating + principal for search/bulk/upload), real-MySQL `ProductManagementIntegrationTest` (3: owner-scoped paging/wildcard/status; bulk all-or-nothing with foreign/missing/deleted ids; upload persistence + static serving + token-authenticated traversal/directory/missing-file 404 + cross-seller/buyer denial).
- Frontend: checkout 3/3, Vitest **35/35**, production build PASS; `git diff --check` PASS.
- Not run: live browser end-to-end against a running backend + MySQL (covered instead by the real-MySQL MockMvc integration test and the mocked-API component tests).
- Repair rounds: 1 (audit findings D1-D7) plus 2 in-flight corrections during implementation (JWT public images; LIKE escape).
- Engineering concept: UI role hiding is convenience only; multitenant safety comes from server-derived identity plus owner predicates repeated inside the database write. File uploads add a second rule: never let client-supplied names or claimed types decide a path or a file's trust. Generate names, verify bytes, and make filesystem and database changes compensate each other.

## Independent review request

Perform a read-only authorization and data-consistency audit. Derive expected behavior from the Acceptance section before reading implementation details. Inspect only these core files plus directly referenced DTO/model methods when necessary: `ProductController.java`, `ProductService.java`, `ProductRepository.java`, `JwtAuthFilter.java`, `ProductManagement.vue`, and the focused controller/service tests. Do not edit files or run commands/tests.

Report exactly `PASS` or `FAIL`, followed by concise evidence. Check especially: BUYER denial; SELLER/ADMIN role gate; JWT-derived ownership with no client owner field; cross-seller write denial; soft-deleted products excluded from catalog/order/index paths; atomic restock; and whether the frontend exposes management only to seller/admin roles. Treat the full-suite Docker failure as separate runtime evidence, not automatically as a code defect.
