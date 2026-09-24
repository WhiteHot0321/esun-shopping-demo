# Task 026 — Phase 3.1 #7 收件地址簿

## 範圍與基線

- 分支：`feature/frontend-ux-revamp`
- 基線：`ab1ff81`；工作樹原先已有 Phase 3.1 #6 與其他使用者變更，全部保留。
- 目標：登入買家可管理自己的多筆地址、維持唯一預設地址，並在結帳時選擇或新增地址。
- 排除：提交、推送、合併、Phase 3.0 訂單完整 schema 套餐及地址編輯／刪除前端管理頁。

## 契約與不變量

- `shipping_address` 以 `member.id` 外鍵綁定會員，包含標籤、收件人、電話、郵遞區號、地址與預設旗標。
- generated column + unique constraint 在 DB 層保證同一會員最多一筆預設地址；第一筆地址自動成為預設。
- CRUD 與設預設端點一律從 JWT 的 `authenticatedEmail` 推導會員，跨會員資源回覆 404。
- `shop_order.shipping_address_id` 外鍵保留訂單與地址關係；已被訂單引用的地址回覆 409，禁止刪除。
- `CreateOrderRequest.shippingAddressId` 可省略；省略時解析該會員預設地址，沒有可用地址則回覆 400。
- 建立訂單時忽略 body 的 `memberId`，改用 JWT email，並在交易內驗證地址歸屬後持久化。

## API

- `GET /api/member/addresses`
- `POST /api/member/addresses`
- `PUT /api/member/addresses/{id}`
- `DELETE /api/member/addresses/{id}`
- `POST /api/member/addresses/{id}/set-default`

## 驗收結果（2026-09-19，Codex）

- `cd backend; mvn -Dtest=ShippingAddressIntegrationTest test`：production 與 test source
  編譯成功；2 個案例皆在 Testcontainers class initialization 前因 Docker engine 未啟動而
  error，未進入測試本體，不能列為通過。嘗試啟動 Docker Desktop 後 pipe 仍不存在，直接
  啟動 `com.docker.service` 又被主機權限拒絕。
- `cd frontend; npm test -- --run`：checkout 3/3、Vitest 19/19，全部通過。
- `cd frontend; npm run build`：通過（101 modules transformed）。
- `git diff --check`：通過；僅既有 Windows line-ending 提示。
- 修正輪次：1（前端既有商品失敗測試改為依 URL mock，避免地址 GET 搶用一次性 mock）。
- 獨立授權審查首次結果：FAIL；發現 DTO 的 `memberId @NotBlank` 會在 Controller 以 JWT
  覆蓋前先拒絕省略欄位的請求。已依 correction task 移除必填限制，並把整合案例改為真的
  省略 `memberId`；這是第 2 輪且最後一輪修正。Docker 尚不可用，因此案例仍待執行。
- 使用者介入：兩次核准範圍上限；elapsed/token/cost/五小時額度變化未知。
- 授權邊界獨立唯讀審查提出 1 個有效阻擋並已修正；當時待 Docker 恢復後重跑的 runtime
  驗收與後續完整回歸，現均已完成並通過（見下方最終證據）。
- Docker 28.4.0 恢復後的驗收：先修正既有 `OrderServiceTest` 六處雙參數 Mockito
  編譯錯誤，再補上測試類缺少的 `webEnvironment=MOCK`。第三次執行已真正跑入 2 個案例，
  結果 **1 通過、1 失敗**：API 由 record accessor 輸出 `isDefault`，但整合斷言及前端使用
  `default`。這是實際契約不一致，會使前端無法辨識預設地址。已達兩輪自動修正上限，
  尚未修正或重跑；Phase 3.1 #7 不得標記完成。
- 使用者明確核准第三輪修正後，前端與整合測試統一採後端實際 JSON 契約
  `isDefault`。最終驗證：`mvn -Dtest=ShippingAddressIntegrationTest test` **2/2 PASS**、
  0 failures/errors/skipped 且 JaCoCo gate PASS；`npm test -- --run` 為 checkout 3/3 +
  Vitest 19/19 PASS；`npm run build` PASS（101 modules）。Phase 3.1 #7 的針對性驗收已完成。
- 2026-09-20 收尾完整回歸首次揭露兩項相容性缺口：舊下單整合 fixture 沒有預設地址，
  以及 compatibility constructor 路徑的 nullable `shippingAddressId` 被條件運算式自動拆箱而 NPE。
  補齊共用地址 fixture 並改用明確分支後，進一步發現地址 SELECT 在 idempotency claim 前建立
  repeatable-read snapshot，使輸掉 unique-key 競態的重播交易看不到勝方已提交資料。最終將地址解析
  移到 claim 成功後，並補齊真實 HTTP JWT 會員的地址 fixture。
- 最終證據：`mvn clean test` **96/96 PASS**、0 failures/errors/skipped，JaCoCo gate PASS；
  修正後的 `OrderIdempotencyIntegrationTest,RedisOrderIntegrationTest,RedisLiveOutageIntegrationTest`
  聚焦組亦 PASS。交易順序與 fixture 收尾變更另經獨立唯讀審查 **PASS**。整體共 2 輪
  收尾修正；未 commit／push／merge。

## 工程概念

地址簿是可變的會員資料，但訂單必須保留建立當下所引用的有效地址關係；因此刪除採限制而非級聯，避免歷史訂單失去收件依據。唯一預設地址同時由交易邏輯與資料庫唯一約束防守，避免並行請求產生兩筆預設值。

## 收尾核對（2026-09-24，Codex）

- 狀態：✅ 已實作、已測試、已獨立審查、已整合至 `advanced-v2`。
- Git 證據：功能提交 `6c79815`（同時包含購物車持久化）已由 merge commit
  `10c642c` 納入目前 `advanced-v2`；`git merge-base --is-ancestor 6c79815 HEAD`
  回傳成功。先前「未 commit／push／merge」僅是 2026-09-20 合併前的歷史狀態。
- 2026-09-24 merged-tree 重驗：`mvn -q -Dtest=ShippingAddressIntegrationTest test`
  **2/2 PASS**、0 failures/errors/skipped；`npm test -- --run` 為 checkout **3/3**、
  Vitest **35/35 PASS**。本輪未重跑完整 backend suite 或 frontend production build；
  完整回歸與 build 證據仍採用上方 2026-09-20 已完成的驗收紀錄。
- 本輪只更新文件狀態，未修改 production/test code；closure branch 的 commit／push／merge
  依最終 Git 紀錄為準。
- 任務分類：Small（收尾核對）；使用者介入 0、修正輪次 0、scope expansion 0；
  elapsed time、起訖 context、tool calls、model cost 與五小時 usage delta 均 unavailable／unknown。
