---
name: codebase-onboarding
description: 帶新人（或新的 agent session）快速看懂 esun-shopping 專案：專案在做什麼、技術棧、架構分層、關鍵不變量與該讀哪些檔案。使用者說「帶我看這個專案」「onboarding」「專案導覽」「這個 repo 怎麼運作」「我要接手這塊，從哪開始看」時使用。只做導覽與解說，不改程式碼。
---

# Codebase Onboarding — esun-shopping

以繁體中文回答。目標是讓讀者用最少的檔案讀取建立正確的心智模型，而不是把整個 repo 讀一遍。

## 原則

- **先問層級再深入**：使用者沒指定就從 Level 1 開始，最後問一句要不要往下挖哪個主題。
- **進度與狀態以 `docs/project-state.md` 為準**。`README.md`、`CLAUDE.md` 的 Phase 清單可能落後（例如 README 仍寫「系統目前沒有登入驗證」，實際上早已有 JWT + 角色）。文件與程式碼衝突時，指出差異並以程式碼與測試為證據。
- **不要把知識寫死在導覽裡**：本 skill 只放穩定的結構與規則；測試數量、commit hash、完成狀態每次現讀 `docs/project-state.md`。
- 只做導覽。使用者要修改才另外處理，不要順手重構或補文件。
- 引用檔案時用 `path:line` 或 markdown 連結，方便點開。

## Progressive Disclosure Levels

### Level 1：30 秒版（預設）

只讀 `README.md` 開頭與 `docs/project-state.md` 頂部的「Current acceptance status」，回答：

- 這個專案在做什麼、目前做到哪、最近在做什麼。
- 目前策略：Phase 3 走「工程深度」（交易 / 死鎖 / 冪等 / Redis Lua / Testcontainers / k6 / CI-CD），不是再堆 CRUD 功能。
- 開發分支是 `advanced-v2`，`main` 保留原始面試版本，不動它。

### Level 2：架構版（要求「看架構」「整體怎麼串」時）

再讀：`backend/pom.xml`（版本與依賴）、`backend/src/main/resources/application.yml`（設定與環境變數）、`docker-compose.yml`、`.github/workflows/ci.yml`。

需要涵蓋：

1. **分層**：Controller → Service → Repository（JdbcTemplate / Stored Procedure 為主）。
2. **請求流**：前端 axios（`frontend/src/api.js`）→ `JwtAuthFilter` → Controller → Service → MySQL（+ 可選 Redis）。
3. **基礎設施**：MySQL 8、Redis（庫存預留，預設關）、Ollama（AI 客服 RAG）。
4. **執行方式**（詳見下方「跑起來」）。

### Level 3：深入主題（指定主題才讀對應檔案）

每個主題只讀「入口檔 + 對應 task/handoff 文件」，不要橫掃。

| 主題 | 入口 | 必須交代的重點 |
|---|---|---|
| 下單與交易 | `OrderController` → `OrderService` → `OrderTransactionService` | 後端計價（金額用 `BigDecimal`）、訂單主檔 + 明細 + 庫存異動同一交易、條件式庫存扣減（`sp_decrease_stock`）、庫存不足回 409 而非 500 |
| 冪等 | `order_request` 表、`CreateOrderRequest.requestId` | `requestId` 必須是小寫 canonical UUID；重試回原 `orderId` 不重複扣庫存 |
| 併發與死鎖 | `docs/tasks/013-phase25-final-acceptance.md`、`bench/PHASE3-K6.md` | 固定鎖定順序（商品依 `productId` 排序）；文件記載的鎖順序：order → coupon → member usage → product；MySQL 1213 與應用層 409 的邊界 |
| Redis 庫存 | `StockCacheService`、`resources/redis/stock-decrease.lua` | Lua 單次往返原子預留；`STOCK_REDIS_ENABLED` 預設 false；Redis 故障時降級為 DB-only |
| 認證與權限 | `security/JwtService`、`security/JwtAuthFilter`、`AuthService` | JWT 帶角色（BUYER / SELLER / ADMIN）；身分一律取自 token，不取自請求參數；`JwtAuthFilter.isPublicRoute` 同時被 OpenAPI 設定共用 |
| 金流 | `PaymentService`、`PaymentCallbackService`、`PaymentGateway` 實作 | 訂單一律 PENDING，只有通過簽章驗證的 callback 能改 PAID；`payment.provider`：`none`（預設）/`sandbox`/`ecpay` |
| 優惠券 | `CouponService`、`backend/DB/14_coupon.sql` | 兌換在訂單交易內（`Propagation.MANDATORY`）、`FOR UPDATE` 鎖 coupon row；client 永不傳價格 |
| 稽核 | `AuditLogService`、`12_audit_log.sql` | 稽核列與被稽核的變更同一交易；before 快照在 row lock 下讀 |
| 推薦 | `RecommendationController`、`15_recommendation.sql` | 唯讀、最小支持度門檻避免洩漏單一買家購物籃 |
| AI 客服 | `SupportService`、`llm/*` | RAG：商品 + FAQ embedding；`LLM_PROVIDER` 預設 ollama；查無資料須回「不知道」 |
| 測試 | `backend/src/test/.../integration/AbstractMySqlIntegrationTest` | Testcontainers 真 MySQL；`mvn clean test`；前端 Vitest + `checkout.test.js` |
| 壓測 | `bench/PHASE3-K6.md`、`bench/run-phase3-suite.py` | 四個 workload；以正確性（不超賣、配額精確）為 pass/fail，延遲只當 baseline |
| CI/CD | `.github/workflows/ci.yml`、`docs/tasks/039-cicd-automation.md` | 測試 → Docker build → 只有綠燈才 publish 到 GHCR；PR 不 publish |
| API 文件 | `OpenApiConfig`、`docs/tasks/040-api-docs.md` | `/swagger-ui.html`、`/v3/api-docs`；正式環境設 `API_DOCS_ENABLED=false` |

## 專案地圖

```
backend/
  DB/                      SQL 腳本：01_schema → 02_data → 03_stored_procedures → 04+ 為可重跑 migration
  src/main/java/com/esun/shop/
    controller/            12 個 Controller（Auth, Cart, Coupon, MemberProfile, Order, Payment,
                           Product, ProductReview, Recommendation, ShippingAddress, Support, AuditLog）
    service/               業務邏輯與交易邊界
    repository/            JdbcTemplate 資料存取
    security/              JwtService、JwtAuthFilter
    llm/                   Ollama / Claude client、embedding 索引、向量搜尋
    dto/ model/ config/ exception/
  src/main/resources/      application.yml、redis/stock-decrease.lua
  src/test/                單元測試 + integration/（Testcontainers）
frontend/src/              Vue 3 + Vite：App.vue、components/、stores/auth.js、router.js、api.js（axios）
bench/                     k6 壓測與結果
docs/                      project-state.md（進度真相）、tasks/（任務規格與結果）、handoff/（交接）
scripts/                   mysql-backup-restore.ps1、invoke-claude.ps1
.claude/                   agents/、skills/
```

## 跑起來

環境變數範本在 `.env.example`；`application.yml` 對每個變數都有預設值。

```bash
docker compose up -d                                   # MySQL / Redis / Ollama
cd backend && mvn clean package && java -jar target/shopping-backend-1.0.0.jar   # :8080
cd frontend && npm install && npm run dev              # :5173
cd backend && mvn clean test                           # 完整後端測試（需要 Docker）
cd frontend && npm test && npm run build
```

注意：`README.md` 的 DB 初始化寫法是舊版；新增 migration（`04_` 之後）為可重跑腳本，不要重跑會 DROP 資料表的 `01_schema.sql`。

## 不可破壞的不變量（改動前必讀）

- 金額由後端計價，`BigDecimal`；client 不能決定價格或 `payStatus`。
- 訂單、明細、庫存異動在同一交易；庫存只做條件式扣減，不做「先查再寫」。
- 多商品扣庫存必須固定順序；新增鎖時遵守 order → coupon → member usage → product。
- 使用者身分只來自 JWT，不來自 request body / query。
- 所有權判斷要放進寫入 SQL 本身，不能只在 UI 隱藏。
- 關鍵路徑（訂單 / 庫存 / 付款 / 權限 / 交易 / 併發）依 `AGENTS.md` 需要另一個 agent 獨立審查。

## Output Format

```markdown
## 這個專案在做什麼
[1-2 句：Spring Boot + Vue 購物車，目前以工程深度為主線]

## 目前進度
[取自 docs/project-state.md 頂部；註明讀取日期，不抄 commit hash 與測試數]

## 技術棧
[後端 / 前端 / DB / 快取 / LLM / CI，各一行]

## 架構與請求流
[Level 2 的分層與請求流，一段文字或簡單條列]

## 該先讀的 5 個檔案
[依讀者目標挑選；每個一行說明「為什麼讀它」]

## 要小心的地方
[挑 2-3 條與讀者目標相關的不變量]

## 下一步
[問讀者要往哪個 Level 3 主題深入]
```

## 收尾檢查

- 有沒有把 README 或 CLAUDE.md 過期的說法當成事實？（改以 `docs/project-state.md` 與程式碼為準）
- 有沒有寫出會過期的數字（測試數、commit hash）？有的話拿掉或標註日期。
- 讀取的檔案是否控制在回答所需的最小範圍？
