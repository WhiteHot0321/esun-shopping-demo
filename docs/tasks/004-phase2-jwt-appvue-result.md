# Task 004 — Phase 2 JWT / App.vue 收尾

- 日期：2026-09-15（Asia/Taipei）
- 執行者：Codex；分支：`phase2-app-vue-refactor`
- 狀態：完成，尚未 commit/push/merge。

## 實際變更

- JWT subject 產生與解析拒絕空值。
- JWT filter 僅允許 `POST /api/auth/register`、`POST /api/auth/login` 公開；避免任意 `/api/auth/**` 路徑繞過驗證。
- `App.vue` 已拆成 `src/components/AuthPanel.vue` 與 `src/components/ShopWorkspace.vue`（商品、訂單、checkout）。
- 導入 Vue Router（`/`、`/shop`）與 Pinia auth store，保留登入、註冊、登出、token 過期及冪等 checkout 行為。

## 驗證

- `frontend`: `npm run build` 通過。
- `frontend`: `npm run test:checkout`：3 passed。
- `backend`: `mvn -q -DskipTests compile` 通過。
- `backend`: `mvn -q '-Dtest=JwtServiceTest,JwtAuthFilterTest' test` 通過。

## 下一步

Phase 2 JWT / App.vue 項目完成；後續可另開任務處理 Router guard、路由級頁面分離與 UI 測試。
