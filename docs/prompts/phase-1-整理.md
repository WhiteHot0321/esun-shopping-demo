# Phase 1 提示詞整理

Phase 1 共 11 項缺陷修復，依優先順序排列。每個提示詞都有單獨的 `phase-1-<X.Y>-<slug>.md` 檔案。

## 進度表（最新更新於 2026-09-09）

| # | 項目 | 對應檔案 | 狀態 | Commit |
| --- | --- | --- | --- | --- |
| 1.1 | Docker/application.yml 一致性 | `phase-1-1.1-docker-app-config.md` | ✅ | `9260f11` |
| 1.2 | DB init 腳本順序（01_/02_/03_） | `phase-1-1.2-db-init-order.md` | ✅ | `84b6f79` |
| 2.1 | HTTP 狀態碼修正 | `phase-1-2.1-http-status-codes.md` | ✅ | `a7924c0` |
| 2.2 | DTO 驗證加固 + payStatus enum | `phase-1-2.2-dto-validation.md` | ✅ | `8dee932` |
| 2.3 | Logging 清理（SLF4J） | `phase-1-2.3-logging-cleanup.md` | ✅ | `a7924c0` |
| 3.1 | 訂單編號碰撞修復（毫秒 + 序號） | `phase-1-3.1-order-id-collision.md` | ✅ | `36185d9` |
| 3.2 | OrderService N+1 查詢優化 | `phase-1-3.2-n-plus-1-query.md` | ✅ | `a197192` |
| 3.3 | 多商品死鎖修復（鎖排序） | `phase-1-3.3-deadlock-fix.md` | ✅ | `a197192` |
| 4.1 | 前端 Axios 統一 | `phase-1-4.1-axios-unified.md` | ❌ | — |
| 4.2 | sp_get_available_products 決策 | `phase-1-4.2-sp-products.md` | ❌ | — |

---

## 下一步（Phase 1 剩餘 2 項）

### 4.1 前端 Axios 統一

見 [`phase-1-4.1-axios-unified.md`](phase-1-4.1-axios-unified.md)

簡述：刪 `frontend/src/api.js` 死碼、App.vue 三處 `fetch()` 改用 axios instance，讀環境變數 `VITE_API_BASE_URL`。

### 4.2 sp_get_available_products 二選一

見 [`phase-1-4.2-sp-products.md`](phase-1-4.2-sp-products.md)

簡述：`ProductRepository` 既有 SP 呼叫的 call 物件，但 `getAvailableProducts()` 實際用 jdbcTemplate 直查。需擇一：真正接上 SP，或刪除 SP + 相關死碼並同步 README。

---

## Phase 1.5（新增驗證層）

在 Phase 1 全部完成後，補充驗證層（Testcontainers 併發測試 + k6 壓測基準線），才進 Phase 2。
見 [`docs/prompts/phase-1.5-驗證層.md`](phase-1.5-驗證層.md)（尚未建立，待 4.1/4.2 完成後補）

---

## 為什麼不同時做所有項目？

- **4.1 + 4.2** 都很小（~15-30 分鐘各），但不能並行：4.2 改 ProductService/Repository，4.1 改 App.vue；最好各自開 branch 方便隔離。
- **Phase 1.5** 要等 4.1/4.2 完成——驗證層測試的是「併發下單是否安全」和「查詢邏輯是否正確」，4.x 把前端/SP 決策定下來後才有意義。

---

## 如何使用本目錄

1. 開發 Phase 1 某一項時，複製對應的 `phase-1-X.Y-<slug>.md`
2. 找到「給開發 session 的提示詞」段落，複製貼到新 Claude Code session
3. Sonnet 開發完成，提出 PR 前跑 `/code-review high`
4. 合併後更新本頁進度表與 [`docs/PROGRESS.md`](../PROGRESS.md)
