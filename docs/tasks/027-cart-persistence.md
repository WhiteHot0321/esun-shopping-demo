# Task 027 — Phase 3.1 #8 購物車持久化

## Session gate

- 任務分類：Medium；會員資料隔離與跨裝置同步使後端授權邊界不可省略。
- 分支／基線：`feature/frontend-ux-revamp` @ `ab1ff81`；保留工作樹內 Phase 3.1 #6/#7 與其他既有變更。
- 目標：登入買家的購物車由後端持久化並跨裝置同步；前端 localStorage 僅作同裝置快速還原，不作完成證據。
- 原始限制：5 個核心檔讀取、3 個核心檔變更、2 個針對性測試命令、1 輪修復。
- 所需擴充：預估 7–9 個 production/test 檔；依 `AGENTS.md`，開始後端寫入前須取得使用者明確核准。
- 排除：commit、push、merge、未登入訪客的伺服器購物車、價格快照、預留庫存、促銷／優惠券。

## Authoritative requirements

Notion「Phase 3 提示詞庫」的 Phase 3.1 #8 是完成標準；本地
`docs/tasks/017-buyer-feature-list.md` 的 localStorage 選項只視為前置層。完成需證明：

1. `shopping_cart` 以會員與商品綁定，數量為正，且同一會員／商品只有一列。
2. 所有 API 身分只取自已驗證 JWT，不接受 client-supplied member ID。
3. `GET /api/cart` 查詢登入會員購物車。
4. `POST /api/cart/add` 新增商品；既有商品採累加或明確定義的 upsert 行為。
5. `PUT /api/cart/items/{cartItemId}` 更新正整數數量。
6. `DELETE /api/cart/items/{cartItemId}` 刪除單項，並提供清空購物車操作。
7. 不存在、下架或數量超過即時庫存的商品不得形成無效 server cart；錯誤需使用既有 API error/status 契約。
8. 前端登入後以 server cart 為權威來源，數量變更與清空同步後端；重新登入或另一裝置能取回相同內容。
9. 下單成功後 server cart 與 localStorage 都清空；下單失敗或結果未確認時不得提前清除。
10. 不同會員彼此無法讀寫對方 cart item；跨會員 item ID 採 404，避免資源枚舉。

## Proposed implementation scope

- DB：新增 fresh-init／可重跑 migration（建議 `08_shopping_cart.sql`），包含 member/product FK、
  `UNIQUE(member_id, product_id)`、`CHECK(quantity > 0)` 與查詢索引。
- Backend：`CartController`、`CartService`、`CartRepository` 與必要 request/response DTO。
- Frontend：`ShopWorkspace.vue` 將現有 localStorage 還原層與 `/api/cart` 同步整合。
- Tests：真實 MySQL + MockMvc 整合測試覆蓋 JWT ownership、upsert、更新、刪除、清空、
  無效商品／庫存，以及前端 server restore／write／checkout-success clear。

## 2026-09-20 frontend milestone

- `ShopWorkspace.vue` 已新增版本化、依正規化 email 隔離的 localStorage key。
- 商品載入後還原；不存在商品移除、超量縮至目前庫存；損壞 JSON 安全清除；後續數量變更自動寫回。
- `App.spec.js` 新增整合案例，證明還原、庫存校正、移除不存在商品與後續持久化。
- 驗證：`npx vitest run src/App.spec.js` 20/20 PASS；`npm test` 為 checkout 3/3 + Vitest 20/20 PASS；
  `npm run build` PASS（101 modules）；`git diff --check` PASS。
- 一次命令路徑錯誤曾使 Vitest 找不到 `frontend/src/App.spec.js`；更正為工作目錄相對路徑後通過，
  非程式修復輪。
- 狀態：🟡 前端前置層完成；後端跨裝置同步尚未開始，因此 Phase 3.1 #8 未完成。

## Minimum verification after scope approval

1. 聚焦 backend cart integration test（真實 MySQL / Testcontainers），並確認 JaCoCo gate。
2. 聚焦 frontend cart tests、完整 frontend Vitest 與 production build。
3. 若觸及 checkout 成功清除契約，執行相關 checkout regression。
4. 完成後依專案規則同步 `docs/project-state.md`、Notion 進度追蹤、提示詞整理階段工作、
   執行順序與策略、執行計劃 Phase 3.1 表格列及 Phase 3 提示詞庫，並逐頁回讀。

## Metrics

- 執行者：Codex。
- 使用者介入：等待 1 次核心檔範圍擴充核准。
- 修復輪：0（僅有 1 次錯誤測試路徑重下命令）。
- elapsed time、model/token cost、五小時額度變化：unknown。

## Independent review — FAIL（2026-09-20 09:25 Asia/Taipei）

- 實作里程碑驗證：真實 MySQL `CartIntegrationTest` 3/3 PASS、JaCoCo gate PASS；frontend
  checkout 3/3 + Vitest 20/20 PASS；production build PASS；`git diff --check` PASS。
- Terra 唯讀獨立審查找到 2 個 blocker，Phase 3.1 #8 不得標記完成。
- 修復輪：2。第一輪把不可靠的「直接改 DB 庫存」失敗 fixture 改為跨會員地址 404；第二輪修正
  QuantityStepper 同值重複同步與 server cart 降量提示。依 stop rule，第三輪需使用者明確核准。

# CODEX → CLAUDE CORRECTION TASK

TASK_ID: 027-C1
PARENT_TASK_ID: 027

## OBSERVED_DEFECT

1. `CartService.checkout()` 沒有涵蓋 read cart → create order → clear cart 的同一會員互斥範圍；
   兩個不同 requestId 的並行 checkout 可由同一 cart 建立兩張訂單。
2. 前端 per-product write queue 未與 `loadServerCart()` 協調；延遲的舊 GET 可在 PUT 成功後覆蓋
   UI/localStorage，造成 client/server drift。

## EXPECTED_BEHAVIOR

- 同一會員同一份 cart 同時只能有一個 checkout 消費成功；第二個請求不得再建立訂單。
- cart GET 不得覆蓋較新的本地已確認寫入；reload 與 add/update/delete 的結果必須收斂到 server 狀態。

## EVIDENCE

- `CartService.java` checkout 先呼叫自我方法 `list()`，其 `@Transactional` 不經 proxy，且後續
  `OrderService.createOrder()` 與 `clear()` 不在同一 lock lifetime。
- `ShopWorkspace.vue` 的 `cartSyncs` 僅序列化同 product writes；`loadServerCart()` 可獨立執行並無條件覆寫。

## ALLOWED_SCOPE

- `CartService.java`、必要 repository transaction/locking 支援、`CartIntegrationTest.java`。
- `ShopWorkspace.vue`、`App.spec.js`。

## ACCEPTANCE

- 兩個不同 requestId 並行 checkout 同一 cart，exactly one 建單，cart 最終為空。
- 延遲舊 GET 與先完成 PUT 的排序測試，最終 UI/localStorage 與 server 新值一致。

## REQUIRED_TEST

- `mvn -Dtest=CartIntegrationTest test`
- `npm test`

## OUT_OF_SCOPE

- commit／push／merge；其他 Phase；全面重構 checkout/order transaction。

## Third repair and re-review — FAIL（2026-09-20 09:33 Asia/Taipei）

- 使用者明確核准第三輪修復。
- 後端 blocker 已修正：checkout 在同一 transaction/member lock lifetime 中完成 replay check、
  cart snapshot、建單與 clear；不同 requestId 並行 checkout 測試證明 exactly one 建單。
- 前端延遲 GET 覆蓋較新 PUT 的 blocker 已修正：reload 等待既有 write queue，mutation generation
  改變時丟棄舊結果；對應延遲測試通過。
- 驗證：真實 MySQL `CartIntegrationTest` **4/4 PASS** + JaCoCo gate；checkout 3/3 + Vitest
  **21/21 PASS**；production build PASS。
- 獨立複核仍為 **FAIL**：`clearCart()` 未先等待既有 `cartSyncs`。若 `/cart/add` POST 尚未完成，
  `DELETE /cart` 可先成功，延遲 POST 再把商品寫回 server，造成 UI/localStorage 空但 server 非空。
- 第三輪已用完；依 stop rule，需使用者核准第四輪才可修正與新增 delayed add → clear ordering test。

## Fourth repair and re-review — FAIL（2026-09-20 09:36 Asia/Taipei）

- 使用者明確核准第四輪；`clearCart()` 現在封鎖新數量變更並等待既有 write queue，delayed
  add → clear 測試證明 DELETE 不會早於 POST，server cart 不再復活。
- checkout 也先等待 write queue，避免剛加入商品就立即下單時讀到空 server cart。
- frontend checkout 3/3 + Vitest **22/22 PASS**；production build PASS。
- 獨立複核仍為 **FAIL**：clear 與 checkout 未互斥。clear 未檢查 `busy`，`submitOrder()` 未檢查
  `cartClearing`；兩者可同時等待 queue 後競態發送 DELETE 與 checkout，結果由網路順序決定。
- 第四輪已用完；需使用者核准第五輪，加入雙向 guard 與 clear/checkout interleaving test。

## Fifth repair and re-review — FAIL（2026-09-20 Asia/Taipei）

- 使用者明確核准第五輪；`submitOrder()` 與 `clearCart()` 已加入雙向 guard，交錯測試覆蓋
  clear → checkout 與 checkout → clear。frontend checkout 3/3 + Vitest **23/23 PASS**；build PASS。
- 獨立複核確認正常 checkout/clear blocker 已修正，但仍為 **FAIL**：`retry()` 只檢查 `busy`，
  未檢查 `cartClearing`。ambiguous checkout 後先清空再 retry，仍會競態發送 DELETE 與 checkout。
- 第五輪已用完；需使用者核准第六輪，補 retry guard 與 retry → clear interleaving test。

## Sixth repair and final review — PASS（2026-09-20 Asia/Taipei）

- 使用者明確核准第六輪；`retry()` 已加入 `cartClearing` guard，ambiguous checkout 後的
  retry → clear 交錯測試證明兩者不會競態送出 checkout 與 DELETE。
- frontend checkout Node tests **3/3 PASS**、Vitest **24/24 PASS**、production build PASS
  （101 modules）。
- backend 真實 MySQL `CartIntegrationTest` **4/4 PASS**，最終 `mvn clean test` **100/100 PASS**，
  0 failures/errors/skipped，JaCoCo gate PASS。
- Terra 唯讀獨立複核最終結果：**PASS**。並行 checkout、GET/PUT、add/clear、clear/checkout、
  retry/clear 的已知競態均已有實作防護與回歸證據。
- `git diff --check` PASS。Phase 3.1 #8 驗收完成；未 commit／push／merge。

## Final metrics

- Task class：critical transaction/concurrency feature。
- 實作者：Codex；獨立審查者：Terra 唯讀 reviewer。
- 修復輪：6；有效審查缺陷：5 組競態／一致性 blocker。
- 使用者介入：1 次範圍擴充核准，以及第三至第六輪共 4 次修復核准。
- elapsed time、model/token cost、五小時額度變化：unknown。
