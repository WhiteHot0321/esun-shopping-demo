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
| Sandbox | `payment.sandbox.enabled`（`PAYMENT_SANDBOX_ENABLED`）**預設 false**：關閉時無法開始付款（503）、sandbox 端點 404、連簽章正確的回調也拒絕（503），因此 `application.yml` 內的開發用 secret 不能被拿來改單。開啟時買家可用 `POST /api/payments/{tradeNo}/sandbox-result` 為自己的嘗試回報結果，該結果仍走同一條簽章回調路徑 |
| Provider 抽換點 | `PaymentGateway` 介面（`HmacPaymentGateway` 為 sandbox 實作）；換成真實綠界/藍新只需實作 `sign/verify`，狀態機不變 |
| 前端 | `OrdersPanel`：付款狀態徽章（未付款/付款失敗/已付款/待退款）、「前往付款/重新付款」、sandbox 付款面板；徽章與可付款與否完全取自伺服器欄位 `payStatus/paymentStatus/payable` |

## 驗證（已執行）

- 後端 `mvn clean test`：見 handoff 的最終數字（含 JaCoCo gate）。
- 真實 MySQL（Testcontainers）`PaymentIntegrationTest` 16 項：客戶端自報 PAID 被忽略、擁有者限定/伺服器定價/冪等、8 路併發開始付款只有一筆、簽章/未知交易/金額/惡意金額拒絕且不改狀態、成功+重送+舊失敗、8 路併發重複回調只套用一次、失敗後重試 + 舊嘗試遲到的成功 → 退款標記、取消前/後付款、**成功回調 vs 取消競態 ×5 輪**、sandbox 僅限擁有者、DB 不變量（第二個進行中嘗試被拒、CHECK amount>0）、migration 重跑；`PaymentDisabledIntegrationTest`（預設關閉）；`HmacPaymentGatewayTest` 8 項。
- 前端 Vitest 56/56、checkout 3/3、`npm run build` PASS。
- 既有測試的清理順序（`DELETE FROM payment` 先於 `shop_order`）已補在 `CartIntegrationTest`、`ShippingAddressIntegrationTest`（FK 連鎖，非行為回歸）。

## 獨立審查

另一個全新脈絡、只讀的審查者（僅給需求，未給實作說明）：**PASS，無 blocker**。採納並修復的 should-fix：(1) 驗簽前不應開交易（改 `TransactionTemplate`，驗簽通過後才開）；(2) 未驗證的 `BigDecimal` 在算術前先限制範圍（`1E+100000000` 類 DoS）＋DTO `@Positive/@Digits`；(3) 簽章正確但金額不符的 SUCCESS 不可丟棄（改標 `REFUND_REQUIRED`）。另修 nits：`Locale.ROOT`、未知 `pay_status` 值防禦、`payable` 檢查金額 > 0、啟用 sandbox 卻用預設 secret 時記 ERROR。

## 未證明 / 後續（不阻擋本項）

- **未做真實金流商端對端**：沒有綠界/藍新測試帳號與可公開的 callback URL；目前只證明「簽章式回調 + 冪等狀態機」，真實 provider 的欄位/CheckMacValue/redirect 需另接 `PaymentGateway`。
- 審查者是同一模型家族的新 session，不是 Codex 的獨立審查；如需更強保證可再請 Codex 過一次。
- 退款本身（呼叫 provider 退款 API、後台處理 `REFUND_REQUIRED`）、付款逾期自動取消、付款事件稽核（`audit_log` 目前不含付款事件）、對 callback 的速率限制、正式環境禁止預設 secret 的啟動檢查（目前僅 ERROR log）。
- **業務決策待定**：出貨/確認尚未要求已付款（賣家可對未付款訂單 CONFIRMED/SHIPPED），保留 #11 行為以支援貨到付款情境。
- 遲到的 SUCCESS 落在已關閉（FAILED）嘗試時一律標退款，即使訂單仍未付款（保守、避免重複計入；買家需重付）。
- 尚無 live 瀏覽器 E2E。

## 本機啟用 sandbox

```bash
PAYMENT_SANDBOX_ENABLED=true PAYMENT_CALLBACK_SECRET=<至少16字元的隨機字串> mvn spring-boot:run
```

Engineering note：付款狀態的權威是「經過驗證的 provider 回調 + 被鎖住的 DB 列上的 compare-and-set」，不是客戶端的任何欄位；凡是與取消/付款交錯的路徑都用同一個鎖順序（order → payment），並讓「無法套用的錢」有帳可查而不是消失。
