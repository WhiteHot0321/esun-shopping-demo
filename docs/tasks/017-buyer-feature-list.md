# esun-shopping 買家（Buyer）功能需求清單 & 缺口分析

**日期**: 2026-09-17
**分析人員**: Claude Sonnet 5
**狀態**: 候選提示詞，未實作
**對應角色文件**: 賣家清單見 `016-feature-gap-analysis.md`；維護者清單見 `018-maintainer-feature-list.md`；三份清單的切割/實作順序見 `019-phase3-role-based-split-plan.md`

---

## 背景

`016-feature-gap-analysis.md`（賣家/營運視角）已經涵蓋商品上架、庫存、金流、行銷這些「讓商店能運作」的功能缺口。本文改從**買家（一般消費者）的購物旅程**出發，只看買家在登入、逛、買、售後這條路徑上實際會用到、但現在缺少或不完整的功能。範圍會跟 016 有重疊（例如訂單查詢、金流、評論、推薦），重疊之處在文中標明「Cross-ref」，避免兩份文件各自重複規劃、日後改出兩套不一致的實作。

本文所有「已實現」與「缺陷」項目都已對照目前程式碼與 schema 驗證（非憑印象推測）：
- `backend/DB/04_member.sql`：`member` 表只有 `id / email / password_hash / created_at`，**沒有姓名、電話、收件地址欄位**。
- `backend/DB/01_schema.sql`：`shop_order` 只有 `order_id / member_id / price / pay_status / created_at`，**沒有 `order_status` 欄位**（訂正 016 文件中「order_status 欄位存在但邏輯未實作」的誤述——目前 schema 裡這個欄位根本不存在，需要新增而不是補邏輯）。
- `OrderController.java` 只有 `POST /api/orders`，沒有任何查詢端點。
- 前端購物車（`ShopWorkspace.vue` / `CartPanel.vue`）純粹是 Vue 記憶體內狀態，沒有 `localStorage` 或後端持久化——重新整理頁面購物車就會清空。

## 已實現功能清單（買家視角）

### 瀏覽 & 購物
- ✅ 商品瀏覽（卡片化商品列表，含庫存/價格）
- ✅ AI 商品客服（聊天 widget，問答含引用商品/FAQ 來源與相似度）
- ✅ 購物車（前端側邊欄，可調整數量、即時看小計/總計）

### 帳號
- ✅ 註冊 / 登入 / 登出（JWT，24 小時有效期）
- ✅ 逐欄位表單驗證訊息（不是籠統的「輸入錯誤」）

### 下單
- ✅ 單 / 多商品下單，結帳金額試算
- ✅ 下單冪等性（同一 `requestId` 重複送出不會重複扣庫存、回傳原訂單）
- ✅ 庫存不足時擋下並回傳 409（不會建立殘缺訂單）
- ✅ 下單成功回饋：sticky 訊息 banner + 訂單編號

---

## 缺陷分析

### 🔴 Tier 1：買家關鍵路徑阻斷

#### 1.1 訂單歷史 / 明細查詢（**優先級 P0**）
```
問題描述：
  - 買家下單後只在當下的 toast/banner 看過一次訂單編號，關掉頁面就再也找不到
  - shop_order 有 member_id，但沒有任何「列出我的訂單」端點
  - 買家視角的驗收重點跟賣家/維護者視角不同：買家只能看見自己的訂單，
    且需要基本的排序（新到舊）與依 pay_status 篩選

實作難度：低 ⭐
影響範圍：
  - 後端：GET /api/orders（僅回傳 authenticatedEmail 對應 member 的訂單，分頁）
          + GET /api/orders/{orderId}（需驗證 order.member_id == 目前使用者，否則 403/404）
  - 前端：新增「我的訂單」頁籤/頁面 + 訂單詳情側欄
  - DB：無改動（member_id 索引屬於維護者清單的效能項目，見 018）

Cross-ref: 015-order-history-candidate.md、016 的 1.1（同一後端端點，兩份清單的差異只在
  「買家只能看自己的」vs「賣家/維護者要看全店訂單佇列」——實作時應該是同一組 API 加上
  角色範圍過濾，而不是做兩套查詢邏輯，詳見 019 的切割建議）

提示詞關鍵字：my orders, order history, member-scoped order query
```

#### 1.2 收件資訊（收貨人 / 地址 / 電話）（**優先級 P0**）
```
問題描述：
  - member 表和 shop_order 表都沒有任何收件地址欄位
  - 目前的「下單」流程實際上無法出貨——沒有地方讓買家填寫要送到哪裡
  - 這比金流整合更基礎：沒有地址，付了錢也送不出去

實作難度：低-中 ⭐⭐
影響範圍：
  - DB：shop_order 新增 receiver_name / receiver_phone / shipping_address（或另開
    shipping_address 子表以支援買家儲存多筆地址，見 Tier 2 的地址簿）
  - 後端：CreateOrderRequest 加入收件欄位 + 驗證（@NotBlank 等）
  - 前端：結帳頁加入收件資訊表單

提示詞關鍵字：shipping address, receiver info, checkout form fields
```

#### 1.3 訂單狀態追蹤（**優先級 P0**）
```
問題描述：
  - 買家完全看不到「訂單到哪了」——只有內部的 pay_status（TINYINT，無語意化）
  - shop_order 沒有 order_status 欄位，這件事要先建欄位才能談邏輯
    （訂正 016 文件對此欄位「已存在」的誤述）
  - 對買家而言，「訂單狀態可視化」是回購率的關鍵因素之一

實作難度：低-中 ⭐⭐
影響範圍：
  - DB：shop_order 新增 order_status（enum 或 TINYINT + 對照表：待確認/已出貨/已送達/已取消）
  - 後端：狀態轉移邏輯（可先簡化為手動或固定流程，通知機制留到 Tier 3）
  - 前端：訂單詳情頁顯示狀態時間軸

Cross-ref: 016 的 2.2（賣家/營運視角是「誰來把狀態轉下一步」，買家視角是「怎麼呈現給我看」，
  同一個 DB 欄位、同一組狀態機，兩邊只是不同的讀寫端點）

提示詞關鍵字：order status tracking, order timeline, order lifecycle
```

#### 1.4 忘記密碼 / 修改密碼（**優先級 P0**）
```
問題描述：
  - member 表只有 email + password_hash，沒有任何密碼重設流程
  - AuthController 目前只有 register/login，沒有 change-password、沒有
    forgot-password/reset-password
  - 買家一旦忘記密碼，唯一的救濟手段是要求維運者手動改資料庫——這在真實
    營運中完全不可行，也是資安上的壞味道（人工改密碼欄位等於繞過雜湊流程）

實作難度：中 ⭐⭐
影響範圍：
  - 後端：POST /api/auth/change-password（需已登入）+ POST /api/auth/forgot-password
    （寄送重設連結/驗證碼，需要 email 發送機制，可先做「產生一次性 token」的最小版本）
  - 前端：忘記密碼表單、修改密碼表單（個人資料頁的一部分，見 Tier 2.1）
  - 安全性：重設 token 需有效期限 + 一次性使用，避免被猜測或重放

提示詞關鍵字：password reset, change password, forgot password flow
```

---

### 🟡 Tier 2：買家黏著度

#### 2.1 個人資料管理（**優先級 P1**）
```
問題描述：
  - 買家完全沒有「會員中心」頁面——改 email、改密碼、看收件資訊都無處可去
  - member 表也沒有姓名/電話欄位可供編輯（跟 1.2 的收件地址是相關但不同的缺口：
    1.2 是「這筆訂單」的收件資訊，這裡是「這個帳號」的個人資料）

實作難度：低-中 ⭐⭐
影響範圍：
  - DB：member 表新增 display_name / phone（可為 null，逐步補齊）
  - 後端：GET/PUT /api/member/profile
  - 前端：會員中心頁面

提示詞關鍵字：member profile, account settings
```

#### 2.2 收件地址簿（多筆地址管理）（**優先級 P1**）
```
問題描述：
  - 1.2 只解決「這一筆訂單填一次地址」，買家若常訂送不同地點（公司/家裡/代收點），
    每次都要重打一次
  - 沒有「預設地址」概念，結帳體驗會比一般電商差一截

實作難度：低 ⭐
影響範圍：
  - DB：新表 shipping_address（member_id, label, receiver_name, phone, address, is_default）
  - 後端：CRUD 端點
  - 前端：地址簿頁面 + 結帳時選擇/新增地址

提示詞關鍵字：address book, saved addresses, default shipping address
```

#### 2.3 購物車持久化（**優先級 P1**）
```
問題描述：
  - 目前購物車是純前端記憶體狀態（ShopWorkspace.vue / CartPanel.vue 均未使用
    localStorage，也沒有對到後端），重新整理頁面或跨裝置購物車就消失
  - 對比登入機制已經有 JWT，購物車卻沒有跟著帳號走，體驗不一致

實作難度：低-中 ⭐⭐
影響範圍：
  - 選項 A（快）：前端 localStorage 持久化，重整不遺失，但換裝置仍不同步
  - 選項 B（完整）：後端 cart 表跟 member_id 綁定，登入後從伺服器讀回
  - 前端：CartPanel.vue 加入持久化邏輯（watch + onMounted 讀取）

提示詞關鍵字：cart persistence, localStorage cart, server-side cart
```

#### 2.4 商品評論與評分（**優先級 P2**）
```
問題描述：
  - 買家看不到其他人的使用心得，也無法為買過的商品留下評論/星等
  - 缺乏社交證明，影響轉化率

實作難度：中 ⭐⭐
影響範圍：
  - 後端：新表 product_reviews（member_id, product_id, rating, comment）+ 端點
  - 前端：商品卡片顯示平均星等，商品詳情顯示評論列表 + 「我買過，可以評論」表單
  - 策略：只有該商品出現在買家 order_detail 中的人才能評論（防灌水）

Cross-ref: 016 的 3.1（同一功能，賣家視角關心「能不能管理/隱藏不當評論」，
  買家視角關心「能不能看到/寫評論」——後台管理歸賣家清單，寫評論/看評論歸這裡）

提示詞關鍵字：product reviews, ratings, verified purchase review
```

---

### 🟢 Tier 3：買家轉化 & 售後

#### 3.1 收藏 / 心願單（**優先級 P2**）
```
問題描述：
  - 買家無法保存感興趣但還不想買的商品，每次都要重新搜尋

實作難度：低 ⭐
影響範圍：
  - 後端：新表 user_favorites + 端點
  - 前端：商品卡片「收藏」按鈕 + 心願單頁面

Cross-ref: 016 的 2.3（同一項目，優先級評估一致，直接沿用不重複規劃）

提示詞關鍵字：wishlist, favorite products
```

#### 3.2 優惠券輸入（買家視角）（**優先級 P2**）
```
問題描述：
  - 結帳流程沒有輸入折扣碼的欄位，即使賣家/營運端做了優惠券系統，買家也用不到

實作難度：中-高 ⭐⭐⭐（多數工作量在後端驗證與金額計算，屬 016 的 3.2）
影響範圍：
  - 前端：結帳頁輸入框 + 折扣預覽
  - 依賴：016 的優惠券後端機制需先存在

提示詞關鍵字：discount code input, coupon apply, checkout discount preview
```

#### 3.3 到貨 / 降價通知訂閱（**優先級 P3**）
```
問題描述：
  - 商品缺貨時買家無法登記「補貨通知我」，降價也不會主動告知，只能自己常回來看

實作難度：中 ⭐⭐
影響範圍：
  - 後端：新表 product_notifications（member_id, product_id, type）+ 觸發邏輯
    （庫存從 0 變 >0、price 下降時掃描並發送）
  - 通知管道：可先做站內通知，email 留待有寄信機制後再擴充

提示詞關鍵字：restock notification, price drop alert, notify me
```

#### 3.4 訂單問題 / 退換貨自助申請（**優先級 P3**）
```
問題描述：
  - 訂單有問題（缺件、想退貨）目前完全沒有自助入口，只能透過 AI 客服問，
    但 AI 客服目前只回答商品/FAQ 知識，不處理個案工單

實作難度：中-高 ⭐⭐⭐
影響範圍：
  - 後端：新表 order_issue（order_id, member_id, type, description, status）
  - 前端：訂單詳情頁「申請退換貨/回報問題」表單 + 進度查詢
  - 需要 1.3（訂單狀態）先存在，退換貨狀態機才有掛靠的地方

提示詞關鍵字：return request, refund request, order issue ticket
```

---

## 功能矩陣（優先級 × 難度）

| 功能 | 難度 | 對買家的價值 | Phase 建議 | 依賴 |
|------|------|------|-----------|------|
| 訂單歷史查詢 | ⭐ 低 | 🔴 關鍵 | Phase 3.0 | 無 |
| 收件資訊 | ⭐⭐ 低-中 | 🔴 關鍵 | Phase 3.0 | 無 |
| 訂單狀態追蹤 | ⭐⭐ 低-中 | 🔴 關鍵 | Phase 3.0 | 需先建 order_status 欄位 |
| 忘記/修改密碼 | ⭐⭐ 中 | 🔴 關鍵 | Phase 3.0 | 需 email 發送機制（可先做 token-only 版） |
| 個人資料管理 | ⭐⭐ 低-中 | 🟡 高 | Phase 3.1 | 無 |
| 收件地址簿 | ⭐ 低 | 🟡 高 | Phase 3.1 | 建議與 1.2 一併設計 schema |
| 購物車持久化 | ⭐⭐ 低-中 | 🟡 高 | Phase 3.1 | 無 |
| 商品評論 | ⭐⭐ 中 | 🟡 高 | Phase 3.1 | 無 |
| 收藏/心願單 | ⭐ 低 | 🟢 中 | Phase 3.1 | 無 |
| 優惠券輸入（前端） | ⭐⭐⭐ 中-高 | 🟢 中 | Phase 3.2 | 依賴 016 的優惠券後端 |
| 到貨/降價通知 | ⭐⭐ 中 | 🟢 中 | Phase 3.2 | 無 |
| 退換貨自助申請 | ⭐⭐⭐ 中-高 | 🟢 中 | Phase 3.3 | 依賴訂單狀態機（1.3） |

---

## 提示詞範本

### Phase 3.0 提示詞（我的訂單 + 收件資訊 + 訂單狀態）

```markdown
## 背景
esun-shopping 買家下單後無法查詢歷史訂單，也沒有收件地址欄位，shop_order 沒有 order_status。
三者互相依賴，適合在同一個 Phase 3.0 分支內一起做基礎 schema，但拆成 A 定位 / B 實作 / C 驗收
三個獨立提示詞執行（見 019-phase3-role-based-split-plan.md 的切割方式）。

## 任務（B 階段，範圍已由 A 階段確認後才展開）
1. DB：shop_order 新增 order_status、receiver_name、receiver_phone、shipping_address 欄位
   （只改這一張表，不動 product/order_detail）
2. 後端：GET /api/orders（限本人，分頁）、GET /api/orders/{orderId}（403 若非本人）、
   CreateOrderRequest 加入收件欄位驗證
3. 前端：「我的訂單」頁面 + 結帳頁收件資訊表單

## 檢驗
- 買家只能查到自己的訂單，查他人訂單回 403/404（不是回傳資料再讓前端隱藏）
- 訂單詳情正確顯示商品明細、收件資訊、狀態
- 既有 81 個後端測試 + 前端測試全數通過，無回歸

## 明確範圍限制（依 prompt-scope-control 規則）
- 可讀：OrderController/OrderService/OrderRepository、shop_order 相關 DTO、
  ShopWorkspace.vue、CartPanel.vue、01_schema.sql
- 可改：上述檔案 + 新增的 Order 查詢/收件相關檔案；不觸碰 ProductController、
  SupportController、認證流程
- 要跑：mvn clean test（全量，確認無回歸）+ npm test + npm run build
```

---

## 建議執行路徑

**當前進度**: Phase 2.1（LLM 客服）✅，`feature/frontend-ux-revamp` 已驗收

### 短期（1-2 週）
1. **Phase 3.0**：訂單歷史 + 收件資訊 + 訂單狀態（三者共用 shop_order 改動，一次做完 schema 遷移比分三次改動安全）
2. **Phase 3.0 同步**：忘記/修改密碼（獨立分支，不碰 shop_order，可並行）

### 中期（2-4 週）
3. **Phase 3.1**：個人資料管理 + 地址簿 + 購物車持久化 + 商品評論

### 長期（可選）
4. **Phase 3.2+**：優惠券輸入、到貨通知、退換貨自助申請

---

## 相關檔案

- Member schema: `backend/DB/04_member.sql`
- Order schema: `backend/DB/01_schema.sql`
- 訂單業務: `backend/src/main/java/com/esun/shop/service/OrderService.java`
- 訂單端點: `backend/src/main/java/com/esun/shop/controller/OrderController.java`
- 認證端點: `backend/src/main/java/com/esun/shop/controller/AuthController.java`
- JWT 過濾器（公開路由清單）: `backend/src/main/java/com/esun/shop/security/JwtAuthFilter.java`
- 前端購物車: `frontend/src/components/CartPanel.vue`、`frontend/src/components/ShopWorkspace.vue`
- 賣家清單: `docs/tasks/016-feature-gap-analysis.md`
- 買家訂單查詢的既有草案: `docs/tasks/015-order-history-candidate.md`

---

## 備註

- 此分析基於 2026-09-17 對照目前 HEAD（`feature/frontend-ux-revamp`）的實際 schema 與程式碼驗證，
  不是憑 016 文件內容推測——發現並訂正了 016 對 `order_status` 欄位存在性的誤述。
- 與 016（賣家）、018（維護者）重疊的項目已標明 Cross-ref，實作時應共用同一組 DB 欄位/API，
  不要各自開發出兩套。
- 三份清單的整合切割與分支/提示詞策略見 `019-phase3-role-based-split-plan.md`。
