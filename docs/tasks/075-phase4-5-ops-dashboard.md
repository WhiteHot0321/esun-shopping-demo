# 075 Phase 4.#5 M4 營運儀表板 — A 定位（唯讀）

**日期**: 2026-10-22（Asia/Taipei）
**階段**: B 實作完成（後端 + 前端 + 測試 + 瀏覽器走查，見 §11）；D1–D6 已於 2026-10-23 依建議決定（見 §8）。**未做 D5 執行計畫量測、未做獨立審查、未 commit、未 merge。**
**基線**: `advanced-v2` @ `cd04e78`（含 Phase 4.#3 merge `becec89`）
**來源**: `068-maintainer-console-plan.md` M4、`073-phase4-demo-slice-prompts.md` Session 2

## 結論摘要

- 今日數字**只能走 DB 聚合**。Micrometer counter 是 process-local，重啟歸零，不能當「今日」。
- 有一個會影響正確性的風險：**今日邊界的時戳來源不一致**（§3）。B 階段前必須先在真 MySQL 驗證，修正可能要動寫入路徑，需要你決定。
- 索引目前不足以支撐「今日」與「失敗付款」查詢（§4）。加索引是 schema 變更，與提示詞「不需要 migration」的前提不符，需要你決定。
- 有 6 個需要你拍板的決定（§8）。

---

## 1. 指標清單（對應 073 第 1 點）

| 指標 | 定義 | 來源 |
|---|---|---|
| 今日訂單數 | `created_at` 落在今日（Asia/Taipei）且 `order_status <> 'CANCELLED'` | DB 聚合 |
| 今日營收 | 上列訂單中 `pay_status = PAID` 的 `price` 加總（`price` 已扣折扣） | DB 聚合 |
| 待付款訂單數 | `pay_status = PENDING` 且非 CANCELLED（不限今日，看積壓） | DB 聚合 |
| 今日付款失敗數 | `payment.status = 'FAILED'` 且時戳落在今日（口徑見 D3） | DB 聚合 |
| 待退款數 | `payment.status = 'REFUND_REQUIRED'`（取消但已收款，不計入營收） | DB 聚合 |
| 低庫存商品 | `product.quantity <= 門檻` 且 `deleted_at IS NULL` | DB |
| Redis 庫存快取狀態 | `StockCacheService.dependencySnapshot().mode()`（`DISABLED`／`ENABLED`／`LATCHED_DB_ONLY`） | process-local |

「待退款數」是我為 M4 新增的指標，068 沒有列。`settleOnCancel` 會把已收款的取消訂單標成 `REFUND_REQUIRED`，這是最需要被看見的異常。

## 2. 資料來源（對應 073 第 2 點）

| 來源 | 能否用於「今日」 | 理由 |
|---|---|---|
| `shop.orders.success/failure`、`shop.payments.failure`（Micrometer） | **否** | process-local，重啟歸零；多實例也無法加總 |
| DB 聚合 | **是** | 持久、可按時間範圍查，是唯一能給「今日」的來源 |
| `StockCacheService` snapshot | 僅狀態 | 只反映**本實例**；目前單一 VM，可接受，但回應必須標示 `scope: THIS_INSTANCE` |
| `product.quantity` | 是（低庫存） | DB 為準；Redis 只是預扣快取，不作為低庫存依據 |

## 3. 端點、時區與時戳風險（對應 073 第 3 點）

**端點**: `GET /api/admin/dashboard`。授權沿用 `CouponController.requireAdmin`（`authenticatedRole == ADMIN`，否則 403）。未登入為 401，`074` 的 `MemberAdminIntegrationTest` 已對同類端點驗證過這個行為。

**「今日」邊界**: Java 端以 `Asia/Taipei` 算出 `[今日 00:00, 明日 00:00)`，以參數傳入 SQL。

**⚠️ 時戳來源不一致（需先驗證）**:

| 表 / 欄位 | 寫入方式 | 時區來源 |
|---|---|---|
| `shop_order.created_at` | DB `DEFAULT CURRENT_TIMESTAMP`（`OrderRepository.insertOrder` 未傳值） | **MySQL 伺服器時區** |
| `payment.created_at` / `updated_at` | app 端 `LocalDateTime.now()`（`PaymentRepository`） | **JVM 預設時區** |

- `docker-compose*.yml` 沒有設定 MySQL 時區（grep 無結果）。**2026-10-23 已對開發用容器 `esun-mysql`（mysql:8.0）做唯讀查詢確認**：`@@global.time_zone = SYSTEM`、`@@system_time_zone = UTC`，`NOW()` 與 `UTC_TIMESTAMP()` 相同。所以 `DEFAULT CURRENT_TIMESTAMP` 寫入的是 UTC 牆上時間，比台北慢 8 小時。「今日」切點若直接用台北 00:00 去比，會整段位移 8 小時。
- 只查了 `esun-mysql`；`esun-tunnel-mysql-1` 也是 mysql:8.0、同樣沒設時區，預期相同，但沒有逐一查。
- 應用端端到端的結果見 §10：Testcontainers 邊界測試已證實兩張表存的都不是台北時間（偏差約 8 小時）。
- `AuditLogRepository` 的註解記錄過同類顧慮：JDBC URL 固定 `serverTimezone=Asia/Taipei`，但伺服器可能是 UTC，所以 audit 改由 app 寫時戳。
- B 階段第一步就要用 Testcontainers 驗證午夜前後的邊界（伺服器時區為 UTC 的情況）。
- 修正方式有兩條，都要你決定（D4）：
  - (a) `insertOrder` 改由 app 傳入 Asia/Taipei 時戳。這動到寫入路徑，073 B 明文禁止，需要你同意破例。
  - (b) 查詢端用 `CONVERT_TZ`。需要 MySQL 時區表已載入，不建議。

**回應形狀（草案，B 階段可調整）**:

```json
{
  "asOf": "2026-10-22T15:00:00+08:00",
  "timezone": "Asia/Taipei",
  "today": { "from": "2026-10-22T00:00:00", "to": "2026-10-23T00:00:00",
             "orderCount": 0, "revenue": 0.00, "paymentFailureCount": 0 },
  "pendingPaymentCount": 0,
  "refundRequiredCount": 0,
  "lowStockThreshold": 5,
  "lowStock": [ { "productId": "...", "productName": "...", "quantity": 0 } ],
  "redis": { "mode": "ENABLED", "scope": "THIS_INSTANCE" }
}
```

**不回傳的欄位（個資／帳號）**: `member_id`、email、收件人姓名、電話、地址，以及 `product.creator_id`（VARCHAR(255)，可能含帳號識別）。

## 4. 成本與索引（對應 073 第 4 點）

| 查詢 | 現有索引 | 判斷 |
|---|---|---|
| 今日訂單（`created_at` 範圍） | `idx_shop_order_status(order_status, created_at)`、`idx_shop_order_member(member_id, created_at)` | **無法利用**：只給時間範圍時，前綴欄位不匹配，預期全表掃 `shop_order` |
| 今日付款失敗（`status`、時戳範圍） | `payment` 只有 `(order_id, id)` 與唯一鍵 | **無索引**，預期全表掃 `payment` |
| 待付款／待退款（`pay_status`／`status`） | 無 | 同上 |
| 低庫存（`product.quantity`） | 無 | 商品數少，全表掃可接受 |

- 資料量小時全表掃可接受。要用 `bench/sqlperf` 的方法（見 `docs/tasks/062`）量測執行計畫再決定。
- 若要加 `(created_at)` 或 `(status, updated_at)` 索引，屬於 schema 變更：需要 `V3__*.sql` 與 `backend/DB/17_*.sql` 兩條路徑（069 提醒過這個雙路徑陷阱）。這與 073 的「本項不需要 migration」矛盾，見 D5。
- **快取**: 沿用 `PublicRecommendationCache` 的模式（單 key、single-flight、失敗不快取）。建議 TTL 15 秒，範圍 10–30 秒，設定鍵 `ops.dashboard.cache-ttl-seconds`，`0` 關閉。代價是儀表板最多落後 TTL。見 D6。

## 5. 取消與金額（對應 073 第 5 點）

- **取消訂單不計入訂單數與營收**（`order_status = 'CANCELLED'`）。
- `shop_order.price` 是**扣除折扣後**的金額（`OrderTransactionService`：`totalPrice = totalPrice - discount` 後才寫入）。`discount_amount` 另存，不重複扣。
- 金額一律 `BigDecimal`，SQL 用 `SUM(DECIMAL)`，不經 `double`。
- 已收款但訂單已取消的款項在 `payment` 為 `REFUND_REQUIRED`，由 `PaymentRepository.settleOnCancel` 產生。儀表板應另列「待退款數」，而不是把它算進營收，也不把它藏起來。

## 6. 前端入口（對應 073 第 6 點）

- `App.vue` ADMIN 按鈕列（約第 15–17 行）加「營運儀表板」按鈕，呼叫 `togglePanel('dashboard')`。
- 面板掛載（約第 30–32 行）加 `<OpsDashboardPanel v-if="activePanel === 'dashboard' && isAdmin" @close="activePanel = ''" />`。
- 與 M0 的 `MemberAdminPanel` 共存：同一個 `activePanel` 機制，一次只開一個面板。M0 已合併，沒有並行衝突。
- 新增 `frontend/src/components/OpsDashboardPanel.vue`，沿用 `.panel`／`.panel__head`／`.link-button` 樣式。

## 7. 未驗證與限制

- 未驗證：MySQL 伺服器時區、實際資料量、索引執行計畫、`payment` 時戳的 JVM 時區是否為 Asia/Taipei。
- 指標與 Redis 狀態只代表單一實例；目前是單一 VM，可接受。
- `payment.failure_reason` 目前已知的值包含 `ORDER_CANCELLED`；其他失敗原因的完整清單尚未逐一確認（見 D3）。

## 8. B 階段前需要你決定

| 編號 | 問題 | 建議 |
|---|---|---|
| D1 | 今日營收口徑：以「今日建立且已付款」，還是以 `paid_at` 落在今日計？ | 以 `created_at` 今日、`pay_status = PAID`、非取消。與 068「今日訂單／營收」一致，實作簡單 |
| D2 | 低庫存門檻預設值？目前沒有既有設定。 | 設定鍵 `ops.dashboard.low-stock-threshold`，預設 5 |
| D3 | 「今日付款失敗數」要不要包含 `ORDER_CANCELLED`（取消訂單時系統自動關閉的未付款嘗試）？ | 排除。那不是付款失敗，是系統關單 |
| D4 | 時戳來源：是否同意 B 階段改 `insertOrder` 寫 app 端 Asia/Taipei 時戳（破例動寫入路徑），或只保留 DB 時戳並以測試限定？ | 先做 Testcontainers 邊界測試，確認伺服器時區後再決定。不先改寫入路徑 |
| D5 | 索引要不要加？加了就是 migration（V3 + 17_），違反 073「不需要 migration」。 | 先量測。執行計畫不可接受才加，並走完整雙路徑 |
| D6 | 快取 TTL？ | 15 秒，可調 10–30 秒 |

### 決定紀錄（2026-10-23，使用者：「全都依你建議」）

- **D1**: 今日營收 = `created_at` 在今日、`pay_status = PAID`、非 `CANCELLED`。
- **D2**: 低庫存門檻預設 5，設定鍵 `ops.dashboard.low-stock-threshold`。
- **D3**: 今日付款失敗數**排除** `failure_reason = 'ORDER_CANCELLED'`。
- **D4**: **先驗證，不改寫入路徑。** B 階段第一步先寫 Testcontainers 邊界測試；測試結果出來再回來決定是否破例改 `insertOrder`。在那之前，`OrderRepository.insertOrder` 與其他寫入路徑一律不動。
- **D5**: **先量測，不先加索引。** 用 `bench/sqlperf` 方法看執行計畫；不可接受才加，且走 `V3__*.sql` + `backend/DB/17_*.sql` 雙路徑。
- **D6**: 快取 TTL 預設 15 秒，鍵 `ops.dashboard.cache-ttl-seconds`，`0` 關閉。

## 9. 下一步

- B 階段分支：`feature/phase4-5-ops-dashboard`，從 `advanced-v2`（`cd04e78`）切，已建立。
- B 階段順序：(1) Testcontainers 時區邊界測試（RED 預期）→ (2) 依結果處理 D4 → (3) 後端端點與查詢 → (4) 量測執行計畫處理 D5 → (5) 前端面板 → (6) 完整測試與瀏覽器走查。
- 目前所有變更都在本地工作區，未提交。

## 10. B 階段第一步：時區邊界測試結果（2026-10-23）

**測試**: `backend/src/test/java/com/esun/shop/integration/OpsDashboardTimeZoneIntegrationTest.java`（真 MySQL，Testcontainers `mysql:8.0`）。
用真實的 `OrderRepository.insertOrder` 與 `PaymentRepository.insert` 寫入，再以 `CAST(created_at AS CHAR)` 讀原始值（避開 JDBC 驅動的時區換算），與 `LocalDateTime.now(Asia/Taipei)` 比較，容許 5 分鐘。測試內把 JVM 預設時區釘成 UTC，模擬正式容器（backend `Dockerfile` 無 `TZ`／`user.timezone`；`esun-tunnel-backend-1` 實測 `TZ` 為空、`date` 為 UTC），不受開發機時區影響。

**命令與結果**: `mvn '-Dtest=OpsDashboardTimeZoneIntegrationTest' '-Djacoco.skip=true' test` — **Tests run: 2, Failures: 2, Errors: 0, Skipped: 0**，BUILD FAILURE（預期的 RED），31.5 s。

| 欄位 | 實際存入 | 台北現在 | 偏差 |
|---|---|---|---|
| `shop_order.created_at`（DB 預設值） | 2026-10-22 16:02:18 | 2026-10-23 00:02:17 | 約 8 小時 |
| `payment.created_at`（app `LocalDateTime.now()`） | 2026-10-22 16:02:17.434 | 2026-10-23 00:02:17 | 約 8 小時 |

**結論**:
- 兩張表在正式部署條件下存的都是 **UTC 牆上時間**。直接用台北午夜當「今日」邊界，每天台北 00:00–08:00 的訂單會被算進「昨天」。
- 兩個時鐘在部署環境彼此一致（DB 容器與 backend 容器都是 UTC），但在開發機直接跑 JVM 時 payment 會是台北時間、`shop_order` 仍是 UTC，環境間不一致。
- 失敗是因為斷言，不是基礎設施錯誤（0 errors，容器正常啟動、資料正常寫入並讀回）。

**未涵蓋**: 沒做反向（綠燈）驗證，即沒有把 MySQL 設成 `+08:00` 確認同一個測試會轉綠；偏差剛好是 8 小時且兩次一致，已足以說明測試量得到差異，但這不是嚴格的 mutation check。只驗證了 `shop_order.created_at` 與 `payment.created_at`，沒驗 `payment.updated_at`、`paid_at`（同為 app 寫入，預期同樣是 UTC）。沒有跑完整測試。

### D4 的選項（需要你決定）

| 選項 | 做法 | 寫入路徑 | 資料遷移 | 代價 |
|---|---|---|---|---|
| **(a) 建議** | 不改儲存。查詢端把「台北今日 [00:00, 24:00)」轉成儲存時區（預設 UTC，設定鍵 `ops.dashboard.storage-zone`）再綁參數。把現有測試改成「契約測試」：斷言存入值 ≈ 設定的儲存時區現在時間，轉綠。 | 不動 | 不需要 | 依賴「部署環境一律 UTC」這個假設，靠契約測試守住；開發機直接跑 JVM 時 payment 的時區會不同，需文件說明 |
| (b) | 改寫入路徑：`insertOrder` 與 payment 寫入都由 app 傳入明確的台北時戳 | 要動（073 B 明文禁止，需破例） | 歷史資料是 UTC，要遷移或接受歷史偏差 | 改動面大，碰到訂單與付款關鍵路徑，屬 Critical 審查範圍 |
| (c) | MySQL 設 `--default-time-zone=+08:00`、JVM 設 `-Duser.timezone=Asia/Taipei` | 不動 | 歷史資料要遷移 | 影響整個部署與所有既有時間欄位，風險最大 |

**建議 (a)**：不動關鍵寫入路徑、不需遷移，且測試能把「儲存時區是 UTC」變成會失敗的契約，而不是隱性假設。尚未實作，等你決定。

**後續**: 使用者於 2026-10-23 採用 (a)；測試已改為契約測試並轉綠，見 §11。

## 11. B 階段實作結果（2026-10-23，分支 `feature/phase4-5-ops-dashboard`，未 commit）

**做了什麼**
- 後端：`GET /api/admin/dashboard`（ADMIN 限定，沿用 `requireAdmin` 模式）→ `OpsDashboardService`（15 秒單一快取、single-flight、失敗不快取；Redis 狀態每次即時讀）→ `OpsDashboardRepository`（唯讀聚合）。`DashboardWindow` 把台北今日轉成儲存時區（UTC）邊界再綁 SQL（D4 選項 a）。設定鍵：`ops.dashboard.low-stock-threshold`（5）、`ops.dashboard.cache-ttl-seconds`（15，0 關閉）、`ops.dashboard.storage-zone`（UTC）。
- 前端：`OpsDashboardPanel.vue` + `App.vue` ADMIN 按鈕與掛載點（沿用 `activePanel`，與 M0 面板共存）。
- 沒有動任何寫入路徑、沒有 schema 變更、沒有 migration。

**驗證**
- 新後端測試 22/22：`DashboardWindowTest` 5、`OpsDashboardServiceTest` 7、`OpsDashboardIntegrationTest`（真 MySQL）8、`OpsDashboardTimeZoneIntegrationTest`（契約）2。
- 變異測試：對查詢與 service 故意做 7 個破壞（移除 NULL 判斷、移除取消排除 ×2、移除軟刪除過濾、結束邊界 `<` 改 `<=`、少一個系統關閉原因、service 傳台北牆上時間）；每個都使對應測試變紅，已逐位元組還原。
- 完整 `mvn clean test`：**344/344**，0 failures／errors／skips，JaCoCo 門檻通過。
- Vitest **108/108**（新增面板 15、App 入口 2）；`npm run build` 通過。
- 瀏覽器走查（本機 dev 後端 + Vite，JVM `-Duser.timezone=UTC` 模擬正式容器）：
  - ADMIN：儀表板正常顯示；數字與對 dev DB 的唯讀 SQL 完全一致（0 筆今日訂單、營收 0、待付款 5、待退款 0、低庫存僅 P001）。
  - 以買家帳號走真實下單流程後按重新整理：今日訂單 0→1、待付款 5→6、營收維持 0（新訂單未付款）、P002 庫存 46→45，與預期一致。
  - 買家：不顯示入口，直接呼叫 API 為 403；未登入為 401。console 無錯誤；手機寬度（375）單欄、無橫向捲動。

**對 D3 的延伸（需你確認）**: D3 只寫排除 `ORDER_CANCELLED`，我依同一理由（系統自己關閉、不是付款失敗）一併排除 `SUPERSEDED_BY_RETRY` 與 `SUPERSEDED_BY_LATE_SUCCESS`。這三個字串是 `PaymentRepository`／`PaymentService`／`PaymentCallbackService` 寫入的字面值，沒有共用常數，改名會悄悄失效。

**走查中發現（非 M4 缺陷）**
- `.claude/launch.json` 的 `frontend-dev` 在專案根目錄跑 `npm run dev`，找不到 `package.json` 而失敗（缺 `cwd`）。未修改。
- 用 `127.0.0.1:5173` 開前端會被後端 CORS 擋（只放行 `localhost:5173`），是我自己選錯網址。

**未涵蓋**
- **D5 未做**：沒有跑 `EXPLAIN`／`bench/sqlperf` 量測「今日訂單」「付款失敗」兩個未建索引的查詢；沒有加索引。
- 沒有獨立審查（073 C 階段，Low 風險，建議快速唯讀審查）。
- 各數字來自獨立查詢，不是同一交易快照。
- 開發機直接跑 JVM 且未設 UTC 時，payment 時戳為台北時間，與預設儲存時區 UTC 不符，付款失敗數會偏；正式容器為 UTC，已由契約測試守住。
- 沒有對前端做變異測試；沒有跨午夜的端到端瀏覽器驗證（邊界只由 `DashboardWindowTest` 與真 MySQL 的邊界測試涵蓋）。

**dev 資料庫殘留（`esun-mysql`）**: 兩個拋棄式測試帳號 `ops-walk-admin-…`（已升 ADMIN）與 `ops-walk-buyer-…`、1 筆收件地址、1 筆 PENDING 訂單（P002 庫存 46→45）。未清理。
