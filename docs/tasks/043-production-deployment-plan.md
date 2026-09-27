# 043 正式部署計畫（Phase 3.3 #18）

- MODE: PLAN（2026-09-26，Claude Code）。基準 advanced-v2 @ 176b190。計畫本身未修改程式碼；**B1 已實作，見 §2.1**（分支 `feature/phase33-18-b1-prod-hardening`）。B2／B3／C 尚未開始。
- 目標：把 esun-shopping 做成「可在單台 Ubuntu VM 上以 HTTPS 正式運行、可重複部署、可回滾」的狀態。
- 決策（使用者，2026-09-26）：**單台 Ubuntu VM ＋ Docker Compose ＋ Caddy 自動 HTTPS ＋ GHCR 映像（以 commit SHA 標記）**。
- 依 `prompt-scope-control` 規則拆為 **B1 → B2 → B3 → C**，每階段獨立提示詞、獨立驗收、一次只跑一個重階段；不可合併成單一 prompt。

## 0. 現況盤點（A 階段結果）

已有：`backend/Dockerfile`（多階段）、CI（後端測試＋JaCoCo、前端測試／建置、映像建置、僅受保護分支推 GHCR，**沒有 deploy job**）、`scripts/mysql-backup-restore.ps1`、`API_DOCS_ENABLED` 開關、`PAYMENT_PROVIDER` 預設 `none`、`HmacPaymentGateway` 遇 `dev-only` 密鑰即拒絕。

## 1. 缺口與驗收標準

| # | 缺口（證據） | 風險 | 驗收標準 | 階段 |
|---|---|---|---|---|
| G1 | `jwt.secret` 有公開的 dev 預設值，`JwtService` 不檢查（`application.yml`）| 漏設環境變數即可偽造任何身分（含 ADMIN）| `prod` profile 下缺少／過短／含 `dev-only` 的 `JWT_SECRET` 使應用**啟動失敗**（測試證明）| B1 |
| G2 | DB 密碼預設 `123456`、`useSSL=false`、Redis 無密碼且 compose 對外開埠 | 憑證與暴露面 | prod 拒絕預設／空白 DB 密碼；Redis 需密碼；正式 compose 不發布 3306／6379／11434 | B1＋B2 |
| G3 | `PAYMENT_PROVIDER=sandbox`、`API_DOCS_ENABLED=true` 在 prod 可被開啟 | 買家可自報已付款；文件外洩 | `prod` 下 provider=sandbox 啟動失敗；API 文件預設 404；provider=ecpay 時 ECPay 設定與 `PAYMENT_CALLBACK_SECRET` 缺漏即啟動失敗 | B1 |
| G4 | CORS 來源寫死 `http://localhost:5173`（`CorsConfig`）| 正式網域前端被擋 | `CORS_ALLOWED_ORIGINS` 可設定（逗號分隔，dev 預設不變，prod 必填）；測試涵蓋多來源與未知來源 | B1 |
| G5 | 無健康檢查端點（無 actuator）| 無法做容器 healthcheck、deploy health gate、回滾 | 加 `spring-boot-starter-actuator`，僅暴露 `health`（liveness/readiness），走**獨立 management 埠**（不發布），不改 `isPublicRoute` 公開清單 | B1 |
| G6 | 無 migration 機制：只靠 `initdb.d`（首次才跑），04～15 需手動套用；04_member／05 含 `DROP TABLE` | 部署即 schema 落後（2026-09-26 已實際發生）| 導入 Flyway：V1 = 目前完整 schema，既有庫以 baseline 接管；migration 全部非破壞性；空庫與既有庫兩條路徑都有 Testcontainers 測試 | B2 |
| G7 | 無前端映像（無 `frontend/Dockerfile`）；`VITE_API_BASE_URL` 建置期寫死 | 前端無法上線 | 前端建置產物由 Caddy 靜態託管，API 走同源 `/api` 反向代理（同源後 CORS 僅作保險）| B2 |
| G8 | 無 `docker-compose.prod.yml`／反向代理；容器以 root 執行；商品圖片目錄無持久 volume | 拓撲不存在、上傳檔隨容器消失 | 見 §3 B2 | B2 |
| G9 | 無 deploy job／環境核准／備份／回滾 | 無法可重複部署 | 見 §4 B3 | B3 |
| G10 | 日誌／指標／告警、SQL 效能、防火牆與映像掃描 | 營運可觀測與縱深防禦 | **不在本計畫**，屬 #19／#20／#21 | — |

## 2. B1 — 程式碼硬化（不需要任何主機）

- 範圍：`application-prod.yml`、啟動期設定驗證元件（`@Profile("prod")`）、`CorsConfig`、pom（actuator）、對應測試。
- 做法：
  - prod 設定**不給預設值**（缺少即啟動失敗）；驗證元件集中檢查 G1／G2／G3，錯誤訊息只寫「哪個設定不合格」，**不印出密鑰值**。
  - `DB_USE_SSL`、`REDIS_PASSWORD`、`CORS_ALLOWED_ORIGINS`、`MANAGEMENT_PORT` 走環境變數；dev 預設行為不變，既有 255 個測試必須全數維持通過。
  - `management.server.port` 獨立且不發布；`/actuator/health` 不進 `JwtAuthFilter.isPublicRoute`（避免動到 `OpenApiConfig` 與其比對測試）。
- 測試：每個 fail-fast 條件一個「啟動失敗」案例＋一個「合法設定通過」案例；CORS 多來源案例；health 只在 management 埠可達、主埠 404。
- 驗收：`mvn test` 全綠＋JaCoCo PASS；以 `SPRING_PROFILES_ACTIVE=prod` 實機分別用「缺密鑰」「合法設定」啟動，前者失敗、後者 health=UP。
- 風險：認證／設定路徑，屬 **Critical**，須走 C 階段獨立審查。

### 2.1 B1 實作結果（2026-09-26，Claude Code，分支 `feature/phase33-18-b1-prod-hardening`）

已完成 G1／G3／G4／G5，以及 G2 中「不合格的 DB／Redis 密碼」的部分；G2 的「compose 不發布埠」屬 B2。

- `application-prod.yml`（新）：`JWT_SECRET`、`DB_HOST/NAME/USERNAME/PASSWORD`、`REDIS_HOST`、`CORS_ALLOWED_ORIGINS` **無預設值**（缺少即解析失敗）；API 文件預設關；`management.server.port` 預設 8081。
- `ProductionConfigValidator`（新，`@Profile("prod")`）：JWT 密鑰 ≥32 bytes 且非 `dev-only`；DB 密碼非空且非常見預設；啟用 Redis 庫存時需 Redis 密碼；禁止 `payment.provider=sandbox`；`ecpay` 需六項 ECPay 設定；CORS 來源須為純 http(s) origin（拒絕 `*`、路徑）。一次列出全部違規，只點名設定、不印值（有測試）。
- `CorsConfig`：來源改讀 `cors.allowed-origins`（`CORS_ALLOWED_ORIGINS`），dev 預設仍為 `http://localhost:5173`。
- actuator：僅暴露 `health`、無細節；readiness = 資料庫，**Redis 刻意不列入**（庫存快取設計上降級回 DB，不應因 Redis 故障使部署閘門失敗或觸發重啟）；prod 走獨立 management 埠，主埠不提供 health，`JwtAuthFilter.isPublicRoute` 未改動。
- `DB_USE_SSL`（預設 false）、`REDIS_PASSWORD` 可由環境變數設定。README 新增 prod 設定表，並修正 Bash 匯出 `.env` 需 `tr -d ''`（實測 CRLF 會使值帶 ``）。

驗證：新增 19 個測試（`ProductionConfigValidatorTest` 13、`CorsConfiguredOriginsTest` 2、`ProductionProfileIntegrationTest` 4，real MySQL）；變異驗證：移除 prod yml 的 management 設定使整合測試 2 案失敗；backend `mvn test` 全數通過（Surefire 報告加總 278、0 failures/errors/skipped）＋ JaCoCo PASS；實機以 prod profile 對真實 MySQL 啟動：缺 `JWT_SECRET` → 啟動失敗；DB 密碼為開發預設 `123456` → 驗證元件拒絕且不印值。

**尚未證明**：prod profile 在真實堆疊（compose、反向代理）下的健康閘門（B2）；驗證元件是否可被繞過（例如未啟用 `prod` profile 就部署——這是部署腳本必須強制設定 `SPRING_PROFILES_ACTIVE=prod` 的理由，屬 B2／B3）；未經獨立審查（Critical，須走 C）。`pom.xml` 同一檔內含 Codex 的 Docker 29 `testcontainers.version` 覆寫（整合測試在本機需要它）。

## 3. B2 — 容器化與拓撲（仍不需要真的 VM）

- **Flyway 決策先行**：這是全計畫最大風險（既有開發庫版本不一、`04_member`／`05` 會 `DROP`）。B2 開頭先做小型設計＋spike：V1 合併語意、baseline 版本號、既有庫接管、`initdb.d` 退役。設計未通過前不進其餘 B2 工作。
- 交付：
  - `frontend/Dockerfile`（Node 建置 → 靜態檔）與 Caddyfile（自動 HTTPS、`/api`、`/uploads` 反向代理至 backend、前端靜態＋SPA fallback、基本安全標頭）。
  - `docker-compose.prod.yml`：mysql（具名 volume、**不發布埠**）、redis（密碼、不發布埠）、backend（非 root、`-XX:MaxRAMPercentage`、healthcheck 走 management 埠、`restart: unless-stopped`）、caddy（唯一對外 80／443）、上傳圖片具名 volume；映像以 `${IMAGE_TAG}`（commit SHA）指定，不用 `latest`。
  - `backend/Dockerfile` 改非 root 使用者；`.env.prod.example`（只放變數名與說明，**不含任何真值**）。
- **待決策（B2 開始前請使用者定）**：正式環境 AI 客服（RAG）用哪個 provider——自架 Ollama 需要大量記憶體，單台 VM 可能不划算；建議 prod 改 `claude` provider（需 `ANTHROPIC_API_KEY`）或先停用 support 功能。
- 驗收：在**本機**以 `docker compose -f docker-compose.prod.yml` 起完整堆疊（HTTP 憑證可用 Caddy 的 internal CA／localhost），瀏覽器走完「註冊→登入→加購物車→建立訂單」；`docker compose ps` 確認只有 caddy 發布埠；空庫與既有庫兩條 Flyway 路徑皆通過。

## 4. B3 — 部署管線（需要外部資源）

- 交付：GitHub Actions `deploy` job（僅 `advanced-v2`／`main` 推送或手動觸發）＋ **GitHub Environment `production` 必須人工核准**＋ SSH 部署腳本。
- 流程：核准 → **部署前備份**（沿用 `scripts/mysql-backup-restore` 的邏輯，Linux 版）→ 以 commit SHA 拉映像 → `compose up -d` → **health gate**（逾時即失敗）→ 失敗自動回滾到上一個 SHA ＋還原備份的操作手冊。
- 前置（**須由使用者帶外提供，不得寫入 repo、Notion 或對話**）：VM（Ubuntu LTS）、網域與 DNS A 記錄、SSH 金鑰、GitHub Environment secrets（`JWT_SECRET`、DB／Redis 密碼、`PAYMENT_CALLBACK_SECRET`、ECPay 正式 merchant／hash key／iv 等）。
- 驗收：一次真實部署成功、一次刻意失敗的部署自動回滾成功（`main` 服務不中斷可用）。
- 註：B3 在使用者提供 VM 之前**不能開始**；B1／B2 完成即可先驗收「可部署」，B3 才是「已部署」。

## 5. C — 獨立驗收（Critical，須全新脈絡）

- B1 與 B3 各一次獨立唯讀審查（建議 Codex 審 Claude 的實作；角色可依 `AGENTS.md` 對調）。審查清單：fail-fast 是否可被繞過（例如 profile 未啟用時的退化行為）、密鑰是否可能出現在日誌／health／錯誤訊息、埠暴露面、Flyway 對既有庫的破壞性、回滾是否真的可用、CORS／同源假設。
- 上線後 smoke（**非破壞性**，只在正式環境做唯讀與自建測試帳號）：HTTPS 憑證有效、health=UP、API 文件 404、sandbox 付款被拒、過期 token 回 401 且前端可讀（CORS 標頭在 401 上）、登入→下單流程、`docker ps` 僅 80／443 對外。
- **完整 k6 壓測只能在拋棄式環境跑，禁止對正式環境執行。**

## 6. 順序與停損

`B1 → B2（先 Flyway 設計）→ C(B1/B2 審查) → B3（等 VM）→ C(B3) → smoke`。每階段完成先更新 `docs/project-state.md` 再提交；B2 的 Flyway 設計若判定風險過高，允許退回「維持手動 migration＋`scripts/migrate.sh` 冪等執行器」的較小方案，但須在任務文件記錄取捨。

## 7. 明確不做（本計畫範圍外）

可觀測性（#19）、SQL 效能基線（#20）、防火牆／SSH 政策／映像掃描／SBOM／異地還原演練（#21）、ECPay **真實入站回調**證明與作品集收尾（#22）、k8s、多機高可用。
