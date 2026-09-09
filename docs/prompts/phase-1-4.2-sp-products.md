# Phase 1 4.2：sp_get_available_products 決策

- **Branch**：`phase-1-4.2-sp-products`
- **概述**：解決 Stored Procedure 與 Repository 的死碼問題

## 目標

消除 `sp_get_available_products` 與相關 call 物件的死碼，使 ProductRepository 與實際邏輯一致。

## 背景

- `backend/DB/stored_procedures.sql` 定義了 `sp_get_available_products`
- `ProductRepository` 曾建立 `getAvailableProductsCall` 物件（SimpleJdbcCall）來呼叫此 SP
- 但實際的 `ProductService.getAvailableProducts()` 呼叫 `productRepository.getAvailableProducts()`，其實現是 `jdbcTemplate.query(...)` 直查，**根本不用 SP**
- 結果：SP 定義和 call 物件都成了死碼

## 不可修改的既有介面

- `ProductService.getAvailableProducts()` 的簽章與回傳型別不變
- `ProductController` 的 `GET /api/products` 行為與回傳格式不變
- `frontend/src/api/client.js` 不動

## 明確需求

**選擇以下其中一條路**：

### 選項 A：真正接上 Stored Procedure（推薦）

1. 在 `ProductRepository` 中，把 `getAvailableProductsCall` 物件啟用，正式改 `getAvailableProducts()` 實作為呼叫 `sp_get_available_products`
2. 測試呼叫是否正常，結果應與原 jdbcTemplate 直查一致
3. 確認 `backend/DB/stored_procedures.sql` 內 `sp_get_available_products` 的 SQL 邏輯正確（應為 `SELECT ... FROM product WHERE quantity > 0`）
4. 更新 README，明確說明「商品查詢使用 Stored Procedure」

### 選項 B：刪除 Stored Procedure 與死碼（快速方案）

1. 從 `backend/DB/stored_procedures.sql` 刪除 `sp_get_available_products` 定義
2. 從 `ProductRepository` 刪除所有 `getAvailableProductsCall` 相關程式碼
3. 保持 `getAvailableProducts()` 用 `jdbcTemplate.query()` 直查
4. 更新 README，移除對 SP 的任何引用
5. 若 README 內有「展示 SP 用法」的敘述，改為「目前商品查詢用 JDBC 直查」

## 驗收條件

### 選項 A 驗收
- [ ] `mvn -f backend/pom.xml clean test` 通過
- [ ] 前端呼叫 `GET /api/products` 能正常取回商品清單
- [ ] `ProductRepository.getAvailableProducts()` 內部確實呼叫了 `sp_get_available_products`
- [ ] README 記載「商品查詢使用 Stored Procedure `sp_get_available_products`」

### 選項 B 驗收
- [ ] `mvn -f backend/pom.maven clean test` 通過
- [ ] 前端呼叫 `GET /api/products` 能正常取回商品清單
- [ ] `ProductRepository` 無任何 `getAvailableProductsCall` 或 SimpleJdbcCall 相關程式碼
- [ ] `backend/DB/stored_procedures.sql` 內無 `sp_get_available_products` 定義
- [ ] README 無任何 SP 相關敘述（或明確標記為「暫未使用」）

## 不在範圍內

- 改變 API 行為或回傳格式
- 改變其他 Stored Procedure 的邏輯
- 改變前端

---

## 給開發 session 的提示詞

你是 esun-shopping 購物車專案的後端工程師。

**目標**：消除 `sp_get_available_products` 的死碼問題，二選一。

**現況**：
- `backend/DB/stored_procedures.sql` 定義了 `sp_get_available_products`
- `ProductRepository` 建了 `getAvailableProductsCall` 但沒被用
- 實際的 `getAvailableProducts()` 是 `jdbcTemplate.query(...)` 直查
- 結果：SP + call 物件都是死碼

**做法（選一）**：

**A. 真正接上 SP（推薦）**：
1. 在 `ProductRepository.getAvailableProducts()` 改用 `getAvailableProductsCall.execute()` 來呼叫 `sp_get_available_products`
2. 測試回傳結果一致
3. 更新 README，記載「商品查詢使用 Stored Procedure」

**B. 刪除 SP（快速）**：
1. 刪除 `backend/DB/stored_procedures.sql` 內的 `sp_get_available_products` 定義
2. 從 `ProductRepository` 刪 `getAvailableProductsCall` 及所有相關程式碼
3. 保持 `getAvailableProducts()` 用 `jdbcTemplate.query()` 直查
4. 更新 README，移除 SP 相關敘述

**驗收**：
- 前端呼叫 `GET /api/products` 能正常取回商品清單
- Repository 內無死碼（選 A 就得有 SP 呼叫，選 B 就得完全無 SP 相關程式碼）
- README 與實作一致

**建議**：選 A 可展示 Spring JDBC 與 SP 的整合，更符合題目「使用 Stored Procedure」的精神；選 B 則更簡潔。

---

## 參考

- `backend/DB/stored_procedures.sql`：SP 定義位置
- `backend/src/main/java/com/esun/shop/repository/ProductRepository.java`：Repository 位置
- `README.md`：文件說明位置
