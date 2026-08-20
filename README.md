# ESUN Shopping Demo

本專案依照玉山銀行 Java 後端實作題需求，提供一個簡易電商購物中心系統，包含：

- 新增商品
- 顯示庫存大於 0 的商品
- 建立訂單
- 更新商品庫存
- 使用 Stored Procedure
- 使用 Transaction 確保下單一致性
- Vue.js 前後端整合

---

## 專案結構

```
esun-shopping/
├─ backend/
│  ├─ src/main/java/com/esun/shop/
│  ├─ src/main/resources/application.yml
│  ├─ DB/
│  │  ├─ 01_schema.sql
│  │  ├─ 02_data.sql
│  │  ├─ 03_stored_procedures.sql
│  │  └─ reset.sql
│  └─ pom.xml
├─ frontend/
│  ├─ src/
│  ├─ package.json
│  └─ vite.config.js
└─ README.md
```

---

## 技術棧

Frontend  
- Vue 3  
- Vite  

Backend  
- Spring Boot 3  
- Spring JDBC  
- Bean Validation  

Database  
- MySQL 8  
- Stored Procedure  

Build Tool  
- Maven  
- npm  

---

## 系統功能

### 商品功能
- 新增商品
- 查詢庫存大於 0 商品
- 庫存管理

### 訂單功能
- 多商品訂單
- 自動計算小計與總價
- 建立訂單
- 扣減庫存
- Transaction 保證一致性

---

## API

### 新增商品
POST /api/products

```json
{
  "productId": "P004",
  "productName": "藍牙耳機",
  "price": 1990,
  "quantity": 10
}
```

---

### 查詢可購買商品
GET /api/products/available

---

### 建立訂單
POST /api/orders

```json
{
  "memberId": "1001",
  "payStatus": "1",
  "items": [
    {
      "productId": "P002",
      "quantity": 2
    },
    {
      "productId": "P003",
      "quantity": 1
    }
  ]
}
```

---

## 本機執行方式

### 1. 建立資料庫

先安裝 MySQL，建立 schema：

```sql
CREATE DATABASE esun_shop;
```

依序執行：

- backend/DB/01_schema.sql
- backend/DB/02_data.sql
- backend/DB/03_stored_procedures.sql

---

### 2. 設定資料庫連線

資料庫連線設定（port、帳號、密碼）已改為讀取環境變數，`docker-compose.yml` 與 `application.yml` 共用同一份設定，避免兩邊設定不一致。

複製環境變數範本並依需要調整：

```bash
cp .env.example .env
```

`.env` 內容：

```
DB_NAME=esun_shop
DB_PASSWORD=123456
DB_PORT=3306
DB_HOST=localhost
DB_USERNAME=root
SERVER_PORT=8080
```

`.env` 已列入 `.gitignore`，不會被提交，可自行填入本機密碼。

若用 Docker 啟動 MySQL，`docker compose` 會自動讀取根目錄的 `.env`：

```bash
docker compose up -d
```

若後端不透過 Docker、直接用 `mvn` 或 IDE 啟動，需自行把 `.env` 內容匯出成環境變數（例如在 IDE 的 Run Configuration 設定環境變數，或執行前 `export $(cat .env | xargs)`），否則會套用 `application.yml` 中的預設值（對應 `.env.example` 的預設值）。

---

### 3. 啟動後端

```bash
cd backend
mvn clean package
java -jar target/shopping-backend-1.0.0.jar
```

後端預設：

http://localhost:8080

---

### 4. 啟動前端

```bash
cd frontend
npm install
npm run dev
```

前端預設：

http://localhost:5173

---

## 測試方式

可透過前端畫面測試以下功能：

- 查詢商品列表
- 新增商品
- 建立單商品訂單
- 建立多商品訂單
- 驗證庫存扣減
- 驗證庫存不足交易回滾

---

## Transaction 說明

建立訂單流程：

1. 建立訂單主檔
2. 建立訂單明細
3. 扣減商品庫存

若任一流程失敗，透過 `@Transactional` 自動 rollback，確保資料一致性。

---

## Stored Procedure

使用 Stored Procedure：

- sp_add_product
- sp_get_available_products
- sp_decrease_stock

---

## 安全性說明

SQL Injection  
- 使用 JdbcTemplate / SimpleJdbcCall  
- 所有查詢採參數化方式  
- 未使用動態 SQL 字串拼接  

XSS  
- 後端對商品名稱進行 HTML escape  
- 前端使用 Vue template rendering  
- 未使用 v-html 直接渲染輸入資料  

Transaction  
- 建立訂單使用 @Transactional  
- 避免多表異動資料不一致  

---

## 三層式架構

- Controller  
- Service  
- Repository  

分離業務邏輯與資料存取。

---

## 重置測試資料

若測試後資料被修改，可執行：

backend/DB/reset.sql

還原初始測試資料。

---

## 題目需求對應

- 使用 Vue.js：已完成
- 使用 Spring Boot：已完成
- RESTful API：已完成
- 使用 Maven：已完成
- Stored Procedure：已完成
- Transaction：已完成
- DDL / DML 放 DB 資料夾：已完成
- SQL Injection 防護：已完成
- XSS 防護：已完成
