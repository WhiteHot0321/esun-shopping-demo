# Task 028 — Phase 3.1 #9 商品評論系統 [Buyer + Seller]

## Session gate

- Task class: Medium（評論 CRUD、RBAC 與商品歸屬整合）
- Baseline: `advanced-v2` @ `6f317fb4dfaf4035bb6e6b593232bd17ce3445ef`
- Worktree: `C:/Users/User/.codex/worktrees/phase31-product-reviews/esun-shopping`
- Goal: 完成買家評論與賣家審核／隱藏流程。
- Implementer: Codex
- Independent reviewer: Claude Code（兩階段唯讀審查）
- User-approved expansion: 約 30 個修改檔、6 個驗證命令、2 個獨立審查階段。
- Exclusions: coupons, recommendations, order-status work, unrelated refactors, commit/push/merge.

## Acceptance contract

- 公開評論支援分頁、穩定排序，平均評分與筆數只計可見評論。
- 只有具 `order_detail` 購買證據的登入會員可評論；評分 1–5、內容最多 1000 字。
- DB 唯一鍵保證每位會員每項商品一則評論，並在競態下回傳 conflict。
- 僅作者可修改或刪除；身分一律取自 JWT。
- SELLER 僅能列出及隱藏／恢復自己商品的評論，不得改買家內容；ADMIN 可跨商品管理。
- 隱藏評論不出現在公開／買家清單，也不計入聚合值。
- migration 對既有資料庫可重複執行，fresh schema 與 migration 一致。
- 前端提供買家建立／修改／刪除與賣家隱藏／恢復入口。

## Result

Status: **implemented, fully verified, independently reviewed; not committed or merged**.

### Delivered

- DB: `product_review` table, rating/visibility checks, member-product unique constraint, public lookup index; idempotent role/product-owner/review migration.
- RBAC/ownership prerequisite: JWT signed role claim, BUYER/SELLER/ADMIN identity, SELLER/ADMIN-only product creation, authenticated seller ownership persistence.
- Backend: public review page/aggregate API, verified-purchase create, author update/delete, seller-owned and admin moderation.
- Frontend: persisted role, seller-only product form, rating/count on catalog cards, buyer authoring and seller/admin moderation panel.
- Tests: real-MySQL coverage includes no-purchase denial, CRUD, duplicate and concurrent duplicate, author/seller ownership, cross-seller denial, hide/restore and visible-only aggregates.

### Verification evidence

- `ProductReviewIntegrationTest`: 3/3 test methods passed; includes exactly-one-winner concurrent duplicate invariant.
- Targeted RBAC compatibility tests (`AuthIntegrationTest,JwtAuthFilterTest,AuthServiceTest`): PASS, exit 0.
- Frontend checkout suite 3/3 and Vitest 26/26: PASS.
- Final backend `mvn -q clean test`: **104/104**, failures 0, errors 0, skipped 0; JaCoCo gate PASS.
- Frontend `npm run build`: PASS, 102 modules transformed.
- `git diff --check`: PASS.
- Independent prerequisite authorization audit (Task 029): **PASS**.
- Independent product-review audit (Task 030): **PASS**.

### Repair record

- Repair round 1: Java 17-compatible executor shutdown in the concurrent review test.
- Repair round 2: updated existing authentication/controller tests for explicit BUYER denial, SELLER success and role-bearing JWT responses.
- Initial narrow Maven run executed all feature tests but exited at the repository-wide JaCoCo gate; final full suite proved the gate.
- Ollama lacked `nomic-embed-text` during tests; existing embedding fallback emitted warnings but did not affect test outcomes.

### Review observations

- Non-blocking: public API supports paging, but the current review panel displays the first page only.
- Non-blocking: public response currently performs two equivalent visible-count queries.
- No functional, security, authorization or data-integrity blocker was found.

### Delivery state

- No commit, push or merge was performed.
- Main checkout user changes were preserved; implementation remains isolated in the worktree above.
- Elapsed time, token/model cost and five-hour usage delta: unavailable/unknown.
- User interventions: 2 scope-expansion approvals.
- Valid independent-review defects: 0.

