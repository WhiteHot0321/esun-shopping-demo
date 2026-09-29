# 前端 payStatus 修改方案

**優先級**：🔴 **Phase 3.0 立即修改**（不等金流）  
**影響範圍**：CartPanel.vue + 後端 DTO  
**目的**：移除用戶騙系統「已付款」的漏洞

---

## 問題代碼

**檔案**: `frontend/src/components/CartPanel.vue` (L47-55, L84)

```vue
<!-- ❌ 現在這樣 —— 用戶可騙系統 -->
<fieldset class="field">
  <legend>付款狀態</legend>
  <div class="segmented segmented--full">
    <label v-for="option in payOptions" :key="option.value" :class="{ active: form.payStatus === option.value }">
      <input v-model="form.payStatus" class="visually-hidden" type="radio" name="pay-status" :value="option.value"
        :disabled="busy" />{{ option.label }}
    </label>
  </div>
</fieldset>

const payOptions = [
  { value: 'PENDING', label: '未付款' }, 
  { value: 'PAID', label: '已付款' }
]
```

**風險**：
- 用戶可以選 "已付款"（PAID）
- 系統信了，存進 DB
- 沒實際支付，訂單卻標記為已支付 ⚠️

---

## 修改方案（Phase 3.0 立即做）

### 前端修改

#### 1️⃣ CartPanel.vue —— 替換第 47-55 行

```vue
<!-- ✅ 改成這樣 —— 固定顯示待支付 -->
<div class="field">
  <label for="pay-status">訂單狀態</label>
  <div id="pay-status" class="field-value">
    <span class="badge badge--info">待支付</span>
  </div>
  <p class="field__hint">訂單建立後，請在「我的訂單」頁面進行支付（Phase 3.1 實裝）</p>
</div>
```

#### 2️⃣ 刪除 payOptions 常數（L84）

```javascript
// ❌ 刪除這行
const payOptions = [{ value: 'PENDING', label: '未付款' }, { value: 'PAID', label: '已付款' }]
```

#### 3️⃣ 後端已準備

後端 `ShopWorkspace.vue` 會自動設：
```javascript
const form = reactive({ 
  memberId: auth.email, 
  payStatus: 'PENDING'  // ← 已是預設
})
```

---

## 修改步驟

### Step 1: 前端修改

**檔案**: `frontend/src/components/CartPanel.vue`

```diff
    <form class="checkout" novalidate @submit.prevent="$emit('checkout')">
      <template v-if="authenticated">
        <div class="field">
          <label for="member-id">會員編號</label>
          <input id="member-id" v-model.trim="form.memberId" autocomplete="off" :disabled="busy" />
          <p class="field__hint">預設為登入 Email，可視需要修改</p>
        </div>

-       <fieldset class="field">
-         <legend>付款狀態</legend>
-         <div class="segmented segmented--full">
-           <label v-for="option in payOptions" :key="option.value" :class="{ active: form.payStatus === option.value }">
-             <input v-model="form.payStatus" class="visually-hidden" type="radio" name="pay-status" :value="option.value"
-               :disabled="busy" />{{ option.label }}
-           </label>
-         </div>
-       </fieldset>
+       <div class="field">
+         <label for="pay-status">訂單狀態</label>
+         <div id="pay-status" class="field-value">
+           <span class="badge badge--info">待支付</span>
+         </div>
+         <p class="field__hint">訂單建立後，請在「我的訂單」頁面進行支付</p>
+       </div>

      <button type="submit" class="btn btn--primary btn--block btn--lg"
```

### Step 2: 移除 payOptions（同檔案 L84）

```diff
- const payOptions = [{ value: 'PENDING', label: '未付款' }, { value: 'PAID', label: '已付款' }]
  const count = computed(() => props.items.reduce((sum, item) => sum + item.quantity, 0))
```

### Step 3: 測試

```bash
npm test  # 應該全部通過（無 payOptions 相關測試）
npm run build  # 驗證編譯無誤
```

---

## 後端建議（同步進行）

### 如果用戶還是自己改 payStatus 怎辦？

**現況**：CreateOrderRequest 允許客戶端提供任何 payStatus

```java
@NotNull
private PayStatus payStatus;
```

**建議 Phase 3.1 時修改**：

```java
// ✅ Phase 3.1：改為有預設值，忽略客戶端的值
@NotNull
@Default(PayStatus.PENDING)  // 或用其他註解
private PayStatus payStatus;

// 後端強制：
ShopOrder order = new ShopOrder();
order.setPayStatus(PayStatus.PENDING);  // 忽略 request 的值
```

或更激進的做法：

```java
// ✅ 後端直接忽略客戶端的 payStatus
public String createOrder(CreateOrderRequest request) {
    request.setPayStatus(PayStatus.PENDING);  // 強制改回
    return transactionService.createOrder(request);
}
```

---

## Phase 3.1 時的下一步

當金流系統完成後：

```javascript
// ✅ Phase 3.1：改為支付按鈕
<div class="field">
  <label>訂單狀態</label>
  <span class="badge badge--info">待支付</span>
  <button type="button" class="btn btn--secondary">
    前往 ECPay 支付 →
  </button>
</div>
```

支付商會回調更新 payStatus：

```
PENDING (訂單建立)
  ↓
[用戶在 ECPay 輸入卡號]
  ↓
PAID (支付商回調驗簽後)
```

---

## 檢查清單

### Phase 3.0 立即做

- [ ] 移除 CartPanel 的 payStatus 單選鈕
- [ ] 改為固定顯示「待支付」
- [ ] `npm test` 全過
- [ ] 前端手動測試：結帳頁面不再有單選鈕

### Phase 3.1 做金流時

- [ ] 後端強制 payStatus = PENDING（忽略客戶端值）
- [ ] 支付商回調時更新 payStatus = PAID
- [ ] 訂單查詢頁面顯示 payStatus
- [ ] 修改不完成，可先留言「Phase 3.1 補」

---

## 預期效果

| 項目 | 修改前 | 修改後 |
|------|--------|--------|
| 用戶能否騙系統已付款 | ❌ 能（選「已付款」） | ✅ 不能（固定「待支付」） |
| payStatus 值來源 | ❌ 客戶端自己決定 | ✅ 系統自動設（Phase 3.1 由金流決定） |
| 前端 UI | ❌ 混淆（兩個選項） | ✅ 清晰（唯讀狀態） |
| 業務邏輯 | ❌ 破裂 | ✅ 正常（Phase 3.1 起） |
