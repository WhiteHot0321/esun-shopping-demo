# esun-shopping 維護者（Maintainer / Ops）功能需求清單 & 缺口分析

**日期**: 2026-09-17
**分析人員**: Claude Sonnet 5
**狀態**: 候選提示詞，未實作
**對應角色文件**: 賣家清單見 `016-feature-gap-analysis.md`；買家清單見 `017-buyer-feature-list.md`；
三份清單的切割/實作順序見 `019-phase3-role-based-split-plan.md`

---

## 背景

016（賣家）與 017（買家）分析的是「商城能不能好好被用來買賣東西」。本文分析的是另一個角色：
**讓系統本身能健康運作、安全、可觀測、出事能救回來的人**——不一定是使用者能看到的功能，
而是維運者、開發者、未來的資安稽核者會需要的能力。這條線目前幾乎是空白：整個專案有並發
安全（死鎖重試、Redis 降級稽核）這種「架構層」的可靠性投資，但完全沒有「維運層」的基本配備。

以下每一項都已對照目前程式碼驗證，不是臆測：

- **完全沒有角色/權限概念**：`member` 表（`04_member.sql`）只有 `id/email/password_hash/created_at`，
  沒有 role 欄位；`JwtAuthFilter.java` 只驗證 token 是否有效，不檢查任何角色；
  `ProductController.java` 的 `POST /api/products`（上架商品）唯一的防護是「必須登入」——
  **任何註冊過的一般買家帳號，都可以呼叫這個端點上架商品**。這不只是功能缺口，是資安缺口。
- **沒有 Spring Boot Actuator**：`backend/pom.xml` 沒有 `spring-boot-starter-actuator` 依賴，
  沒有 `/actuator/health`、沒有 metrics 端點。系統死活只能靠 `docker compose logs` 人工看。
- **沒有備份機制**：`docker-compose.yml` 只有 volume 持久化（`healthcheck` 有做，備份沒有）；
  `MANUAL_TESTING_GUIDE.md` 8.2 的「重置」流程是整個 DB 砍掉重建，不是「備份→驗證可還原」。
- **CI 沒有部署**：`.github/workflows/ci.yml` 的 `docker-build` job 明確寫著
  `# Pushing the image to a registry is intentionally left out here`——目前只有測試，沒有任何
  自動部署路徑。

## 已實現功能清單（維運視角）

### 已有的可靠性投資
- ✅ Docker Compose 一鍵啟動（MySQL healthcheck + 初始化腳本序列化 01→02→03→04）
- ✅ 死鎖檢測 + 重試（多品項庫存扣減依 productId 排序上鎖）
- ✅ Redis 降級與稽核（連線中斷時 latch 到 degraded 模式，`cache.audit()` 可核對 DB/Redis 是否一致）
- ✅ 結構化 Log（SLF4J，Phase 1 已把 `printStackTrace()` 全面替換）
- ✅ 標準化錯誤回應（HTTP 400/404/409/500，不再全部回 200）
- ✅ GitHub Actions CI（backend 測試 + frontend 測試/build + Docker image build）
- ✅ 負載測試腳本（`bench/phase25.js`，k6，可重跑驗證並發安全）

### 已知但尚未執行的維運缺口記錄
- `docs/project-state.md` 已記錄的技術債（3-round 重複測試、Redis outage drill 等）多半是
  「測試證據夠不夠」層級，跟本文「維運能力本身存不存在」是不同層次的問題

---

## 缺陷分析

### 🔴 Tier 1：維運關鍵風險（現在完全不存在的防護）

#### 1.1 角色權限管理（RBAC）（**優先級 P0，資安等級**）
```
問題描述：
  - 系統目前只有「有沒有登入」的二元判斷，沒有「登入的是誰、能做什麼」的概念
  - 實測結果：任何買家帳號都能呼叫 POST /api/products 上架商品——沒有 PUT/DELETE，
    但「寫入」本身就不該對一般買家開放
  - 016 提到「權限：需區分賣家 vs 一般用戶角色」，但這句話背後需要的基礎設施
    （member 加 role 欄位、JWT claim 帶入角色、路由層角色檢查）完全還沒開始

實作難度：中 ⭐⭐
影響範圍：
  - DB：member 表新增 role 欄位（預設 'BUYER'，可選 'SELLER' / 'ADMIN'）
  - 後端：JwtService 簽發 token 時把 role 放進 claim；JwtAuthFilter 或新增的
    角色檢查層依路由所需角色擋下請求（例如 POST /api/products 要求 SELLER/ADMIN）
  - 前端：依角色顯示/隱藏「上架商品」等操作入口（UI 隱藏只是體驗優化，
    後端檢查才是真正的防線）
  - 這是 016 商品管理後台、017 訂單狀態管理端、018 本身多項功能的共同依賴，
    應優先於其他 Tier 1 項目完成（見 019 的排序建議）

提示詞關鍵字：RBAC, role-based access control, JWT role claim, authorization
```

#### 1.2 系統監控 / 健康檢查（Observability）（**優先級 P0**）
```
問題描述：
  - 沒有 /actuator/health，Docker healthcheck 只驗證 MySQL 容器本身，沒有驗證
    後端應用程式是否真的存活（例如資料庫連線池耗盡時，容器還是「healthy」但 API 全部逾時）
  - 沒有任何 metrics（QPS、p95/p99、錯誤率），要知道「系統現在正不正常」
    只能等使用者回報或人工看 log

實作難度：低-中 ⭐⭐
影響範圍：
  - 後端：加入 spring-boot-starter-actuator，開放 /actuator/health（含 DB/Redis 連線檢查）
  - 部署：/actuator/** 不應該對外公開，需限制來源或加驗證（本身也是 1.1 RBAC 的應用場景）
  - 可選：接 Prometheus + Grafana，或先用最小版本（health endpoint + log-based 告警）

提示詞關鍵字：spring boot actuator, health check, metrics endpoint
```

#### 1.3 資料備份與還原演練（**優先級 P0**）
```
問題描述：
  - 目前唯一的資料保護是 Docker volume（容器重建資料還在，但 volume 本身被誤刪、
    磁碟損毀、或誤跑重置腳本，資料就沒了）
  - 沒有排程備份（mysqldump 或等效機制），也沒有「備份後實際還原驗證過一次」的紀錄——
    沒驗證過的備份等於沒有備份

實作難度：低-中 ⭐⭐
影響範圍：
  - 腳本：定期 mysqldump（先做手動可執行版本，排程化可以晚一點）
  - 文件：還原演練 SOP，寫進 MANUAL_TESTING_GUIDE.md 或獨立文件
  - 驗證：至少完整跑過一次「備份→模擬資料損毀→還原→驗證資料正確」

提示詞關鍵字：database backup, restore drill, mysqldump automation
```

---

### 🟡 Tier 2：維運效率與風險降低

#### 2.1 操作稽核日誌（Audit Log）（**優先級 P1**）
```
問題描述：
  - 誰在什麼時候改了商品價格/庫存、誰建立了訂單、誰改了訂單狀態——目前完全沒有記錄
  - 出問題（例如庫存數字異常、價格被改錯）時無法追溯是誰、什麼時候、改了什麼
  - 一旦 1.1（RBAC）上線，「誰用什麼角色做了什麼」的稽核價值會更高

實作難度：中 ⭐⭐
影響範圍：
  - 後端：新表 audit_log（actor_member_id, action, target_type, target_id, before/after, created_at）
  - 可用 AOP 或 Service 層統一攔截寫入操作，避免每個 Service 方法各自手動記錄

提示詞關鍵字：audit log, change tracking, admin action history
```

#### 2.2 集中式錯誤追蹤 / 告警（**優先級 P1**）
```
問題描述：
  - 錯誤目前只進 log 檔案，沒有集中收集、沒有告警規則
  - 系統掛掉（例如 Ollama 服務中斷、DB 連線耗盡）要等使用者回報才知道，
    Redis outage 有降級稽核機制，但其他錯誤類型沒有對應的「主動通知維運者」路徑

實作難度：中 ⭐⭐
影響範圍：
  - 選項 A（輕量）：log 層級告警（ERROR log 觸發 webhook/email）
  - 選項 B（完整）：接 Sentry 或等效錯誤追蹤服務
  - 依賴 1.2（Actuator/metrics）作為告警的資料來源之一

提示詞關鍵字：error tracking, alerting, sentry integration, log-based alerts
```

#### 2.3 環境設定與密鑰管理審查（**優先級 P1**）
```
問題描述：
  - Phase 1 已把 DB 密碼等改用環境變數（不再寫死在 application.yml），但目前
    仍是明碼存在 .env / docker-compose 環境變數中，沒有 secrets vault、沒有輪替機制
  - 上生產環境前，維運者需要一份「目前有哪些敏感設定、存在哪裡、誰能存取」的盤點清單

實作難度：低（盤點文件）到中（若要接 vault）⭐-⭐⭐
影響範圍：
  - 文件：敏感設定盤點清單（DB_PASSWORD、JWT secret、Ollama 若接外部服務的 API key 等）
  - 進階：評估 Docker secrets / HashiCorp Vault / 雲端 KMS，視部署環境決定是否需要

提示詞關鍵字：secrets management, environment variable audit, credential rotation
```

#### 2.4 資料庫效能監控（慢查詢 / 索引健檢）（**優先級 P1**）
```
問題描述：
  - 017 的訂單歷史查詢一旦上線，member_id 需要索引，否則資料量成長後查詢會變慢
  - 目前沒有慢查詢日誌，沒有定期索引健檢的習慣，效能問題通常是「使用者抱怨慢」之後才被發現

實作難度：低-中 ⭐⭐
影響範圍：
  - MySQL 慢查詢日誌開啟 + 定期檢視
  - 017 訂單查詢上線時，順帶補上 shop_order(member_id, created_at) 複合索引
  - 建議跟 017 的 Phase 3.0（訂單歷史）同一輪一起做，而不是事後補

提示詞關鍵字：slow query log, database indexing, query performance monitoring
```

---

### 🟢 Tier 3：長期維運成熟度

#### 3.1 CI/CD 部署自動化（**優先級 P2**）
```
問題描述：
  - CI 目前只跑測試 + build image，docker-build job 明確跳過了 push/deploy 步驟
  - 沒有任何自動部署路徑，每次上版都得手動處理

實作難度：中 ⭐⭐（取決於目標部署環境，雲端 vs 自架主機難度差很多）
影響範圍：
  - 需先決定部署目標（雲端容器服務 / 自架 VM / K8s），再補 CI 的 push + deploy job
  - 需要注入部署憑證（registry 帳密、SSH key 等），屬於 2.3 密鑰管理的實際應用場景

提示詞關鍵字：CI/CD pipeline, docker registry push, automated deployment
```

#### 3.2 API 文件（Swagger/OpenAPI）（**優先級 P2**）
```
問題描述：
  - 目前 API 規格只存在於 Controller 程式碼和 README，維護者/新加入的協作者
    要串接或除錯只能翻原始碼

實作難度：低 ⭐
影響範圍：
  - 加入 springdoc-openapi，自動產生 /swagger-ui
  - CLAUDE.md 的 Phase 2 candidates 已列出此項，屬於低成本高回報的維運工具

提示詞關鍵字：swagger, openapi, api documentation
```

#### 3.3 容量規劃 / 效能基線長期追蹤（**優先級 P2**）
```
問題描述：
  - bench/ 已有 k6 腳本可以跑，但每次都是「手動執行一次、寫進某份任務文件」，
    沒有長期趨勢儀表板——沒辦法一眼看出「這次改動有沒有讓效能變差」

實作難度：中 ⭐⭐
影響範圍：
  - 把現有 k6 結果定期跑一次並存成時序資料（簡單版：存成 CSV/JSON，畫成趨勢圖）
  - 進階：接 Grafana/InfluxDB，跟 1.2 的 metrics 基礎設施共用

提示詞關鍵字：performance baseline tracking, load test trend, capacity planning
```

#### 3.4 商品/訂單批量操作與軟刪除（**優先級 P2，與 016 共用）**
```
問題描述：
  - 016 提到商品管理需要「編輯/下架/批量操作」，維運視角補一點：現在的 product
    表沒有 status/deleted_at 欄位，任何刪除只能是硬刪除，一旦有訂單明細
    （order_detail 有 FK 指向 product_id）就會刪不掉或造成資料不一致

實作難度：低-中 ⭐⭐
影響範圍：
  - DB：product 表新增 status（ACTIVE/DELISTED）取代硬刪除
  - 後端：下架邏輯改成軟刪除，既有訂單歷史不受影響

Cross-ref: 016 的 2.1（賣家後台的下架操作，底層需要這個 schema 改動先存在）

提示詞關鍵字：soft delete, product delisting, status column
```

---

## 功能矩陣（優先級 × 難度）

| 功能 | 難度 | 風險等級 | Phase 建議 | 依賴 |
|------|------|---------|-----------|------|
| RBAC 角色權限 | ⭐⭐ 中 | 🔴 資安 | **Phase 3.0（最優先）** | 無，但是多數其他項目的依賴 |
| 系統監控/健康檢查 | ⭐⭐ 低-中 | 🔴 關鍵 | Phase 3.0 | 無 |
| 資料備份與還原演練 | ⭐⭐ 低-中 | 🔴 關鍵 | Phase 3.0 | 無 |
| 操作稽核日誌 | ⭐⭐ 中 | 🟡 高 | Phase 3.1 | 建議在 RBAC 之後做，稽核才有角色可記 |
| 集中式錯誤追蹤/告警 | ⭐⭐ 中 | 🟡 高 | Phase 3.1 | 依賴監控基礎設施 |
| 密鑰管理審查 | ⭐-⭐⭐ 低-中 | 🟡 高 | Phase 3.1 | 無 |
| DB 效能監控/索引 | ⭐⭐ 低-中 | 🟡 高 | Phase 3.0（搭配買家訂單查詢上線） | 017 訂單歷史查詢 |
| CI/CD 部署自動化 | ⭐⭐ 中 | 🟢 中 | Phase 3.2 | 需先決定部署目標 |
| API 文件 | ⭐ 低 | 🟢 中 | Phase 3.1 | 無 |
| 效能基線追蹤 | ⭐⭐ 中 | 🟢 中 | Phase 3.2 | 依賴監控基礎設施 |
| 軟刪除/批量操作 | ⭐⭐ 低-中 | 🟢 中 | Phase 3.1 | 016 商品管理後台的前置 |

---

## 提示詞範本

### Phase 3.0 提示詞（RBAC 角色權限，最優先的維運項目）

```markdown
## 背景
esun-shopping 目前任何登入帳號都能呼叫 POST /api/products 上架商品——member 表沒有 role
欄位，JwtAuthFilter 只驗證 token 有效性，不驗證角色。這是資安缺口，也是 016（賣家後台）、
017（買家個人資料/訂單狀態管理）多項功能的共同依賴，應在其他 Phase 3 項目之前完成。

## 任務（B 階段，範圍已由 A 階段確認）
1. DB：member 表新增 role 欄位（VARCHAR 或 ENUM，預設 'BUYER'）
2. 後端：JwtService 簽發 token 時帶入 role claim；新增角色檢查機制，
   讓 POST /api/products 等寫入端點要求 SELLER/ADMIN 角色，一般 BUYER 呼叫回 403
3. 既有測試（AuthServiceTest, JwtServiceTest, ProductControllerTest 等）需更新以涵蓋角色情境

## 檢驗
- BUYER 角色呼叫 POST /api/products 應回 403（目前是 200，這是要修的缺陷本身）
- SELLER/ADMIN 角色呼叫正常成功
- 既有的 81 個後端測試全數通過，新增角色相關測試案例

## 明確範圍限制（依 prompt-scope-control 規則）
- 可讀：member 相關 DTO/Entity、AuthService、JwtService、JwtAuthFilter、ProductController
- 可改：上述檔案 + 04_member.sql（新增欄位，非破壞性 migration）；
  不觸碰 OrderController、SupportController、前端 UI（角色相關 UI 隱藏留待後續提示詞）
- 要跑：mvn clean test（全量）
```

---

## 建議執行路徑

**當前進度**: Phase 2.1（LLM 客服）✅，`feature/frontend-ux-revamp` 已驗收

### 短期（1-2 週，資安/可靠性優先）
1. **Phase 3.0 第一項**：RBAC 角色權限（其他角色相關功能的地基，應該最先做）
2. **Phase 3.0 同步**：系統監控/健康檢查 + 資料備份演練（兩者互相獨立，可與 RBAC 並行，
   但依 `phase2-branching-strategy` 記憶規則，各自開獨立分支，不要擠在同一個 PR）

### 中期（2-4 週）
3. **Phase 3.1**：操作稽核日誌、錯誤追蹤/告警、密鑰管理盤點、API 文件、軟刪除/批量操作
   （建議跟 017 的訂單歷史查詢同一輪補上 DB 索引）

### 長期（可選）
4. **Phase 3.2+**：CI/CD 部署自動化、效能基線長期追蹤

---

## 相關檔案

- Member schema（無 role 欄位）: `backend/DB/04_member.sql`
- 商品寫入端點（無角色檢查）: `backend/src/main/java/com/esun/shop/controller/ProductController.java`
- JWT 過濾器（僅驗證 token 有效性）: `backend/src/main/java/com/esun/shop/security/JwtAuthFilter.java`
- CI 設定（無部署步驟）: `.github/workflows/ci.yml`
- Docker Compose（無備份機制）: `docker-compose.yml`
- 手動測試指南（重置流程 = 砍掉重建）: `docs/MANUAL_TESTING_GUIDE.md`
- 依賴管理（無 Actuator）: `backend/pom.xml`
- 賣家清單: `docs/tasks/016-feature-gap-analysis.md`
- 買家清單: `docs/tasks/017-buyer-feature-list.md`

---

## 備註

- 此分析基於 2026-09-17 對照目前 HEAD（`feature/frontend-ux-revamp`）的實際程式碼驗證，
  RBAC 缺口（任何買家可上架商品）是實測程式碼路徑得出的具體發現，非推測。
- RBAC（1.1）是本文影響範圍最廣的項目，建議整合排序時優先於 016/017 中依賴「角色」概念
  的所有功能（賣家後台權限、買家個人資料存取控制等），詳見 `019-phase3-role-based-split-plan.md`。
- 與 016（賣家）、017（買家）重疊的項目已標明 Cross-ref，避免三份清單各自重複規劃。
