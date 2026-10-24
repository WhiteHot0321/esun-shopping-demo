# 面試準備：esun-shopping 後端深度 + REST/Token 安全 + 邏輯題 + 雲端話術

> 依據 2026-10-22 讀到的實際程式碼寫成。每個「我做了什麼」都對得到檔案；每個「還沒做」也明講，面試被追問時不會破功。

---

## 1. JWT

**程式位置**：`security/JwtService.java`、`security/JwtAuthFilter.java`

### 30 秒版
> 登入成功後簽發 HS256 JWT，claims 有 `sub`(email)、`role`、`ver`(token_version)、`iat`、`exp`(預設 1 小時)。`JwtAuthFilter` 是 `OncePerRequestFilter`，在 MVC 之前驗簽章、驗過期，**再查一次 DB** 比對帳號狀態與 `token_version`。所以它是「無狀態驗證 + 一次索引查詢換即時撤銷」的混合設計。

### 為什麼不是純 stateless
純 JWT 的缺點：簽出去就收不回，直到過期。Phase 4.#3 做 admin 改角色/停用帳號時，要求「舊 token 立刻 401」。
做法：`member` 加 `status`、`token_version`；admin 每次變更就 `token_version + 1`；filter 每個請求 `findAuthState(email)`，不是 ACTIVE、`ver` 缺漏、`ver` 不等於 DB 值，一律 401（`JwtAuthFilter.java:77-82`）。
**代價**：每個請求多一次 DB 查詢（走索引）→ 失去「完全不碰 DB」的水平擴展優勢；若要降低，可用 Redis 快取 `token_version`（TTL 幾秒）。

### 被追問的細節
| 問題 | 答案 |
|---|---|
| 為什麼用 HS256？ | 單服務、單一簽發者，對稱金鑰最簡單。`Keys.hmacShaKeyFor` 金鑰 <256 bit 啟動直接失敗（fail fast）。多服務驗證時改 RS256/EdDSA，驗證端只需公鑰。 |
| alg=none / 演算法混淆攻擊？ | jjwt 0.12 的 `verifyWith(key)` 綁定金鑰型別，`alg=none` 的 token 會被拒；不自己手寫 header 解析。 |
| 金鑰洩漏？ | 輪替 `JWT_SECRET`，所有 token 立刻失效（已在 SEC-03 演練過 rotation drill，舊憑證被拒）。更好的做法是 `kid` header 支援雙金鑰並行。 |
| 為什麼用 email 當 subject？ | 坦白講：email 可能被改，穩定識別碼（member id / UUID）更好。這是已知取捨。 |
| 密碼怎麼存？ | `AuthService` 用 BCrypt（含 salt、可調 cost）。 |

### 現況的誠實缺口（先自己講，比被問到好）
1. **沒有 refresh token**：access token 1 小時，過期就重新登入。
2. **前端存 `localStorage`**（`frontend/src/stores/auth.js`）：XSS 可竊取。靠 Caddy 的 CSP 降低風險，但不是根治。
3. **改密碼 / 重設密碼尚未 bump `token_version`**（project-state 明列 Not done）。

---

## 2. 冪等（Idempotency）

**程式位置**：`OrderTransactionService.doCreateOrder`、`OrderRepository.claimRequest`

### 30 秒版
> 客戶端每次結帳帶一個 `requestId`。後端在**同一個交易的第一步**對 `order_request(request_id PK)` 做 INSERT 搶佔。成功 → 繼續建單；撞 PK（`DuplicateKeyException`）→ 查出原訂單回傳，`newlyCreated=false`，不再扣庫存。

### 為什麼這樣設計
- **用 DB 唯一鍵當鎖**，而不是「先 SELECT 再 INSERT」（check-then-act 有競態）。
- **兩個併發重送**：第二個 INSERT 會卡在 PK 的 lock 上，等第一個 commit（→ 撞鍵、回傳原單）或 rollback（→ 自己接手）。所以**不會重複扣庫存**。
- **搶佔在 Redis 預扣之前**（註解寫明）：重送的請求不會第二次動 Redis。
- 歸屬檢查：撞鍵後比對 `memberId`，不同會員用同 requestId → 409，避免跨使用者洩漏訂單。
- 重送回傳「同一個 orderId」→ 對客戶端而言是**同一結果**，這才是冪等的定義（不是「第二次報錯」）。

### 深一層：MySQL 細節
撞鍵後同一交易再 `SELECT`。MySQL 不像 PostgreSQL，duplicate key 不會讓交易進入 aborted 狀態，所以能繼續查。REPEATABLE READ 的 snapshot 是**第一次一致性讀**才建立，而本交易第一個語句是 INSERT（不建 snapshot），所以後面 SELECT 看得到對方已 commit 的列。如果前面先做過普通 SELECT，就可能讀不到——這是順序不能亂動的原因。

### 誠實缺口
- **沒有 payload 雜湊比對**：同 requestId、不同內容 → 靜默回傳原單。Stripe 的做法是回 422。可補：`order_request` 加 `payload_hash`，不一致就 409/422。
- `requestId` 放 body；業界較常見是 `Idempotency-Key` header（跨端點一致）。
- `order_request` 沒有過期清理（Stripe 保留 24h）。

### 冪等 vs 防重複點擊
前端 disable 按鈕只是 UX；真正的保證在後端唯一鍵。**不要把保證放在前端**。

---

## 3. Redis + Lua 庫存

**程式位置**：`resources/redis/stock-decrease.lua`、`StockCacheService`

### 30 秒版
> 熱門商品庫存先在 Redis 預扣，擋掉大部分搶購流量，不讓請求全打到 MySQL 行鎖。Lua script 在 Redis 內「檢查所有商品庫存 → 全部足夠才全部 DECRBY」，整段原子。MySQL 仍是最終真相來源。

### Lua 為什麼
Redis 單執行緒執行命令，Lua script 執行期間不會插入其他命令。如果在 Java 裡 `GET` → 判斷 → `DECRBY`，兩個請求可能同時讀到 1，都判斷成功 → 超賣。Lua 把 check-and-decrement 合成不可分割的一步。

### script 做了什麼
1. 驗證每個數量是正整數，否則 `error_reply`。
2. **同一商品重複出現先加總**（`totals`），避免購物車有兩行同商品時每行都「看起來夠」。
3. 先**全部檢查**，任一不足 → 回 `{0, productId}`，**什麼都不扣**（多商品全有或全無）。
4. key 不存在 → 回 `{2, key}`（不當作缺貨）。
5. 全部通過才 DECRBY，回 `{1, ''}`。

### 一致性：Redis 與 MySQL 兩個地方有庫存
- **Redis 預扣成功，但後面 MySQL 交易失敗** → `OrderService` catch 後呼叫 `compensate`（另一支 Lua，INCRBY 還原）。補償放在交易代理 rollback **之後**。
- **Redis 掛了 / key 遺失** → `degraded` latch，退回 DB-only 模式。理由：寧可變慢，也不要因快取問題讓商品賣不出去。latch 只能維護對帳後重啟才解除，健康檢查**不會**自動清除（避免在未對帳時回到 Redis）。
- **新增/補貨**：commit **之後**才同步 Redis（`seed` 用 `setIfAbsent`，`increase` 用 INCRBY），避免交易還沒成功就改快取。
- **對帳**：`@Scheduled auditScheduled` 每 60 秒比對 Redis vs DB，有 drift 就告警。
- **真正兜底**：MySQL 的 `sp_decrease_stock` 仍做扣減，Redis 算錯也不會真的超賣。

### 被追問
| 問題 | 答案 |
|---|---|
| Redis Cluster 呢？ | 多 key 的 Lua 要求 key 在同一 slot，否則 `CROSSSLOT`。需要 hash tag（`{cart}`）或改單商品 script。目前是單機 Redis，這是已知限制。 |
| Redis 重啟資料丟了？ | 是快取非真相：啟動時 `preload` 用 `setIfAbsent` 從 DB 載入，不覆蓋其他實例的預扣。 |
| 為什麼不用 Redisson 分散式鎖？ | 鎖的是臨界區，吞吐低；Lua 直接把「判斷+扣減」變成單一原子命令，更簡單更快。 |
| 補償本身失敗？ | 記 error、latch DB-only、靠 audit 對帳發現。這是最終一致，不是強一致，要講清楚。 |
| 為什麼 `pairs(totals)` 順序不定沒關係？ | 整支 script 原子執行，順序不影響結果。 |

---

## 4. Deadlock 處理

**程式位置**：`OrderTransactionService`（排序）、`OrderService`（重試）

### 為什麼會 deadlock
交易 A 先鎖商品 1 再等商品 2；交易 B 先鎖商品 2 再等商品 1 → 循環等待。InnoDB 偵測到後殺掉其中一個（`DeadlockLoserDataAccessException`）。

### 三層防線（講這個結構最加分）
1. **預防：固定加鎖順序**。扣庫存前把 items 依 `productId` 排序（`OrderTransactionService.java:124`）；優惠券行鎖也規定在商品行之前（全域 lock order）。破壞「循環等待」這個必要條件。
2. **容忍：重試**。`@Retryable` 針對 `CannotAcquireLockException` / `DeadlockLoserDataAccessException`，最多 3 次，exponential backoff 50ms → 200ms 並加 **random jitter**（避免兩個失敗者同時重試又撞）。
3. **放棄：明確失敗**。重試耗盡 → `@Recover` → `ConcurrentOrderException`，並記 `shop.orders.lock.exhausted` metric。

### 關鍵設計點
- **重試包在交易外面**：`OrderService`（無 `@Transactional`）呼叫 `OrderTransactionService`（有 `@Transactional`），每次重試都是**全新交易**。如果 `@Retryable` 和 `@Transactional` 在同一個 bean，重試會在已標記 rollback-only 的交易裡跑。
- **重試要冪等**：重試時 `requestId` 不變，第一次若已 claim 成功但整體 rollback，claim 也一起 rollback，重來不會誤判為重送。
- **Redis 預扣在每次重試前要補償**，否則會重複預扣。
- **可觀測**：`shop.orders.lock.retry` / `exhausted` counters + 告警規則；有 k6 deadlock 壓測並對帳。

### 被追問
- **為什麼還需要重試，排序不是已經防止了？** 排序只防「商品行」；間隙鎖、外鍵共享鎖、不同表的順序都可能造成其他循環，InnoDB 不保證零死結，重試是安全網。
- **Java 排序和 MySQL 排序一致嗎？** 不一定（collation）。程式註解已特別處理，以 Java 字串順序為準，全程只用同一套順序。這是個很好的「我踩過坑」故事。
- **為什麼不用樂觀鎖？** 高競爭商品會大量失敗重試；本案例熱點商品用悲觀行鎖 + Redis 前置較穩。Phase 2 清單有「樂觀 vs 悲觀比較」可以說明取捨。

---

## 5. REST 設計

### 原則（面試講法）
- **資源用名詞、複數**：`/api/orders`、`/api/orders/{id}`；動作用 HTTP 方法表達。
- **方法語意**：GET 安全+冪等；PUT/DELETE 冪等；**POST 非冪等**（所以才需要 requestId / Idempotency-Key）；PATCH 部分更新（專案的會員角色/停用用 PATCH）。
- **狀態碼要對**（專案 Phase 1 #4 就是修「全部回 200」）：
  - 200 OK / 201 Created(+`Location`) / 204 No Content
  - 400 格式錯誤、驗證失敗；401 沒登入或 token 失效；403 已登入但沒權限；404 不存在
  - 405 方法不允許(+`Allow`)；409 衝突（庫存不足、重複、最後一個 ADMIN）；422 語意錯誤；429 限流
  - 500 才是真的 bug
- **401 vs 403**：401 = 你是誰我不知道；403 = 我知道你是誰但不行。停用帳號登入回 403（密碼對但被禁止）是合理的，token 失效回 401 讓前端強制登出。
- **分頁/過濾**：`GET /api/orders?status=&payStatus=&page=&size=`，回傳 total；大資料量改 cursor-based（OFFSET 越深越慢）。
- **統一錯誤格式**：`ApiResponse.fail(message)`；進階可採 RFC 9457 `application/problem+json`。
- **版本**：URL `/v1` 或 header；原則是新增欄位向後相容，破壞性變更才升版。
- **冪等**：POST 建立類操作用 `Idempotency-Key`。
- **安全**：所有輸入驗證（`@NotNull` 等）、不信任 client 傳的價格/`payStatus`（專案刻意忽略 client 的 payStatus，價格由伺服器重算）、CORS 白名單、避免在 URL 放敏感資料。
- **文件**：springdoc OpenAPI，公開路由清單與 filter 共用同一個來源，避免兩份清單漂移。

### 常被問
- **PUT vs PATCH？** PUT 是整體取代（冪等），PATCH 是局部變更。
- **為什麼取消訂單不用 DELETE？** 訂單是帳務資料，取消是狀態轉移（`POST /orders/{id}/cancel` 或 PATCH status），並要寫狀態歷史，不是刪資料。
- **HATEOAS 需要嗎？** 實務很少用，說明知道概念即可。

---

## 6. Token 安全：過期、刷新、被盜

### 設計答案（建議面試時用這個架構回答）

**過期**
- Access token 短效（5–15 分鐘）；越短，被盜的有效窗口越小。
- 驗證 `exp`、`iat`、必要時 `nbf`、`iss`、`aud`；伺服器容許極小時鐘偏移。

**刷新**
- 長效 **Refresh token**（7–30 天）：隨機不透明字串（不是 JWT 也可），**DB 只存雜湊**，綁裝置/使用者。
- 放 **HttpOnly + Secure + SameSite=Strict/Lax cookie**，JS 讀不到 → 防 XSS 竊取；Access token 放記憶體，不放 localStorage。
- **Refresh token rotation**：每次刷新發新的、舊的作廢。
- **Reuse detection**：已被作廢的 refresh token 又被拿來用 → 代表被盜 → 撤銷整個 token family，強制重新登入。
- 刷新端點要限流。

**被盜怎麼辦（分情境）**
| 情境 | 作法 |
|---|---|
| 用戶回報/偵測到異常 | 撤銷該使用者所有 refresh token；`token_version + 1`（本專案已有機制，舊 access token 立即 401）。 |
| 改密碼/重設密碼 | 一律 bump `token_version`、撤銷所有 session。（**本專案尚缺這一步**，我會補。） |
| Access token 外洩 | 靠短效 + `token_version` / `jti` denylist（Redis，TTL = 剩餘有效時間）。 |
| Refresh token 外洩 | rotation + reuse detection 偵測。 |
| 簽章金鑰外洩 | 輪替金鑰、`kid` 支援雙金鑰過渡、全體失效。 |
| 偵測 | 記錄登入 IP/UA/裝置，異地登入通知，失敗登入限流。 |

**傳輸與儲存**
- 全程 HTTPS + HSTS；不把 token 放 URL。
- XSS：CSP、輸出轉義；CSRF：用 cookie 就要 SameSite + CSRF token 或自訂 header。
- 不在 JWT payload 放敏感資料（payload 只是 base64，不是加密）。

**localStorage vs cookie 取捨（一定會被問）**
- localStorage：XSS 可讀，但沒有 CSRF 問題。
- HttpOnly cookie：XSS 讀不到，但要處理 CSRF。
- 結論：refresh token 用 HttpOnly cookie，access token 放記憶體，是目前較常見的平衡。

### 對照本專案（誠實回答）
> 現在是單一 1 小時 access token，加上 `token_version` 做即時撤銷。這解決了「管理者變更權限/停用帳號」要立刻生效。沒做的是 refresh token 與改密碼撤銷；如果要上線，我會做 refresh rotation + HttpOnly cookie，並把 access token 縮短到 15 分鐘。

---

## 7. 邏輯題

### 解題框架（比答案重要）
1. **複述題目、確認限制**（「最壞情況還是平均？」）。
2. **邊想邊說**，面試官看的是思路。
3. **從小規模開始**（2 顆、3 層），找規律。
4. **先給可行解，再優化**。
5. **驗算邊界**。

### 常考題與答案
1. **兩顆蛋、100 層樓，最少丟幾次找出臨界層（最壞情況）？** 答 **14**。第一次從 14 樓，之後依序加 13、12、11…（14+13+12+…+1 = 105 ≥ 100）。蛋 1 碎了就用蛋 2 從上一個安全層逐層往上試。
2. **8 顆球，1 顆較重，天秤最少幾次？** **2**。3/3/2 分組：先秤 3 vs 3；平衡 → 重的在剩下 2 顆再秤一次；不平衡 → 在重的那 3 顆中取 2 顆秤一次。
3. **25 匹馬、5 條賽道，找前 3 名最少幾場？** **7**。5 場分組 → 各組第一名賽 1 場 → 淘汰不可能進前 3 的馬 → 再賽 1 場決定第 2、3 名。
4. **3 個開關對應 3 顆燈，只能進房間一次。** 開關 1 開很久後關掉，開關 2 打開，進房：亮的=2，熱但不亮=1，冷且不亮=3。
5. **兩條不均勻繩子各燒 1 小時，量 45 分鐘。** 繩 A 兩端點、繩 B 一端點；A 燒完（30 分）時把 B 另一端也點，B 剩下的再燒 15 分。
6. **3 公升與 5 公升桶量 4 公升。** 裝滿 5 → 倒入 3（剩 2）→ 3 倒掉 → 把 2 倒入 3 → 再裝滿 5 → 倒入 3 直到滿（補 1）→ 5 剩 4。
7. **100 個人、100 個燈泡（開關切換）：最後哪些燈亮？** 第 n 號燈被切換的次數 = n 的因數個數；只有完全平方數有奇數個因數，所以 1, 4, 9, … , 100（共 10 盞）亮。
8. **12 顆球 1 顆輕重未知，3 次找出？** 答案存在（4/4 分組再依結果分支）；面試時能說明「3 次、3^3=27 ≥ 24 種可能（12 顆×輕或重）」的資訊量論證即可，不必背細節。

**工程風格的題目**（銀行很愛）：
- 設計 URL 短網址、限流器、秒殺系統、轉帳如何確保不重複扣款 → 直接套用本專案的 冪等 + 悲觀鎖排序 + Redis 預扣 + 對帳。
- 轉帳 A→B：交易內依帳號 ID 排序加鎖（避免死結）、冪等鍵、`balance >= amount` 條件更新、流水帳 append-only。

---

## 8. 「雲端平台沒有正式經驗」怎麼答

### 原則
**不假裝、不道歉、不說「我沒做過所以不會」。** 流程：承認事實（一句）→ 展示遷移得過去的底層能力 → 說明具體的學習行動 → 表達上手意願。

### 60 秒回答範本
> 我沒有在正式環境維運雲端平台（AWS/GCP/Azure）的經驗，這點我直接說明。
> 不過雲端服務的底層概念我在自己的專案裡都實作過。我的購物車專案有完整的 production 拓撲：Docker 多階段映像、非 root 容器、Caddy 反向代理與 TLS、MySQL/Redis 網路分段、Flyway 資料庫版本、GitHub Actions CI（含 Trivy 弱點掃描、SBOM、映像 digest 掃描）、Prometheus + Grafana 監控與 11 條告警規則、備份加密與還原演練、最小權限 DB 帳號與密鑰輪替演練，以及帶備份、健康檢查與自動 rollback 的部署腳本。
> 這些對應到雲端就是：ECS/EKS 或 Cloud Run、RDS、ElastiCache、ALB、CloudWatch、Secrets Manager、IAM。差別在於託管服務和 IAM 模型，不是工程原理。
> 我也清楚自己只在本機與 tunnel 上跑過，沒有真正的 VM 或多可用區環境。所以我的計畫是（或已經在做）：把這個專案部署到 [AWS/GCP] 的免費額度，用 [Terraform/Cloud Run] 管理，補上 IAM 最小權限與雲端監控，這樣能用實作而不是口頭說明。

### 要避免
- ❌ 說「雲端就是 Docker」或誇大成「我有雲端經驗」。
- ❌ 一直強調「我可以快速學」卻沒有具體證據。
- ✅ 用具體名詞與成果說話，並**明講限制**（誠實會加分，被追問才暴露會扣分）。

### 對照表（被問「這在雲端上怎麼做」時用）
| 專案已有 | 雲端對應 |
|---|---|
| Docker Compose prod | ECS Fargate / Cloud Run / EKS |
| Caddy + TLS | ALB + ACM / Cloud Load Balancing |
| MySQL 容器 + 備份腳本 | RDS / Cloud SQL（自動備份、PITR、Multi-AZ） |
| Redis 容器 | ElastiCache / Memorystore |
| `.env.prod` + 密鑰輪替文件 | Secrets Manager / Secret Manager + IAM role |
| GitHub Actions + GHCR | Actions + ECR/Artifact Registry + OIDC 部署角色 |
| Prometheus/Grafana/Alertmanager | CloudWatch / Cloud Monitoring（或 managed Prometheus） |
| `deploy-tunnel.sh` rollback | ECS 滾動部署 / blue-green、CodeDeploy |
| 網路分段 edge/data | VPC、private subnet、Security Group |

### 可能的追問
- **你會怎麼把它部署到 AWS？** VPC（公有放 ALB、私有放 ECS 與 RDS）→ ECR 存映像 → ECS Fargate 服務 → RDS MySQL Multi-AZ → ElastiCache → Secrets Manager 注入環境變數 → ALB + ACM 憑證 → CloudWatch 日誌與告警 → GitHub Actions 用 OIDC 假設 IAM role 部署（不存長效金鑰）。
- **最大風險是什麼？** IAM 過寬、資料庫公網暴露、金鑰寫進映像、沒有備份還原演練。
- **成本？** 先用免費額度或最小規格；設預算告警。

---

## 9. 建議的練習順序
1. 把 §1–§4 每節的「30 秒版」背成口語，對著白板畫一次下單流程（requestId → claim → Redis Lua → 排序扣庫存 → commit / 補償）。
2. 準備 3 個「踩坑故事」：Redis 補貨後賣不出去的 bug、Java vs MySQL 排序差異、health check 被 Ollama 拖成 unhealthy。
3. 每天 2–3 題邏輯題，**出聲講**。
4. 針對 §8 做一次完整 60 秒練習並錄音。
5. 若時間允許，把專案實際部署到一個雲端免費額度，之後回答就從「計畫」變成「做過」。
