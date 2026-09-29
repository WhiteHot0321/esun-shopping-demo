# esun-shopping 功能缺陷分析 & Phase 3+ 路線圖

**日期**: 2026-09-17  
**分析人員**: Claude Haiku 4.5  
**狀態**: 候選提示詞，未實作

---

## 背景

截至 Phase 2.1 完成（LLM 客服 + 並發冪等），購物車已有完整的**下單 → 支付狀態記錄 → 客服**流程。本文分析現有功能對比正常電商網站的缺口，並按實作難度 + 商業價值排序。

## 已實現功能清單

### 核心交易
- ✅ 商品列表（卡片化，LLM 嵌入搜尋）
- ✅ 購物車（前端邊欄）
- ✅ 訂單創建 + 庫存扣減（並發安全，冪等性）
- ✅ JWT 認證（註冊/登入/登出）
- ✅ 訂單冪等性（requestId 去重）

### 性能 & 穩定
- ✅ Redis 快取（庫存/會話）
- ✅ InnoDB 死鎖檢測 + HTTP 重試
- ✅ 並發負載測試基線（k6）

### 客服
- ✅ LLM AI 聊天（Ollama 嵌入 FAQ）
- ✅ 文本生成式回答

---

## 缺陷分析

### 🔴 **Tier 1：立即阻斷商業運營**

#### 1.1 訂單查詢 / 歷史檢視（**優先級 P0**）
```
問題描述：
  - 用戶完成訂單後，無法查看過往訂單
  - 只在結帳成功那一刻看一次訂單 ID（吐司提醒）
  - DB 表 shop_order 有 member_id 關鍵字，但無查詢端點

實作難度：低 ⭐
影響範圍：
  - 後端：GET /api/orders (list) + /api/orders/{orderId} (detail)
  - 前端：新增「我的訂單」頁面 + 篩選/搜尋
  - DB：無改動（表結構已備）

提示詞關鍵字：order history, member orders, order detail page
```

#### 1.2 金流整合（**優先級 P0**）
```
問題描述：
  - 訂單表有 payStatus 欄位，但後端未實作實際支付邏輯
  - 目前所有訂單都標記為「待確認」，無扣款流程
  - 無法區分「用戶下單但未付款」vs「已付款」

實作難度：中-高 ⭐⭐⭐
影響範圍：
  - 後端：呼叫第三方支付 API（綠界/藍新）→ 簽名驗證 → 回調更新訂單
  - 前端：付款方式選擇器 + 重導結帳頁面
  - 需要 API key / 測試帳號（非代碼改動）

提示詞關鍵字：payment gateway integration, ECPay/NewebPay, order payment status, webhook
```

### 🟡 **Tier 2：限制用戶黏著度**

#### 2.1 商品管理介面（**優先級 P1**）
```
問題描述：
  - 只有靜態「上架新商品」表單（無驗證、無圖片、無分類）
  - 無法編輯 / 下架 / 批量操作
  - 缺乏賣家後台 (Admin Panel)

實作難度：中 ⭐⭐
影響範圍：
  - 後端：PUT /api/products/{id} / DELETE / GET admin/products
  - 前端：商品管理表格 + CRUD 介面 + 上傳圖片
  - 權限：需區分賣家 vs 一般用戶角色

提示詞關鍵字：product management, admin panel, CRUD, image upload
```

#### 2.2 訂單狀態流程（**優先級 P1**）
```
問題描述：
  - shop_order.order_status 欄位存在但邏輯未實作
  - 用戶看不到「已確認」→「已出貨」→「已送達」的狀態變化
  - 無通知/推播機制

實作難度：低-中 ⭐⭐
影響範圍：
  - 後端：狀態轉移邏輯 + 通知隊列（或簡易輪詢）
  - 前端：訂單詳情頁顯示時間軸
  - DB：無改動

提示詞關鍵字：order status tracking, order timeline, order lifecycle
```

#### 2.3 用戶收藏 / 心願單（**優先級 P2**）
```
問題描述：
  - 用戶無法保存感興趣的商品
  - 每次都要重新搜尋

實作難度：低 ⭐
影響範圍：
  - 後端：新表 user_favorites / 端點 POST/DELETE
  - 前端：「收藏」按鈕 + 心願單頁面
  - DB：一張簡單關聯表

提示詞關鍵字：wishlist, favorite products, save for later
```

### 🟢 **Tier 3：提升轉化 & 留存**

#### 3.1 使用者評論系統（**優先級 P2**）
```
問題描述：
  - 無法看到其他買家的使用心得
  - 缺乏社交證明（reviews）

實作難度：中 ⭐⭐
影響範圍：
  - 後端：新表 product_reviews / 端點 POST/GET
  - 前端：評論卡片 + 星評顯示
  - 策略：只有購買過該商品的人才能評

提示詞關鍵字：product reviews, ratings, user feedback
```

#### 3.2 優惠券 / 折扣碼（**優先級 P2**）
```
問題描述：
  - 無法進行行銷活動（如「首購 9 折」）
  - 訂單表無 coupon 或 discount 欄位

實作難度：中-高 ⭐⭐⭐
影響範圍：
  - 後端：新表 coupons + 驗證邏輯 + 價格計算
  - 前端：輸入碼的欄位 + 折扣預覽
  - DB：需檢查訂單金額計算邏輯

提示詞關鍵字：discount code, coupon validation, order total calculation
```

#### 3.3 推薦系統（**優先級 P3**）
```
問題描述：
  - 首頁只是靜態商品列表
  - 無基於瀏覽 / 購買紀錄的推薦

實作難度：高 ⭐⭐⭐⭐
影響範圍：
  - 後端：行為追蹤表 + ML/相似度計算
  - 前端：推薦卡片區塊
  - 可用 LLM embedding 計算相似度

提示詞關鍵字：recommendation engine, personalization, collaborative filtering
```

---

## 功能矩陣（優先級 × 難度）

| 功能 | 難度 | 商業價值 | Phase 建議 | 工作量估計 |
|------|------|---------|-----------|---------|
| 訂單查詢 | ⭐ 低 | 🔴 關鍵 | **Phase 3.0** | 1-2 天 |
| 金流整合 | ⭐⭐⭐ 高 | 🔴 關鍵 | **Phase 3.1** | 3-5 天 |
| 訂單狀態 | ⭐⭐ 中 | 🟡 高 | Phase 3.0 | 1 天 |
| 商品管理 | ⭐⭐ 中 | 🟡 高 | Phase 3.1 | 2-3 天 |
| 收藏清單 | ⭐ 低 | 🟡 中 | Phase 3.0 | 0.5 天 |
| 評論系統 | ⭐⭐ 中 | 🟢 中 | Phase 3.2 | 2 天 |
| 優惠券 | ⭐⭐⭐ 高 | 🟢 高 | Phase 3.2 | 2-3 天 |
| 推薦系統 | ⭐⭐⭐⭐ 高 | 🟢 中 | Phase 3.3+ | 4-5 天 |

---

## 提示詞範本

### Phase 3.0 提示詞（訂單查詢 + 狀態）

```markdown
## 背景
esun-shopping 購物車已實現訂單創建、並發控制、冪等性。缺乏用戶查詢自己訂單的能力。

## 任務
1. 後端：實作 GET /api/orders (列表，支援分頁/篩選) + GET /api/orders/{orderId} (詳情)
2. 前端：新增「我的訂單」頁面，展示訂單列表 + 詳情側欄
3. 訂單狀態：在 order_status 欄位上實作簡易狀態流（待確認→已出貨→已送達）

## 檢驗
- 認證用戶可查看 & 篩選自己的訂單（無法看他人訂單）
- 訂單詳情顯示商品明細、總金額、狀態時間軸
- 後端 API 支援分頁 & 日期篩選

## 設計考量
- 權限：order.member_id == current_user.id
- 效能：orders 表需索引 (member_id, created_at)
- 狀態轉移：可簡化為固定流程或管理員手動更新
```

### Phase 3.1 提示詞（金流）

```markdown
## 背景
訂單表有 payStatus 欄位但未實作。用戶無法真正購買商品。

## 任務
選擇支付商（綠界/藍新）並集成其 API：
1. 後端：建立支付請求簽名 → 重導用戶到支付商 → 接收回調 → 驗簽 → 更新訂單
2. 前端：顯示支付方式選擇器（信用卡/超商/銀行轉帳）
3. 提供測試卡號讓 QA 驗證端對端流程

## 檢驗
- 訂單創建後導向支付頁面
- 支付成功後訂單狀態變為「已付款」
- 支付失敗後用戶可重試（不重複扣款）

## 安全性
- 簽名驗證必須在後端完成
- 敏感 key 不可提交到版本控制
```

---

## 建議執行路徑

**當前進度**: Phase 2.1（LLM 客服）✅

### 短期（1-2 週）
1. **Phase 3.0 優先** → 訂單查詢 + 狀態（無外部依賴）
2. **Phase 3.0 同步** → 收藏清單（簡單，提升 UX）

### 中期（2-4 週）
3. **Phase 3.1** → 金流整合 + 商品管理（需第三方 API）

### 長期（可選）
4. **Phase 3.2+** → 評論、優惠、推薦

---

## 相關檔案

- DB Schema: `backend/DB/01_schema.sql`
- 訂單業務: `backend/src/main/java/com/esun/shop/service/OrderService.java`
- 前端訂單: `frontend/src/components/` (checkout 相關)

---

## 備註

- 此分析基於 2026-09-17 current HEAD（feature/frontend-ux-revamp）
- 功能清單對標一個正常的 B2C 電商（如蝦皮、PChome）
- 優先級排序兼顧商業價值與技術復雜度
- 具體實作時應再細化技術方案（如選擇 ECPay vs NewebPay）
