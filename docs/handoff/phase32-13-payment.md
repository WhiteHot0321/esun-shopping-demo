# Phase 3.2 #13 handoff

Goal: 金流整合 — 訂單 payStatus 只能由經驗證的 provider 回調推進，關閉「客戶端自報 PAID」漏洞。

Changed: `01_schema.sql`, `13_payment.sql`（`payment` 表）; `PaymentStatus`, `PaymentResult`, `PayStatus`(doc), `PaymentGateway`, `HmacPaymentGateway`, `PaymentService`, `PaymentCallbackService`, `PaymentRepository`, `PaymentController`, DTOs `PaymentView`/`PaymentCallbackRequest`/`SandboxPaymentResultRequest`; `OrderTransactionService`（強制 PENDING）, `CreateOrderRequest`, `CartService`, `OrderRepository`/`OrderView`/`OrderStatusService`（付款欄位 + 取消時結算付款）, `JwtAuthFilter`（公開 webhook）, `application.yml`; frontend `OrdersPanel.vue`, `CartPanel.vue`, `ShopWorkspace.vue` + specs; tests `PaymentIntegrationTest`(16), `PaymentDisabledIntegrationTest`(1), `HmacPaymentGatewayTest`(8), fixture/cleanup updates.

Validated: backend `mvn clean test` **184/184**, JaCoCo PASS; frontend Vitest **56/56**, checkout 3/3, build PASS. 獨立（全新脈絡、唯讀）審查 PASS，3 項 should-fix 已修復並重測。

Not proven: 真實金流商（綠界/藍新）端對端；Codex 獨立審查；live 瀏覽器 E2E；退款流程、逾期自動取消、付款稽核、callback 速率限制。

Risks: 未合併分支 `codex/phase31-pay-status` 含另一版真實 ECPay 實作，類別名衝突、表不同，勿直接 merge，應把其 `EcpayPaymentGateway` 移植為 `PaymentGateway` 的第二實作（見 task 035）；sandbox 預設關閉——本機需 `PAYMENT_SANDBOX_ENABLED=true` 才能付款；賣家仍可對未付款訂單出貨（業務決策待定）。

Next: 決定 ECPay 整合路線（移植上述 gateway）；其後收藏/心願單、優惠券（019 Phase 3.2）或 Phase 3 工程深度主線；若要上真實 provider，實作 `PaymentGateway` 並取得測試帳號。
