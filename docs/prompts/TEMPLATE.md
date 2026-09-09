<!--
複製這份檔案為 docs/prompts/phase-<N>-<X.Y>-<slug>.md 後填寫。
用法與命名規則見 docs/prompts/README.md。

開發 session 直接貼「給開發 session 的提示詞」段落即可。
開發完成後，此檔案不需補「驗收結果」——改動記錄進版控 commit 與 docs/PROGRESS.md。
-->

# Phase <N> <X.Y>：<標題>

- **Branch**：`phase-<N>-<X.Y>-<slug>`
- **日期**：YYYY-MM-DD（當初新增的日期）
- **前置 phase**：<例如 phase-1-4.1，或「無」>

## 目標

<這個任務要達成什麼，一到三句話。避免寫成模糊的「優化 xxx」，要能對照驗收條件檢查。>

## 背景

<為什麼需要這個改動，關聯到哪個已知問題或需求討論結論。若對應 tools/README.md 的
已知問題編號，在此註明。>

## 不可修改的既有介面

<列出**不應該變動**的東西：API 路徑與回傳格式、DB schema 欄位、Stored Procedure 簽章、
frontend/src/api/client.js 的匯出方式等。具體列檔案路徑或函式名稱，避免寫空話。>

- 例：`ProductController` 的 `GET /api/products` 回傳格式不可變更
- 例：`sp_decrease_stock` 的參數簽章不可變更

## 明確需求

條列這次要做的事，具體到檔案/函式層級。

- ...
- ...

## 不在這次範圍內（Out of scope）

明確排除的事項，避免開發 session 順手擴大範圍或誤以為要一併修復其他已知問題。

## 驗收條件

**必須是可執行、可驗證的條件**，不是「看起來正確」。優先對應 `tools/` 測試或具體指令：

- [ ] `mvn -f backend/pom.xml test` 通過（若有新增/修改 Java 測試）
- [ ] `cd tools && pytest tests/integration` 通過
- [ ] 若涉及下單/庫存：`python -m esun_ops bench --product <ID> --orders <N> --workers 20` 一致性檢查全過
- [ ] <其他此任務特有的驗收條件>

---

## 給開發 session 的提示詞

<把上面幾節濃縮成一段可直接執行的指示。也可以說「請依本檔案的目標、不可修改介面、
明確需求、驗收條件開發」。把關鍵限制在此再強調一次，幫開發 session 快速入場。>

---

## 範例內容（開發前刪除）

例如 Phase 1 4.1（前端 Axios 統一），可能的內容：

**目標**：統一前端 HTTP 客戶端，刪除死碼

**不可修改**：
- `ProductController` 的所有 GET/POST 路徑和回傳格式
- `VITE_API_BASE_URL` 環境變數名稱

**明確需求**：
1. 刪 `frontend/src/api.js`
2. App.vue 的三處 fetch（行 105、130、193）改用 axios
3. axios instance 讀環境變數 `VITE_API_BASE_URL`

**驗收**：
- [ ] `npm run build` 通過
- [ ] 前端啟動後，所有 API 呼叫都來自同一個 axios instance
- [ ] 改環境變數 `VITE_API_BASE_URL=http://other-api` 後，前端會連到新位址

**給開發 session 的提示詞**：

你是 esun-shopping 購物車專案的前端工程師。目標：統一 HTTP 客戶端。現況：axios 已裝但沒用、api.js 是死碼、App.vue 用原生 fetch。做法：(1) 刪除 api.js；(2) 改 App.vue 三處 fetch 為 axios，讀環境變數 VITE_API_BASE_URL；(3) 確認 API 都經過同一個 axios instance。驗收：`npm run build` 過、前端啟動連測 API。
