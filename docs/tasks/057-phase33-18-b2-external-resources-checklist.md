# 057 — Phase 3.3 #18 B2 外部資源檢查清單

Date: 2026-09-29 Asia/Taipei. Mode: PLAN（規劃/文件整理，非實作）。
Baseline: advanced-v2 @ 1f18fde（已含 B1 044-C1 CORS 修正與 B2 Flyway adoption spike，兩者皆已 commit/push）。

## 前言與範圍

本文件只回答一個問題：**在開始寫 B2 的程式碼交付物（`frontend/Dockerfile`、`Caddyfile`、`docker-compose.prod.yml`、`backend/Dockerfile` 非 root 改動、`.env.prod.example`）之前，有哪些東西只有使用者本人能提供或決定？**

明確排除：
- 不設計 `docker-compose.prod.yml`／`Caddyfile`／`frontend/Dockerfile` 的實際內容——那是下一個 B2 實作任務的產出。
- 不建立 `.env.prod.example`——`docs/tasks/043-production-deployment-plan.md` §3 把它跟 compose 檔／backend Dockerfile 非 root 改動綁在同一個交付項裡，現在寫會搶先鎖定尚未定案的 volume/變數命名細節，之後兩個檔案容易對不上。本文件的憑證對照表（第 3 節）已先把「將對應哪個環境變數」列出來，供 B2 實作任務直接引用。
- 不蒐集、不記錄任何真實密鑰值、VM IP、網域名稱。
- 不建立 VM、不設定 DNS、不建立 GitHub Environment 或其 secrets。

以上四項都是使用者離開這個 session 之後、自行在 session 外完成的動作；本文件只是他的 to-do list 與「完成後要對到哪個環境變數」的對照表。這也延續 `docs/project-state.md` 既有的規則：「VM、DNS、SSH and production ECPay credentials must be supplied out of band and must never be recorded in the repository or Notion.」

## 銜接的既有缺口（`docs/tasks/043-production-deployment-plan.md` §1 缺口表）

| # | 內容 | 狀態 |
|---|---|---|
| G6 | 無 migration 機制，`04_member`/`05_password_reset_token` 對既有庫是破壞性的 | **已關閉**（task 053 的 Flyway adoption spike，独立審查 PASS，已 commit `1f18fde`）|
| G7 | 無 `frontend/Dockerfile`，`VITE_API_BASE_URL` 建置期寫死 | 待 B2 實作 |
| G8 | 無 `docker-compose.prod.yml`／反向代理；容器以 root 執行；商品圖片目錄無持久 volume | 待 B2 實作 |
| G9 | 無 deploy job／環境核准／備份／回滾 | 屬 B3，需要 VM 才能開始（見第 6 節） |

本清單要解決的是「G7/G8/未來 G9 開工前，使用者要先準備什麼」，不是 G7/G8 本身。

## 0. 決策結果（2026-09-29，使用者裁定）

| 決策 | 結果 |
|---|---|
| 1.1 AI 客服 provider | **維持自架 Ollama**（非停用、非切 Claude）——VM 規格必須預留模型記憶體，見第 2 節 |
| 1.2 GHCR 映像可見度 | **Public** |
| 1.3 正式網域 | **DuckDNS**，網域為 `whitehot0321.duckdns.org`（使用者已自行完成 DuckDNS 註冊；DuckDNS 的 update token 是機密，只存放在未來 VM 本機的動態更新腳本裡，不進 repo/Notion/對話紀錄）。目前該網域尚未綁定任何 IP（VM 還不存在），等 VM 有公網 IP 後才需要手動或用排程腳本更新。 |

以下 1.1-1.3 的取捨分析保留作為決策依據紀錄；決策本身以上表為準。

## 1. 待決策（B2 開始前，使用者裁定）

### 1.1 正式環境 AI 客服（RAG）provider

README.md 現況（已逐行核對）：
- README.md:383-384：目前 RAG 客服功能預設用本機 Ollama，「介面設計成之後可平滑切換為 Claude API」。
- README.md:420：錯誤情境明確寫「切換為 Claude 但尚未實作 → 501」。
- README.md:427-431：切換方式是設定 `llm.provider: claude`（或環境變數 `LLM_PROVIDER=claude`）＋ `ANTHROPIC_API_KEY`，但「目前 `ClaudeLlmClient` 僅為框架 stub（尚未實作實際呼叫），呼叫客服 API 會回傳 501」。

三個選項，各自的代價：

| 選項 | 需要準備 | 代價 |
|---|---|---|
| a) 繼續自架 Ollama | VM 要有足夠記憶體跑模型（7B 等級模型通常建議 ≥8GB 可用記憶體，需視實際選用模型再估） | 單台小 VM 上要跟 MySQL/Redis/backend/frontend/Caddy 搶記憶體，可能是整個拓撲裡最貴的資源需求 |
| b) 切到 Claude API | `ANTHROPIC_API_KEY`；**必須先實作 `ClaudeLlmClient` 的真實呼叫邏輯** | 這代表選這項等於把「實作 Claude client」加進 B2（或之前）的範圍，不是免費切換 |
| c) 正式環境停用客服功能 | 不需要額外資源 | 風險最低、成本最低，之後仍可再啟用；不影響 B2 其餘容器拓撲驗收 |

**這是一個會影響第 2 節 VM 規格估算的決策，建議在開始寫 `docker-compose.prod.yml` 之前先選定。**

### 1.2 GHCR 映像可見度：public 還是 private

現況（`.github/workflows/ci.yml` 的 `publish-image` job 已核實）：只發布 backend 映像到 `ghcr.io/<repo>/backend`，用內建 `GITHUB_TOKEN`（job 有 `packages: write`）推送，沒有任何地方決定這個套件本身是 public 還是 private——這是 GHCR 套件層級的設定，不是工作流程檔案能決定的。

| 選項 | 影響 |
|---|---|
| public | VM 上 `docker pull ghcr.io/<repo>/backend:<tag>` 不需要登入，`docker-compose.prod.yml` 的 `image:` 欄位可以直接寫 |
| private | VM 需要先 `docker login ghcr.io`，需要一組有 `read:packages` 權限的憑證（PAT 或其他），這組憑證本身也要收進第 3 節的憑證清單、放在 VM-local，不進 repo |

frontend 映像目前完全不存在（沒有 `frontend/Dockerfile`），這個可見度決策屆時也適用於未來的 frontend 映像。

### 1.3 正式網域名稱

Caddy 的自動 HTTPS（`Caddyfile`）與 DNS A/AAAA 記錄都需要先有一個確定的網域名稱（例如 `shop.example.com`）才能繼續設計。這件事本身不是「密鑰」，但屬於「只有使用者能決定/擁有」的前提，排在這裡一併列出。

## 2. 實體基礎設施（需使用者提供或建立）

- [ ] **Ubuntu LTS VM 一台**（單機拓撲，已定案於 043）。**已確定維持自架 Ollama**，規格必須把模型常駐記憶體算進去，不能只算 MySQL/Redis/backend/frontend/Caddy 的入門級規格；實際選用模型與對應記憶體需求待 B2 本機驗收（用 `docker stats` 量測）時再定案，選 VM 方案時要抓比純 web 服務更高一級的記憶體。
- [ ] **對外防火牆/安全群組只開放 80/443**（呼應 `docs/tasks/056-phase33-21-production-security-scoping.md` 已定案的最終拓撲限制：MySQL/Redis/Ollama/backend app port/management port 一律只留在內部 Docker network，不對外）。
- [x] ~~網域名稱~~——**已定案**：DuckDNS `whitehot0321.duckdns.org`。仍待辦：VM 建好、拿到公網 IP 後，把 A 記錄指過去（手動填 DuckDNS 網頁，或在 VM 上排程呼叫 DuckDNS 更新 API）；此為 B3 工作。
- [ ] **部署用 SSH 金鑰對**：私鑰存放位置由使用者決定（建議之後作為 B3 的 GitHub Environment secret），公鑰裝到 VM 的 `authorized_keys`。
- [ ] **SSH 存取限制**（若雲端平台支援）：只允許特定來源 IP 或至少不對整個網際網路開放密碼登入。

以上都不需要現在動手做——B2 本身「還不需要真的 VM」（見 043 §3 開頭），這節只是先讓使用者知道規格/清單，實際建立可以留到接近 B3 時再進行。

## 3. 憑證／密鑰對照表

只記錄「需要什麼」「對應哪個環境變數」「未來放在哪裡」——**完全不填入任何真實值**。

| 用途 | 環境變數 | 來源 | 存放位置 | 何時才需要真值 |
|---|---|---|---|---|
| JWT 簽章密鑰 | `JWT_SECRET`（≥32 bytes，非 `dev-only` 開頭） | 使用者產生（如 `openssl rand -base64 32`） | GitHub Environment secret（B3）+ VM-local `.env.prod`（B2 本機驗收） | B2 本機驗收就需要一組測試用值（可以是隨機產生、非正式) |
| 資料庫密碼 | `DB_PASSWORD`（非空、非常見預設） | 使用者產生 | 同上 | 同上 |
| 資料庫連線資訊 | `DB_HOST` / `DB_NAME` / `DB_USERNAME` | 依 compose 內部拓撲決定（通常是 compose service 名稱，非密鑰） | 同上 | B2 |
| Redis 密碼 | `REDIS_PASSWORD` | 使用者產生 | 同上（僅 `STOCK_REDIS_ENABLED=true` 時 `ProductionConfigValidator` 強制要求非空） | 若啟用 Redis 庫存快取才需要 |
| CORS 白名單 | `CORS_ALLOWED_ORIGINS`（純 http(s) origin，逗號分隔，禁止 `*`/路徑/user-info/query） | `https://whitehot0321.duckdns.org`（已定案網域，DuckDNS 免費子網域） | 同上 | B2（B2 本機驗收若還沒有真實 VM/憑證，可先用 localhost 測試，B3 才需要真的對到這個網域） |
| 金流模式 | `PAYMENT_PROVIDER`（`none` 或 `ecpay`，`ProductionConfigValidator` 拒絕 `sandbox`） | 決策 | 同上 | **B2 本機驗收建議先用 `none`**，避免把 ECPay 真實憑證需求提前綁進 B2 |
| 金流回呼密鑰 | `PAYMENT_CALLBACK_SECRET` | 使用者產生 | 同上 | 僅 `provider=ecpay` 時必填 |
| ECPay 商店設定（六項） | `ECPAY_MERCHANT_ID` / `ECPAY_HASH_KEY` / `ECPAY_HASH_IV` / `ECPAY_PAYMENT_URL` / `ECPAY_CALLBACK_URL` / `ECPAY_RETURN_URL`（`application.yml:94-100`，皆為空字串預設） | 向 ECPay 申請正式商店取得 | 同上 | **只在 #22 上線驗收（真實入站回調）才需要真值**；B2/B3 都可以維持 `PAYMENT_PROVIDER=none` |
| ~~（若選 1.1 的 Claude provider）Anthropic 金鑰~~ | `ANTHROPIC_API_KEY` | Anthropic 帳號 | 不適用 | **不需要**——1.1 已定案維持自架 Ollama，此列僅保留作為決策依據紀錄 |
| （若選 1.2 的 GHCR private）映像拉取憑證 | 無固定環境變數名（VM 本機 `docker login` 用） | GitHub PAT（`read:packages`）或等效 token | VM-local，不進 repo，不進 GitHub Environment（因為是 VM 拉取用，不是應用程式讀取） | 若決定 private 才需要 |

有預設值但安全相關、不算「需要使用者提供的秘密」，僅供對照：`DB_PORT`(3306)、`DB_USE_SSL`(false，僅同私網安全)、`API_DOCS_ENABLED`(false)、`MANAGEMENT_ADDRESS`(127.0.0.1)、`MANAGEMENT_PORT`(8081)。

每一列現階段都只是「B2 需要知道名稱與存放位置」；真正把它們接進部署流程（GitHub Environment secrets 的實際建立、deploy job 讀取）是 B3 的工作，本文件不動手做。

## 4. GHCR 映像存取決策的後續影響

- 選 **public**：`docker-compose.prod.yml` 的 `image: ghcr.io/<repo>/backend:${IMAGE_TAG}` 可以直接拉取，VM 不需要額外設定。
- 選 **private**：VM 部署腳本（B3）需要先執行 `docker login ghcr.io`，對應憑證見第 3 節最後一列；這個決策也會套用到未來新增的 frontend 映像。

## 5. 明確不需要（現在）

- **正式 ECPay 真實商店資料**（merchant id/hash key/iv/callback）——只在 #22 上線驗收（真實入站回調證明）才需要；B2 自身的本機驗收與 B3 的首次真實部署都可以維持 `PAYMENT_PROVIDER=none`。
- **B3 的 deploy job／GitHub Environment 人工核准設定**——屬於 B3 範圍，本清單只在第 3 節預先點名將來對應到哪些 secret，不在這裡建立。
- **備份／回滾腳本、監控告警／SQL 效能基線／防火牆政策與映像掃描**——分別屬於 B3（備份沿用 `scripts/mysql-backup-restore` 的 Linux 版）與 #19/#20/#21，依 043 §1 的 G10 決議明確排除於本計畫外。
- **真的建立 VM／設定 DNS／建立 GitHub Environment secret**——這些是使用者離開本 session 之後才會做的事，本文件只是他的 to-do list，不代表任何一項已經完成。

## 6. 完成後動作

1. 使用者依第 1 節做出三項決策（AI provider、GHCR 可見度、網域名稱）。
2. 使用者依第 2 節備妥基礎設施規格（此階段可以先只是「知道要準備什麼」，不必真的建好 VM——B2 本機驗收不需要真實 VM）。
3. 回到 session 後，開始 B2 實際實作：`frontend/Dockerfile`、`Caddyfile`、`docker-compose.prod.yml`、`backend/Dockerfile` 改非 root、`.env.prod.example`（此時才依第 3 節的對照表填入變數名稱，仍不含真值）。
4. B2 本機驗收通過後，才進入 B3（此時才需要使用者真正提供 VM/DNS/SSH/GitHub Environment secrets）。

## Measurement

本任務為純文件規劃，未執行任何測試、未修改任何程式碼/設定檔/CI 檔案、未 commit/push（提交時機另行確認）。所有環境變數名稱與行為描述均逐項核對下列來源：`docs/tasks/043-production-deployment-plan.md`、`backend/src/main/resources/application-prod.yml`、`backend/src/main/resources/application.yml`（ECPay 區塊，第 94-100 行）、`backend/src/main/java/com/esun/shop/config/ProductionConfigValidator.java`、`README.md`（prod 設定表與 RAG 章節，第 420/427-431 行）、`docs/tasks/056-phase33-21-production-security-scoping.md`。
