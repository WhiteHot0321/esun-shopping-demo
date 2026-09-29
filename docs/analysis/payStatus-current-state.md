# payStatus 現狀分析

**日期**: 2026-09-17  
**結論**: payStatus 目前是**無用的裝飾欄位** —— 存在但無業務邏輯

---

## 1. 現狀圖解

```
前端 (ShopWorkspace.vue)
  └─ form.payStatus = 'PENDING' (預設，用戶可改)
     └─ POST /api/orders
        └─ CreateOrderRequest { payStatus: 'PENDING' }
           └─ OrderTransactionService.doCreateOrder()
              └─ ShopOrder.setPayStatus(request.getPayStatus().ordinal())
                 └─ INSERT INTO shop_order (pay_status) VALUES (0)
                    └─ ✅ 存進 DB
                    └─ ❌ 之後完全沒人用
```

---

## 2. 關鍵代碼位置

### A. 數據庫層
**檔案**: `backend/DB/01_schema.sql` (L22)
```sql
pay_status   TINYINT NOT NULL DEFAULT 0,
```
- 存儲為整數 (0=PENDING, 1=PAID, 2=SHIPPED)
- 預設值 0（PENDING）
- **沒有任何觸發器或約束**

### B. 模型層
**檔案**: `backend/src/main/java/com/esun/shop/model/PayStatus.java`
```java
public enum PayStatus {
    PENDING,    // ordinal=0
    PAID,       // ordinal=1
    SHIPPED     // ordinal=2
}
```
- 定義了三個狀態
- **但系統中只會用到 PENDING**（預設值）

### C. DTO 層
**檔案**: `backend/src/main/java/com/esun/shop/dto/CreateOrderRequest.java` (L23)
```java
@NotNull
private PayStatus payStatus;
```
- 客戶端必須提供（@NotNull）
- **但系統無法校驗或轉換這個值**

### D. 前端層
**檔案**: `frontend/src/components/ShopWorkspace.vue` (L42)
```javascript
const form = reactive({ memberId: auth.email, payStatus: 'PENDING' })
```
- 總是設為 'PENDING'
- CartPanel.vue 中有單選鈕讓用戶改，但**完全是擺設**

---

## 3. 缺陷清單

| 項目 | 現狀 | 應該怎樣 |
|------|------|--------|
| **訂單建立時** | payStatus 固定為 PENDING | ✅ 正確 —— 下單時應該待支付 |
| **支付後** | payStatus 仍然 PENDING，無人更新 | ❌ 應轉為 PAID（但系統無金流邏輯） |
| **查詢訂單** | OrderController 無端點 | ❌ 無法查，所以 payStatus 無人見 |
| **業務規則** | 無任何校驗（如禁止對已支付訂單重複下單） | ❌ 缺少防護 |
| **前端單選鈕** | 用戶可改 payStatus，但改完沒效果 | ❌ UX 混淆 |

---

## 4. 為什麼 payStatus 現在沒用

### 缺 ①：無金流系統
- 沒有支付商 API（ECPay/NewebPay）
- 沒有 webhook 處理回調
- 沒有端點更新 payStatus PENDING → PAID

### 缺 ②：無訂單查詢
- `OrderController` 只有 `POST /api/orders`
- 無 `GET /api/orders/{id}` 端點
- 所以用戶無法看到自己訂單的 payStatus

### 缺 ③：無業務規則
- 沒有檢查「能否修改已支付訂單」
- 沒有「payStatus=PENDING 且逾期未支付，自動取消」的邏輯
- 沒有「已支付訂單不能重複下單」的冪等檢查

---

## 5. Phase 3 的修復方案

### Phase 3.0（訂單查詢）
```
✅ 新增 GET /api/orders/{orderId}
   └─ 前端「我的訂單」頁面可查看 payStatus
   └─ 用戶終於能看到 payStatus 的值
```

### Phase 3.1（金流整合）
```
✅ 集成支付商 API（例 ECPay）
   ├─ 訂單建立時：payStatus = PENDING
   ├─ 用戶導向 ECPay 支付
   ├─ ECPay 回調 POST /api/payments/callback
   └─ 驗簽後更新：payStatus = PAID

✅ 新增業務規則
   ├─ 相同 requestId 的重複下單：payStatus 檢查冪等
   ├─ 已支付訂單無法再修改
   └─ 支付失敗可重試（防重複扣款）
```

---

## 6. 前端 CartPanel 的單選鈕現況

**檔案**: `frontend/src/components/CartPanel.vue` (L50-51)
```vue
<label v-for="option in payOptions">
  <input v-model="form.payStatus" type="radio" :value="option.value">
</label>
```

**現狀**：
- payOptions 可能是 `['PENDING', 'PAID', 'SHIPPED']`
- 用戶可以手動改成 'PAID' 或 'SHIPPED'
- **但改完之後，系統接收到的值也只會存進 DB，沒有任何特殊處理**
- ❌ 用戶可以下單時自己聲稱「已支付」，而系統信了

**隱藏風險**：
- 任何人都可以 `POST /api/orders` 時設 `payStatus: "PAID"`，系統直接存進 DB
- 無真實支付，訂單已標記為「已支付」 —— **業務邏輯破裂**

---

## 7. 建議

### 短期（Phase 3.0）
✅ **不改 payStatus 邏輯，僅補齊查詢**
- 新增 `GET /api/orders/{orderId}` 端點
- 前端展示 payStatus（儘管目前都是 PENDING）
- payStatus 值無害，只是暫時無用

### 中期（Phase 3.1）
⚠️ **金流整合時必須同時修復 payStatus 邏輯**
- 支付商回調時，**後端才能改 payStatus**（不信客戶端聲稱）
- 移除前端「手動改 payStatus」的 UI（CartPanel 單選鈕）
- payStatus 改為唯讀（從支付商決定）

### 立即修復
❌ **現在不要改** —— 如果現在移除 CartPanel 的單選鈕，用戶會發現無法下單（因為 payStatus 被當成必填 @NotNull）
- 應等到金流邏輯完成，payStatus 由系統自動設為 PENDING

---

## 8. 測試覆蓋

### 缺失的測試
```java
// ❌ 目前沒有這個測試
@Test
public void testCreateOrderWithDifferentPayStatus() {
    // 如果客戶端聲稱 payStatus = "PAID"，
    // 系統應該無視並改為 "PENDING"
    // 或者拒絕（403）未支付訂單不能標記為已支付
}

@Test
public void testPayStatusUpdateOnPaymentCallback() {
    // ❌ 目前沒有支付回調，所以無此測試
}
```

---

## 結論

| 現況 | 原因 | Phase 修復 |
|------|------|-----------|
| payStatus = 無用裝飾欄位 | 缺金流、缺查詢、缺業務規則 | 3.0 + 3.1 同時進行 |
| 用戶可手動改 payStatus | 前端無驗證、後端無檢查 | 3.1 金流後移除此 UI |
| 所有訂單都是 PENDING | 無人更新 payStatus | 3.1 支付商回調後更新 |

**決策**：Phase 3.0 先補查詢功能，Phase 3.1 一併修復 payStatus 的完整流程。
