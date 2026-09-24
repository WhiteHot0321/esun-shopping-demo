# Phase 3.2 #14 handoff

Goal: 優惠券系統 — ADMIN 發行平台優惠碼，買家結帳套用；折扣由伺服器在訂單交易內鎖定 coupon 列後計價，付款沿用折後 `shop_order.price`。

Changed: `backend/DB/14_coupon.sql`（`coupon`、`coupon_member_usage`，`shop_order` 加 `coupon_id/coupon_code/discount_amount`，可重複執行）; `DiscountType`, `AuditAction`(+COUPON_CREATE/UPDATE), `ShopOrder`; `CouponRepository`, `CouponService`, `CouponController`（ADMIN 建立/列表/更新/`/active` 切換）, `CartController`（`POST /api/cart/coupon-preview`、checkout 加 `couponCode`）, `CartService`, `OrderTransactionService`（結帳內 redeem）, `OrderStatusService`（取消時 release，鎖順序 order→coupon→usage→product）, `OrderRepository`（`lockHeader` 改 `FOR UPDATE OF o`）, `OrderView`/`CreateOrderRequest`/DTOs; frontend `CartPanel.vue`（優惠碼欄位+預覽）, `ShopWorkspace.vue`, `OrdersPanel.vue`（折扣顯示）, `CouponPanel.vue`（ADMIN）, `AuditLogPanel.vue`, `App.vue` + specs; tests `CouponServiceTest`(6), `CouponIntegrationTest`(13), fixture 加 `14_coupon.sql`.

Validated: backend `mvn clean test` **225/225** + JaCoCo PASS; frontend checkout 3/3 + Vitest **71/71**, build PASS; 兩次變異驗證（拿掉 coupon 列鎖、還原 `OF o`）皆使對應測試失敗；真實 MySQL + 後端 + 瀏覽器 E2E（建立→套用→結帳→折後付款 2160→重複使用 409→已付款訂單取消→名額釋放、REFUND_REQUIRED）。獨立（全新脈絡、唯讀）審查首輪 FAIL，1 項 must-fix（cancel/checkout 經 shipping_address 列鎖形成死結，已重現並修復）+ 1 項空洞測試 + 5 項 should-fix，均已修復並窄範圍重測。細節：`docs/tasks/036-coupon-system.md`。

Not proven: Codex 獨立審查；`coupon-preview` 速率限制；賣家自建優惠券、疊加、首購規則、到期背景任務；退款實際執行；Redis Lua 取代 coupon 列鎖的熱點方案。

Risks: 熱門優惠券的兌換在各訂單交易 commit 前會序列化於 coupon 列；預覽為 advisory，配額可能在預覽與結帳之間被用完（結帳回 409）；非整數商品價仍會產生小數應付（既有問題，ECPay 會拒絕）。

Next: 收藏/心願單，或回 Phase 3 工程深度主線（k6 熱點優惠券壓測、Redis Lua、CI/CD）；若要驗證 ECPay 真實入站回調需公開 HTTPS callback URL（見 task 035）。
