# Task 035 — Phase 3.2 #13 金流整合 [Critical]

- Baseline: `advanced-v2` @ `cc0adbd`; branch `feature/phase32-13-payment`
- Risk: **Critical**（payment、order 狀態、authorization、concurrency）
- Implementer: Claude Code；Reviewer: 另一個全新脈絡、唯讀的 Claude 審查 session（**不是 Codex**，見「未證明」）
- Date: 2026-09-24

## 問題與根因

`POST /api/orders` 直接採用客戶端傳入的 `payStatus`（`OrderTransactionService` 使用 `request.getPayStatus().ordinal()`），任何人下單時送 `"PAID"` 就得到「已付款」訂單；購物車 UI 還提供「已付款」單選鈕。系統沒有金流，`pay_status` 只是裝飾欄位（見 `docs/analysis/payStatus-current-state.md`）。

## 解法

| 面向 | 做法 |
|---|---|
| 訂單建立 | 伺服器一律寫 `PENDING`；`CreateOrderRequest.payStatus` 保留為可選且被忽略；前端移除付款狀態單選鈕 |
| 付款帳本 | 新表 `payment`（`01_schema.sql` + 可重複執行的 `13_payment.sql`）；一筆 = 一次付款嘗試；`INITIATED → SUCCEEDED / FAILED`，無法套用的款項 `REFUND_REQUIRED` |
| 每張訂單最多一個進行中嘗試 | 由 DB 保證：生成欄位 `active_order_id`（僅 INITIATED/SUCCEEDED 有值）+ UNIQUE，等同 MySQL 不支援的 partial unique index |
| 開始付款 | `POST /api/orders/{id}/payment`（JWT、僅本人訂單，否則 404）；金額取伺服器端訂單價；重複點擊回傳同一筆進行中嘗試 |
| 回調 | `POST /api/payments/callback`（公開路由，靠簽章）：先驗 HMAC-SHA256（常數時間比較、金額正規化為兩位小數、欄位不可含 `\|`、金額範圍先行限制）→ 才開交易；鎖定順序固定 **order → payment**；compare-and-set 更新兩表 |
| 冪等 | 重送/併發重複 → `DUPLICATE`；`SUCCEEDED` 之後的舊 `FAILED` 不會撤銷付款 |
| 與取消競態 | 取消（同一交易內）關閉進行中嘗試、已付款者標 `REFUND_REQUIRED`；成功回調與取消同時到達，無論誰先，終態一致：訂單 CANCELLED、款項 `REFUND_REQUIRED`、庫存只退一次 |
| 金額不符 | 簽章正確的 SUCCESS 但金額不同 → `REFUND_REQUIRED(AMOUNT_MISMATCH)`（不套用、不丟棄）；FAILED 金額不符 → 400 |
| Sandbox | `payment.provider=sandbox`（`PAYMENT_PROVIDER`，預設 `none`）：預設關閉時關閉時無法開始付款（503）、sandbox 端點 404、連簽章正確的回調也拒絕（503），因此 `application.yml` 內的開發用 secret 不能被拿來改單。開啟時買家可用 `POST /api/payments/{tradeNo}/sandbox-result` 為自己的嘗試回報結果，該結果仍走同一條簽章回調路徑 |
| Provider 抽換點 | `PaymentGateway` 介面（`HmacPaymentGateway` 為 sandbox 實作）；換成真實綠界/藍新只需實作 `sign/verify`，狀態機不變 |
| 前端 | `OrdersPanel`：付款狀態徽章（未付款/付款失敗/已付款/待退款）、「前往付款/重新付款」、sandbox 付款面板；徽章與可付款與否完全取自伺服器欄位 `payStatus/paymentStatus/payable` |

## ECPay 整合（2026-09-24 後續任務，已完成）

未合併分支 `codex/phase31-pay-status`（基準 `ec140f7`）有另一版 ECPay 實作，類別名與資料表和本項衝突。整合做法：**以本項為底，只移植該分支的 ECPay 簽章／表單／回應協定**，其餘（`payment_transaction`、自有 `PaymentService`）不併入。

- `PaymentGateway` 改為 provider 接縫（`payment.provider` = `none`（預設）｜`sandbox`｜`ecpay`，以 `@ConditionalOnProperty` 恰好啟用一個）：`newMerchantTradeNo`、`checkout`（回傳要 POST 的表單或空）、`verifyCallback(Map)`（驗證並正規化，永不 throw）、`simulatedCallback`（僅 sandbox）、`canResumeAttempt`。狀態機只接觸經驗證的 `VerifiedPaymentCallback`。
- `EcpayPaymentGateway`：AioCheckOut、SHA-256 CheckMacValue（含 ECPay 公開測試向量）、`MerchantTradeNo` = `E`+19 碼十六進位（≤20）、僅整數 TWD（否則 422 並回滾剛建立的嘗試）、驗簽後再核對 MerchantID／`TradeAmt` 僅純數字。`POST /api/payments/ecpay/callback`（form-encoded、公開路由、回 `1|OK`／`0|ERROR`）。設定不全時**啟動即失敗**；`ecpay.*` 一律來自環境變數。
- 只開信用卡（`ChoosePayment=Credit`）：ATM／超商的首次回調 `RtnCode≠1` 代表「已取得繳費代碼」而非失敗，若當作失敗會把之後真的付款變成退款；要支援需先加入「待處理」回調結果。
- 前端：`OrdersPanel` 收到 `redirect` 時以暫存表單 POST 到 provider（僅接受 `https://`），不假設付款狀態。

### 第二次獨立審查（全新脈絡、唯讀）：FAIL → 已修復
- **blocker**：CheckMacValue 參數排序必須**不分大小寫**（信用卡回調含 `card4no`／`auth_code`／`amount` 等小寫欄位；區分大小寫的排序會讓每一筆真實回調驗簽失敗）。Codex 原分支同樣有此問題，官方向量只含 PascalCase 所以測不出。已改 `String.CASE_INSENSITIVE_ORDER`，新增以獨立算出的預期值驗證排序、以及帶小寫欄位的回調測試。
- 遲到成功：已被我方關閉的嘗試（拒付／重付／取消）若之後收到成功回調，**訂單仍待付款且未取消 → 入帳**（並關閉較新的進行中嘗試以維持「每單一個進行中嘗試」）；否則才標 REFUND_REQUIRED。此規則取代前一版「一律退款」。
- ECPay 不接受重送同一 `MerchantTradeNo` → 「再次付款」改為關閉舊嘗試並開新號（`canResumeAttempt=false`）；舊頁面若之後付款，仍依上一條入帳。
- 拒絕 `SimulatePaid=1`（後台模擬付款）除非 payment-url 是 stage 主機；缺 `RtnCode` 視為無效。
- **審查者部分結論來自其記憶（自述約 60–80% 把握）**：小寫欄位、失敗後可在同頁重試、重送同號被拒。已採取「即使不成立也不會出錯」的保守設計，但**必須以 ECPay stage 實測確認**。

### 仍未證明
- **ECPay 回調（入站）尚未收過真實的一筆**：需要公開 HTTPS callback URL（如 ngrok）＋ stage 帳號 `3002607`／`pwFHCqoQZGmho4w6`／`EkRm7iFT261dpevs`（ECPay 公開測試帳號），並在 ECPay 頁面完成一次測試付款。出站部分與 UI 已實測，見下節。以下為入站手動驗證步驟。

```bash
PAYMENT_PROVIDER=ecpay ECPAY_MERCHANT_ID=3002607 ECPAY_HASH_KEY=pwFHCqoQZGmho4w6 ECPAY_HASH_IV=EkRm7iFT261dpevs ECPAY_PAYMENT_URL=https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5 ECPAY_CALLBACK_URL=https://<你的公開網址>/api/payments/ecpay/callback ECPAY_RETURN_URL=http://localhost:5173/ mvn spring-boot:run
```
  確認項目：信用卡付款成功後訂單變「已付款」（驗證小寫欄位排序）、拒付後同頁重試的行為、重按「前往付款」不報 MerchantTradeNo 重複。
- 其餘同上（Codex 獨立審查、退款執行／逾期取消／付款稽核／速率限制、賣家可對未付款訂單出貨的業務決策）。

## 實機驗證（2026-09-24，Claude Code）

用一次性 MySQL 容器（載入全部 DB 腳本）+ 真實後端 + Vite 前端，非 mock。

**A. 真實 ECPay stage（`payment.provider=ecpay`，公開測試帳號，出站）**
- 後端產生的簽章表單 POST 到 `payment-stage.ecpay.com.tw` → **被接受**，顯示「選擇支付方式」頁：訂單編號＝我方 `MerchantTradeNo`（20 碼）、商品說明、`NT$240`、信用卡付款；無 CheckMacValue 錯誤。另以獨立的 Python 實作重算 CheckMacValue，與伺服器輸出一致。
- **同一 `MerchantTradeNo` 第二次送出（相同內容、或只改日期並重簽）→ ECPay 回 `10300028 訂單編號重覆，建立失敗`**；「再次付款」開的新編號 → 被接受。這實證了 `canResumeAttempt=false` 的設計（審查者原本只有約 60% 把握）。
- 用戶端送 `payStatus:"PAID"` 下單 → 訂單仍為 `PENDING`（實機確認漏洞已關閉）。

**B. 瀏覽器 E2E（`payment.provider=sandbox`，真實 UI）**
- 購物車不再有「付款狀態」選項，並提示「建立訂單後，請至『我的訂單』完成付款」。
- 「我的訂單」顯示「未付款」徽章與「前往付款」→ 沙盒面板 → **模擬付款失敗** → 徽章「付款失敗」＋「重新付款」→ **重新付款 → 模擬付款成功** → 「已付款」，且不再出現「前往付款」。DB：第一次嘗試 `FAILED(PROVIDER_DECLINED)`、第二次 `SUCCEEDED`、`pay_status=1`；另一張訂單維持未付款。
- **取消已付款訂單** → 畫面「已取消／待退款」；DB `CANCELLED / pay_status=1 / REFUND_REQUIRED`，庫存回補。
- 登入以本機拋棄式測試帳號的 JWT 寫入 localStorage 完成（未在 UI 輸入密碼）；測試後已停止後端／前端並刪除 MySQL 容器。
- 未涵蓋：真實 ECPay 頁面上完成一次付款並由 ECPay 回打我方 callback；手機寬度；賣家端畫面。

## 驗證（已執行）

- 後端 `mvn clean test`：見 handoff 的最終數字（含 JaCoCo gate）。
- 真實 MySQL（Testcontainers）`PaymentIntegrationTest` 16 項：客戶端自報 PAID 被忽略、擁有者限定/伺服器定價/冪等、8 路併發開始付款只有一筆、簽章/未知交易/金額/惡意金額拒絕且不改狀態、成功+重送+舊失敗、8 路併發重複回調只套用一次、失敗後重試 + 舊嘗試遲到的成功 → 退款標記、取消前/後付款、**成功回調 vs 取消競態 ×5 輪**、sandbox 僅限擁有者、DB 不變量（第二個進行中嘗試被拒、CHECK amount>0）、migration 重跑；`PaymentDisabledIntegrationTest`（預設關閉）；`HmacPaymentGatewayTest` 8 項。
- 前端 Vitest 56/56、checkout 3/3、`npm run build` PASS。
- 既有測試的清理順序（`DELETE FROM payment` 先於 `shop_order`）已補在 `CartIntegrationTest`、`ShippingAddressIntegrationTest`（FK 連鎖，非行為回歸）。

## 獨立審查

另一個全新脈絡、只讀的審查者（僅給需求，未給實作說明）：**PASS，無 blocker**。採納並修復的 should-fix：(1) 驗簽前不應開交易（改 `TransactionTemplate`，驗簽通過後才開）；(2) 未驗證的 `BigDecimal` 在算術前先限制範圍（`1E+100000000` 類 DoS）＋DTO `@Positive/@Digits`；(3) 簽章正確但金額不符的 SUCCESS 不可丟棄（改標 `REFUND_REQUIRED`）。另修 nits：`Locale.ROOT`、未知 `pay_status` 值防禦、`payable` 檢查金額 > 0、啟用 sandbox 卻用預設 secret 時記 ERROR。

## 未證明 / 後續（不阻擋本項）

- **真實 ECPay 只驗證了出站半邊**（見「實機驗證」）：ECPay 伺服器對我方回調的實際內容（含小寫欄位）尚未收過；此外未接藍新。
- 審查者是同一模型家族的新 session，不是 Codex 的獨立審查；如需更強保證可再請 Codex 過一次。
- 退款本身（呼叫 provider 退款 API、後台處理 `REFUND_REQUIRED`）、付款逾期自動取消、付款事件稽核（`audit_log` 目前不含付款事件）、對 callback 的速率限制、正式環境禁止預設 secret 的啟動檢查（目前僅 ERROR log）。
- **業務決策待定**：出貨/確認尚未要求已付款（賣家可對未付款訂單 CONFIRMED/SHIPPED），保留 #11 行為以支援貨到付款情境。
- 遲到的 SUCCESS 落在已關閉嘗試：訂單仍待付款則入帳，否則標 REFUND_REQUIRED（見「ECPay 整合」）。

## 本機啟用 sandbox

```bash
PAYMENT_SANDBOX_ENABLED=true PAYMENT_CALLBACK_SECRET=<至少16字元的隨機字串> mvn spring-boot:run
```

Engineering note：付款狀態的權威是「經過驗證的 provider 回調 + 被鎖住的 DB 列上的 compare-and-set」，不是客戶端的任何欄位；凡是與取消/付款交錯的路徑都用同一個鎖順序（order → payment），並讓「無法套用的錢」有帳可查而不是消失。
