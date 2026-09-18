# Task 021 — Phase 3.1 商品管理前端

## Session gate

- Baseline commit: `ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287`（延續 Tasks 017–020 未提交變更）
- Task class: medium / bounded frontend integration
- Goal: 完成登入使用者的商品管理介面，串接 Task 020 owner-only API。
- Affected module: `frontend`；只有在顯示已下架商品確實缺少資料時，才允許最小 owner-list API 調整與直接測試。
- Allowed changes: 新增 `ProductManagement.vue`、`ShopWorkspace.vue`/`App.vue` 的最小整合、直接相關 Vitest；必要時 owner product list 的最小 backend response/filter 調整。
- Change limit: frontend 最多 5 個核心檔；backend 最多 2 個核心檔且僅為管理列表狀態需求。
- Target verification: 一個聚焦 Vitest 命令及 `npm run build`；最多一次修復重跑。
- Explicit exclusions: ECPay、訂單查詢、DB migration、完整 backend regression、Notion、commit/push/merge、無關 UI 重構。

## Acceptance criteria

- 登入使用者可看到自己的商品，並能區分／篩選上架與已下架狀態。
- 可新增商品、編輯自己的商品名稱／價格、確認後軟刪除、對自己的商品補貨。
- 商品編號、名稱、價格、庫存的即時驗證明確；價格至少 `0.01`、庫存至少 `0`、補貨量大於 `0`。
- 所有請求沿用既有 authenticated API client，不傳 creator/member ownership 欄位。
- 403、409、validation 與一般網路錯誤呈現可理解訊息；成功後重新載入 owner/public product state。
- 聚焦前端測試與 production build 通過。

## Result

- Status: **PASS — implemented, focused-verified, production-built, and independently reviewed.**
- Implementer: delegated worker
- Reviewer: independent read-only review after verification
- Changed (uncommitted): new `frontend/src/components/ProductManagement.vue` and `ProductManagement.spec.js`; `frontend/src/components/ShopWorkspace.vue`, `frontend/src/App.vue`, `frontend/src/App.spec.js`; and the minimal owner-list adjustment in `backend/src/main/java/com/esun/shop/repository/ProductRepository.java` plus `ProductService.java`.
- Owner list adjustment: `GET /api/admin/products` still derives ownership exclusively from JWT on the server, but now returns that owner's active and soft-deleted rows. Public/catalog/order paths remain deleted-filtered. This is necessary for the requested deleted filter and sends no creator/member value from the browser.
- UI behavior: authenticated users can filter `上架中`/`已下架`, add with immediate ID/name/price/quantity validation, edit only name/price, confirm soft-delete, and restock with immediate positive-integer validation. Requests use `/admin/products` authenticated client paths; product payloads contain no ownership fields. 403/409/400/network failures map to actionable messages. A successful mutation reloads owner rows and emits a catalog refresh to the checkout workspace.
- Focused verification attempt 1: `npx vitest run src/components/ProductManagement.spec.js src/App.spec.js` ran 14 tests, with 11 passing and 3 failing. Cause: the create button relied on native form submit, which Vitest click simulation does not dispatch; product creation/error tests and the former App create-product expectation did not execute.
- Repair round 1: create button changed to explicit `type="button"` and `@click`, preserving validation and avoiding native form navigation.
- Focused verification rerun: `npx vitest run src/components/ProductManagement.spec.js src/App.spec.js` — **14/14 passed**, 0 failures.
- Production verification: `npm run build` — exit 0; Vite transformed 98 modules and built successfully.
- `git diff --check` passed (only existing repository LF/CRLF warnings).
- Independent review: Claude read-only audit exited 0 and found no correctness or security issue. It confirmed active/deleted filtering, CRUD/restock flows, client validation, authenticated API use without ownership fields, catalog refresh, and that the minimal backend owner-list adjustment does not weaken public/order soft-delete filtering.
- Risks: no live browser/backend API run and no frontend E2E test; the bounded backend owner-list adjustment is covered by frontend contract mocks rather than an additional backend test in this frontend-only session. Existing Task 020 targeted backend evidence remains separate.
- Repair rounds: 1. User interventions: 0. Elapsed time / usage / cost: `unknown`.
