# Task 020 — Phase 3.1 商品所有權後端

## Session gate

- Baseline commit: `ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287`
- Task class: medium / critical authorization and database consistency
- Goal: 完成商品建立者所有權、軟刪除、管理 CRUD 與補庫存 API，所有身分均由 JWT principal 取得。
- Affected module: `backend`
- Allowed changes: 商品 schema/migration、Product model/DTO/repository/service/controller、安全路由與直接相關測試；本文件結果區。
- Change limit: 以完成上述垂直切片所需的最小檔案為限，不碰付款、訂單與前端。
- Target verification: 商品 repository/service/controller 的單一聚焦 Maven 測試命令；最多一次修復重跑。
- Explicit exclusions: 前端商品管理、ECPay、訂單查詢、Notion、commit/push/merge、無關重構。

## Acceptance criteria

- 建立商品時以已驗證使用者身分保存 `creator_id`，不接受客戶端指定擁有者。
- `POST /api/admin/products`、`PUT /api/admin/products/{id}`、`DELETE /api/admin/products/{id}` 與 `POST /api/admin/products/{id}/restock?amount=...` 均須驗證身分。
- 僅商品擁有者可修改、軟刪除或補庫存；他人操作回應 403，不洩漏可寫能力。
- 軟刪除保留商品與庫存資料列，公開可購買清單排除已刪除商品。
- 價格最小 `0.01`、庫存與補貨量驗證明確；補庫存具資料庫一致性保障。
- 舊資料／seed 商品的 creator migration 行為明確且可重複執行。
- 直接相關測試通過，並留下實際命令與結果。

## Result

- Status: **PASS — implemented, focused verification passed, and independent authorization/database review passed.**
- Implementer: Terra worker
- Reviewer: Codex/Claude independent read-only review after targeted verification
- Changed (uncommitted): `backend/DB/01_schema.sql`, `backend/DB/03_stored_procedures.sql`, `backend/DB/06_product_ownership.sql`, `Product`, `CreateProductRequest`, new `UpdateProductRequest`, `ProductRepository`, `ProductService`, `ProductController`, `ProductServiceTest`, `JwtAuthFilterTest`, and new `AdminProductControllerTest`.
- Ownership and migration: `creator_id` is written only from the controller's `authenticatedEmail`; client DTOs contain no owner field. The base schema defaults old seed rows to the non-loginable `legacy` principal. The additive migration uses `ADD COLUMN IF NOT EXISTS`, backfills null/blank values to `legacy`, then enforces NOT NULL, so it can be rerun without changing valid owners.
- Admin API: authenticated routes are `POST/GET /api/admin/products`, `GET/PUT/DELETE /api/admin/products/{id}`, and `POST /api/admin/products/{id}/restock?amount=...`. The existing protected `POST /api/products` remains a compatibility route but applies the exact same JWT-principal creator rule. The JWT filter already protects every non-public `/api/**` route, including all admin endpoints.
- Consistency: update/delete/restock first confirm active ownership, return 403 for another active owner, and issue a second SQL update constrained by `(product_id, creator_id, deleted_at IS NULL)`. Restock is `quantity = quantity + ?` in one statement, preventing a lost increment. Soft deletion only sets `deleted_at`; public listing, batch order lookup, stock cache snapshots, indexing and stored-procedure availability/decrement all exclude deleted rows.
- Validation: creation/update price minimum is `0.01`; create quantity is `@Min(0)`; controller and service both reject a restock amount <= 0 with 400.
- Verification command (first shell spelling attempt did not invoke Maven because PowerShell parsed unquoted `-Djacoco.skip=true` as a lifecycle phase; no tests ran): `mvn '-Djacoco.skip=true' '-Dtest=ProductServiceTest,AdminProductControllerTest,JwtAuthFilterTest' test`.
- First executed test run: 17 tests; 16 passed, one controller test found `@Validated @Min` produced a global-handler 500 rather than the required 400. Repair round 1 removed method-level validation and added an explicit local amount check.
- Rerun of the same command: **17/17 passed**, 0 failures/errors/skips; JaCoCo intentionally skipped for this narrow suite; Maven exit 0.
- `git diff --check` passed (repository emitted only LF/CRLF warnings).
- Independent review: Claude read-only audit exited 0 and found no authorization bypass or data-consistency defect. It confirmed fail-closed JWT routing, server-derived ownership, conditional owner-aware writes, atomic restock, soft-delete filtering, and repeatable migration behavior. Non-blocking follow-ups: remove the now-unused `addProductCall`, and optionally add a direct admin-route 401 test.
- Risks / not yet proven: no live admin frontend and no full suite were run (both outside this bounded task); the additive migration was statically and independently reviewed but not applied against an existing populated MySQL instance in this session. The focused service tests mock the repository.
- Repair rounds: 1. User interventions: 0. Elapsed time / usage / cost: `unknown`.
