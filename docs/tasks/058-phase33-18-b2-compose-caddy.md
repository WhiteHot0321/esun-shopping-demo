# 058 — Phase 3.3 #18 B2 容器拓撲實作（Compose／Caddy）

Date: 2026-09-29 Asia/Taipei. Mode: IMPLEMENT。
Baseline: advanced-v2 @ c7940d8（承接已定案的 B2 三項決策：自架 Ollama、GHCR public、DuckDNS `whitehot0321.duckdns.org`）。

## 範圍

依 `docs/tasks/043-production-deployment-plan.md` §3 的 B2 交付項清單，實作單機 Docker Compose ＋ Caddy 反向代理拓撲，關閉 G7（無 `frontend/Dockerfile`）與 G8（無 `docker-compose.prod.yml`／容器以 root 執行／商品圖片無持久 volume）。不含 B3（部署管線、真實 VM、GitHub Environment）與 C（獨立驗收），也不修改任何業務邏輯程式碼。

## 變更檔案

- `backend/Dockerfile`：加入非 root 使用者（`spring`）、安裝 `curl`（僅供 compose healthcheck 使用）、`ENTRYPOINT` 改為 shell form 以支援執行期 `JAVA_OPTS`（例如 `-XX:MaxRAMPercentage`）、`EXPOSE` 加上管理埠 8081、建立 `/app/uploads` 並修正擁有者。
- `frontend/Dockerfile`（新）：兩階段建置——`node:20-alpine` 執行 `npm ci && npm run build`（`VITE_API_BASE_URL` 建置期預設 `/api`，因為 Caddy 同源反向代理，SPA 永遠不需要寫死後端網域，解決 G7 的「建置期寫死」問題）；最終階段 `FROM caddy:2-alpine`，把建置產物與 `Caddyfile` 一併放進映像——這個映像同時扮演「前端靜態檔」與「反向代理／TLS 終止」兩種角色，是整個拓撲中唯一對外發布埠的容器。
- `frontend/Caddyfile`（新）：`{$DOMAIN:localhost}` 站台，`/api/*` 與 `/uploads/products/*` 整段路徑原樣反向代理到 `backend:8080`（backend 本身就是用這兩個前綴提供服務，不需要 strip prefix），其餘路徑走 SPA fallback（`try_files {path} /index.html`）＋基本安全標頭（`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`）。`DOMAIN` 未設定時預設 `localhost`，Caddy 自動改用內建自簽 CA，不會嘗試對外 ACME 憑證申請——這讓同一份 Caddyfile 同時支援「本機驗收」與「真實網域上線」兩種情境。
- `frontend/.dockerignore`（新）：排除 `node_modules/`、`dist/`、`.env*`。
- `docker-compose.prod.yml`（新，repo 根目錄）：`mysql`（**不掛載 `docker-entrypoint-initdb.d`**——刻意不跑舊的 `backend/DB/*.sql` legacy 鏈，schema 改由 backend 內建的 Flyway 在 `FLYWAY_ENABLED=true` 時建立/遷移，對應 043 acceptance 的「Compose 不可同時跑 legacy init 腳本與 Flyway」）、`redis`（`--requirepass`，具名 volume）、`ollama`（具名 volume，供之後 `docker compose exec ollama ollama pull <model>` 手動拉模型）、`backend`（非 root、`JAVA_OPTS=-XX:MaxRAMPercentage=75`、healthcheck 打 management 埠的 `/actuator/health`、商品圖片具名 volume 掛在 `/app/uploads`）、`caddy`（唯一發布 80/443 的容器，掛 `caddy-data`/`caddy-config` 持久化 TLS 憑證與狀態）。`backend`／`caddy` 都同時宣告 `build:` 與 `image: ${BACKEND_IMAGE:-esun-shopping-backend:local}` / `${FRONTEND_IMAGE:-esun-shopping-frontend:local}`——本機驗收用 `--build` 直接本地建置並打上 `:local` tag；真正部署（B3）時改設定 `BACKEND_IMAGE`/`FRONTEND_IMAGE` 指到 GHCR 的 commit-SHA tag，改用 `pull` 而不 `--build`，兩種模式共用同一份 compose 檔。
- `.env.prod.example`（新，repo 根目錄）：只列變數名稱與說明，不含任何真值，逐項對應 `docs/tasks/057` 第 3 節的憑證對照表。
- `.gitignore`：新增 `.env.prod`（先前只忽略 `.env`，不會匹配 `.env.prod`，屬於這次施工時發現並修正的小缺口）。

## 已知、刻意不做的事（明確排除，非遺漏）

- **未修改 CI**：目前只有 backend 映像會發布到 GHCR；frontend/caddy 映像還沒有對應的 publish job，這份拓撲設計上已經相容（`FRONTEND_IMAGE` 變數預留），但實際接上 CI 是獨立的小任務，不在本次範圍。
- **DB 使用者仍是 `root`**：沿用現有開發環境的慣例（`DB_USERNAME=root`），沒有另外建立最小權限的應用程式專用帳號；這屬於可另外排的強化項，不是 B2 交付清單要求的項目。
- **Ollama 模型不會自動拉取**：容器啟動後模型倉庫是空的，需要人工執行一次 `docker compose exec ollama ollama pull llama3.1` / `nomic-embed-text`；沒有做成啟動時自動拉取（避免每次啟動都嘗試下載大檔案拖慢啟動）。
- **DuckDNS 網域尚未綁定 IP**：因為還沒有真實 VM，`DOMAIN` 這次驗收全程使用預設值 `localhost`（Caddy 內建自簽 CA），沒有實際測試對 `whitehot0321.duckdns.org` 的 ACME 憑證簽發流程；這件事要等 B3 真的有 VM 公網 IP 才能驗。

## 驗證

**本機完整堆疊啟動**（`docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d`，`.env.prod` 是本次驗收用的隨機測試值，非真實密鑰，未提交）：五個容器（mysql/redis/ollama/backend/caddy）全部啟動，`backend` 與 `mysql`/`redis` 皆回報 `healthy`。

**`docker compose ps` 埠檢查**：只有 `caddy` 發布 `0.0.0.0:80`／`0.0.0.0:443`；`backend`（8080-8081）、`mysql`（3306/33060）、`redis`（6379）、`ollama`（11434）皆無對外發布埠，符合 043 acceptance「只有 caddy 發布埠」。

**Flyway 空庫路徑**：backend 日誌顯示 `Current version of schema esun_shop: << Empty Schema >>` 之後 `Migrating schema esun_shop to version "1 - baseline schema"` 成功——證明拿掉 legacy initdb.d 掛載後，Flyway 確實從空白資料庫正確建表。既有庫 adoption 路徑已由 task 053 的 `FlywayAdoptionIntegrationTest`（1/1 PASS）另外驗證，本次不重複測。

**經由 Caddy 的完整購物流程**（全部走 `https://localhost`，Caddy 反向代理，非直接打 backend）：
1. `POST /api/auth/register` 註冊賣家、買家帳號皆 200。
2. 以 `UPDATE member SET role='SELLER'`（透過 `docker compose exec mysql`，僅本機測試操作）把賣家帳號升級角色——這是唯一繞過 API 的步驟，因為專案本身沒有自助升級賣家角色的端點；其餘步驟全部走真實 REST API。
3. 賣家重新登入取得新 JWT（`role: SELLER`）、`POST /api/products` 建立測試商品，200。
4. 買家 `POST /api/member/addresses` 新增收件地址、`POST /api/cart/add` 加入購物車、`POST /api/cart/checkout` 結帳，皆 200；回傳 `orderId`。
5. `GET /api/orders` 確認訂單 `status: CREATED`、`payStatus: PENDING`、金額 398.00（2 件 × 199）正確。
6. `GET /api/products/available` 確認庫存從 10 正確扣減為 8。

**未做**：瀏覽器 UI 端到端測試——這個環境裡 Claude 內建瀏覽器的網路命名空間跟實際跑 Docker 的主機不是同一個（瀏覽器的 `localhost`打到主機上另一個既有的 AppServ 服務，摸不到這次的 Docker 容器），因此改用上面完整的 REST API 走查代替；未跑前端 Vitest/production build 的額外驗證（未改動前端程式碼，只改建置設定，風險低）；未做 HTTPS 真網域憑證簽發驗證（見上「刻意不做」）。

**收尾**：驗證後執行 `docker compose -f docker-compose.prod.yml down` 完整移除本次測試容器與網路；具名 volume（`mysql-data` 等）保留，供之後重跑沿用；沒有影響機器上其他既有容器（同機另有不相關的 `postgres`/`redis` 開發容器，事前事後皆未受影響）。

## 下一步

B2 本機驗收通過，`docs/project-state.md` 的 G7/G8 缺口關閉。下一個任務是 B3（GitHub Actions `deploy` job、GitHub Environment 人工核准、部署前備份、health gate、失敗自動回滾）——需要使用者提供真實 VM（見 `docs/tasks/057` 第 2 節）才能開始。
