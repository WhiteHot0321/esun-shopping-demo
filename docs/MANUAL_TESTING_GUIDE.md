# 玉山購物車系統 — 手動測試使用說明書

**更新日期**：2026-09-17  
**適用版本**：Phase 2.1（含 LLM 商品客服、JWT 認證、訂單冪等）

---

## 📋 快速開始

本說明書涵蓋三種測試方式：
1. **前端 UI 測試** — 在瀏覽器中完整操作
2. **API 直接測試** — 使用 curl 或 Postman 驗證後端
3. **性能/壓力測試** — 使用 k6 腳本驗證並發與庫存一致性
4. **備份與恢復演練** — 用隔離暫存資料庫證明 MySQL dump 可完整還原

### 環境需求

| 工具 | 版本 | 安裝方式 |
|------|------|---------|
| Docker Desktop | 最新 | https://www.docker.com/products/docker-desktop |
| Node.js | 18+ | https://nodejs.org/ |
| Maven | 3.8+ | 含在 backend/ 中（或 `brew install maven`） |
| MySQL CLI（可選） | 8.0+ | `brew install mysql-client` 或隨 Docker 啟動 |
| curl / Postman（可選） | 最新 | 預裝在大多數 OS；或安裝 Postman |

### 預設連接埠

| 服務 | 預設連接埠 | URL |
|------|-----------|-----|
| 前端（Vite dev server） | 5173 | http://localhost:5173 |
| 後端 API | 8080 | http://localhost:8080 |
| MySQL | 3306 | localhost:3306 |
| Ollama LLM | 11434 | http://localhost:11434 |

---

## 🚀 第一步：環境設定

### 1.1 設定環境變數

在專案根目錄建立 `.env` 檔案（或複製範本）：

```bash
cp .env.example .env
```

檔案內容應為：

```
DB_NAME=esun_shop
DB_PASSWORD=123456
DB_PORT=3306
DB_HOST=mysql
DB_USERNAME=root
SERVER_PORT=8080
OLLAMA_BASE_URL=http://ollama:11434
LLM_PROVIDER=ollama
```

**說明**：
- `DB_HOST=mysql` 時，透過 Docker 容器名稱連接；本機連接改為 `localhost`
- `LLM_PROVIDER=ollama` 為預設；若無 Ollama，改為 `LLM_PROVIDER=none`（客服功能將禁用）

### 1.2 檢查 Docker 環境

```bash
docker --version
docker compose --version
```

確認兩者都已安裝。

---

## 🐳 第二步：啟動 Docker Compose

### 2.1 一鍵啟動所有服務（推薦）

```bash
cd C:\GitHub\esun-shopping
docker compose up -d
```

該命令會依序啟動：
1. **MySQL** — 執行初始化腳本（01_schema.sql → 02_data.sql → 03_stored_procedures.sql → 04_add_order_request.sql）
2. **Ollama** — 拉取並準備 LLM 模型（可選；若失敗，客服功能降級但不影響訂單）

### 2.2 驗證容器狀態

```bash
docker compose ps
```

應看到類似輸出：

```
NAME         IMAGE              STATUS
mysql        mysql:8.0          Up 2 minutes (healthy)
ollama       ollama/ollama      Up 1 minute
```

### 2.3 檢查 MySQL 初始化

```bash
docker compose exec mysql mysql -uroot -p123456 esun_shop -e "SHOW TABLES;"
```

應輸出：

```
Tables_in_esun_shop
doc_embedding
faq
order_details
order_request
orders
products
```

若缺少任何表，可手動執行初始化（見 **疑難排解** 章節）。

---

## 🔧 第三步：啟動後端

### 3.1 方式一：Maven（推薦；開發用）

```bash
cd backend
mvn clean spring-boot:run
```

期望輸出結尾為：

```
Started ShoppingBackendApplication in X.XXX seconds
```

### 3.2 方式二：Maven 打包後執行（生產用）

```bash
cd backend
mvn clean package
java -jar target/shopping-backend-1.0.0.jar
```

### 3.3 驗證後端啟動

在**另一個終端**執行：

```bash
curl -s http://localhost:8080/api/products/available | jq .
```

期望回應：

```json
{
  "success": true,
  "data": [
    {
      "productId": "P001",
      "productName": "iPhone 15",
      "price": 29990,
      "quantity": 50
    },
    ...
  ]
}
```

若無法連接，檢查：
- 防火牆是否開放 8080 埠
- MySQL 是否正常執行（見 2.3）

---

## 💻 第四步：啟動前端

### 4.1 開發伺服器（推薦；即時重編譯）

```bash
cd frontend
npm install      # 首次執行；之後可省略
npm run dev
```

期望輸出：

```
  ➜  Local:   http://localhost:5173/
  ➜  press h + enter to show help
```

### 4.2 測試與打包

```bash
npm test          # 執行單元測試（Vitest）
npm run build     # 編譯成靜態資源
npm run preview   # 預覽編譯結果
```

### 4.3 在瀏覽器中開啟

打開瀏覽器進入：

```
http://localhost:5173
```

應看到玉山購物車主畫面。

---

## 🧪 第五步：前端 UI 測試場景

### 場景 1：查詢商品列表 ✅

**操作步驟**：
1. 開啟首頁（http://localhost:5173）
2. 觀察「可購買商品」表格，應顯示 6 種商品：
   - iPhone 15 (P001) — 50 件
   - MacBook Pro (P002) — 30 件
   - AirPods Pro (P003) — 100 件
   - 藍牙耳機 (P004) — 10 件
   - 無線充電器 (P005) — 25 件
   - 真愛密碼項鍊 (P006) — 15 件

**預期結果**：
- 所有商品均顯示
- 價格、庫存欄位完整
- 無錯誤訊息

---

### 場景 2：註冊與登入 🔐

**註冊新使用者**：
1. 點擊「註冊」頁籤
2. 輸入：
   - Email: `test.user@example.com`
   - 密碼: `Test@12345`
   - 確認密碼: `Test@12345`
3. 點擊「註冊」按鈕

**預期結果**：
- 成功訊息：「註冊成功，請登入」
- 自動導向登入頁面

**登入**：
1. 點擊「登入」頁籤
2. 輸入上述註冊帳號密碼
3. 點擊「登入」按鈕

**預期結果**：
- 成功訊息：「登入成功」
- 導向首頁，導覽列顯示「登出」按鈕
- 瀏覽器開發者工具 (F12) → Storage → localStorage 應見 `token` 欄位

**邊界案例**：
- 登入密碼錯誤 → 應顯示錯誤訊息，清空密碼欄位
- 帳號不存在 → 應顯示「帳號或密碼不正確」
- 網路中斷後登入 → 應顯示「連線失敗，請檢查網路」

---

### 場景 3：建立單商品訂單 🛒

**前提條件**：已登入

**操作步驟**：
1. 在「可購買商品」表格中找到 iPhone 15 (P001)
2. 點擊該列的「加入購物車」按鈕
3. 購物車欄應顯示「1 件」
4. 點擊「檢視購物車」按鈕
5. 確認購物車內容：
   - 商品：iPhone 15
   - 數量：1（可調整為 2）
   - 小計：29,990 元
   - 總計：29,990 元
6. 點擊「結帳」按鈕
7. 確認訂單頁面顯示正確金額
8. 點擊「確認下單」按鈕

**預期結果**：
- 訂單建立成功，顯示訂單編號（格式 `Ms20260917...`）
- 訂單詳情頁面顯示：
  - 訂單編號、建立時間、會員編號
  - 商品列表（iPhone 15 × 1）
  - 訂單狀態：「已建立」
- 返回首頁後，iPhone 15 的庫存應從 50 降至 49

**庫存驗證**：
1. 刷新首頁（F5）
2. 查看 iPhone 15 (P001) 的庫存，應為 49

---

### 場景 4：建立多商品訂單 🛍️

**前提條件**：已登入，購物車為空

**操作步驟**：
1. 依次加入 3 種商品（數量各不同）：
   - iPhone 15 (P001) — 2 件
   - MacBook Pro (P002) — 1 件
   - AirPods Pro (P003) — 3 件
2. 檢視購物車，驗證小計計算：
   - iPhone 15: 29,990 × 2 = 59,980
   - MacBook Pro: 79,990 × 1 = 79,990
   - AirPods Pro: 8,990 × 3 = 26,970
   - **總計: 166,940**
3. 點擊「結帳」 → 「確認下單」

**預期結果**：
- 訂單建立成功
- 返回首頁後，三種商品庫存分別減少 2、1、3 件

---

### 場景 5：庫存不足 ❌

**操作步驟**：
1. 查看當前 iPhone 15 (P001) 的庫存（應為 48）
2. 在購物車中嘗試加入 iPhone 15 共 50 件
3. 點擊「結帳」 → 「確認下單」

**預期結果**：
- 下單失敗，顯示 HTTP 409 錯誤：「庫存不足」
- 訂單未建立，庫存未扣減
- 購物車商品仍存在，可重新調整數量

---

### 場景 6：重複下單（冪等性）📋

**說明**：同一筆訂單重複結帳時，系統應自動識別並回傳原訂單，不重複扣庫存。

**操作步驟**：
1. 建立一筆訂單（例如 iPhone 15 × 1），取得訂單編號 `Order_001`
2. 在瀏覽器開發者工具中，模擬網路延遲：
   - F12 → Network → Throttling 設為「Slow 3G」
3. 再次點擊「確認下單」（在網路回應前快速點擊）
4. 觀察結果

**預期結果**：
- 系統識別重複 request ID，回傳原訂單 `Order_001`
- 庫存**不會再次扣減**（仍為原數值）

**驗證方法**：
- 訂單詳情頁應顯示完全相同的訂單編號與金額
- 首頁庫存未再變化

---

### 場景 7：商品 AI 客服 🤖

**前提條件**：
- Ollama 容器正常執行（docker ps 中有 ollama）
- 模型已下載（llama3.1, nomic-embed-text）

**檢查 Ollama 狀態**：
```bash
curl -s http://localhost:11434/api/tags
```

應輸出包含 `llama3.1` 與 `nomic-embed-text`。

**操作步驟**：
1. 在頁面上方點擊「商品客服」頁籤（或透過側邊欄導航）
2. 在「提問」欄位輸入問題，例如：
   - `有沒有防水的商品？`
   - `推薦什麼禮物給女友？`
   - `有什麼可以保護手機的商品嗎？`
3. 點擊「提交」按鈕
4. 等待 2-5 秒（Ollama 處理時間）

**預期結果**：
- 顯示 AI 回答（中文）
- 下方顯示引用來源，例如：
  - 「真愛密碼項鍊 (P006)」— 相似度 0.92
  - 「AirPods Pro (P003)」— 相似度 0.87
- 可點擊引用跳轉到商品

**邊界案例**：
- 空白問題 → 顯示「問題不能為空」
- 超過 500 字問題 → 顯示「問題過長」
- Ollama 未啟動 → 顯示「服務暫時無法使用 (503)」

---

## 🔌 第六步：API 直接測試（curl 範例）

### 6.1 獲取商品列表

```bash
curl -s http://localhost:8080/api/products/available | jq .
```

### 6.2 使用者註冊

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@test.com",
    "password": "Test@12345"
  }' | jq .
```

期望回應：

```json
{
  "success": true,
  "data": {
    "memberId": "1001",
    "email": "newuser@test.com"
  }
}
```

### 6.3 使用者登入

```bash
RESPONSE=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@test.com",
    "password": "Test@12345"
  }')

echo $RESPONSE | jq .

# 提取 token（Linux/Mac）
TOKEN=$(echo $RESPONSE | jq -r '.data.token')
echo "Token: $TOKEN"
```

### 6.4 建立訂單

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "memberId": "1001",
    "items": [
      {"productId": "P001", "quantity": 1},
      {"productId": "P002", "quantity": 2}
    ]
  }' | jq .
```

期望回應：

```json
{
  "success": true,
  "data": {
    "orderId": "Ms20260917...",
    "totalAmount": 189970,
    "status": "CREATED"
  }
}
```

### 6.5 查詢訂單

```bash
curl -s http://localhost:8080/api/orders/Ms20260917... \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### 6.6 商品客服 API

```bash
curl -X POST http://localhost:8080/api/support/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "有沒有防水的商品？"
  }' | jq .
```

期望回應：

```json
{
  "success": true,
  "data": {
    "answer": "根據目前商品資料，防水商品有...",
    "sources": [
      {
        "sourceType": "product",
        "sourceId": "P006",
        "title": "真愛密碼項鍊",
        "similarity": 0.92
      }
    ]
  }
}
```

---

## 📊 第七步：性能測試（k6 腳本）

### 7.1 安裝 k6

**macOS**：
```bash
brew install k6
```

**Windows**：
```powershell
choco install k6
```

或從 https://k6.io/docs/get-started/installation/ 下載安裝。

### 7.2 執行基礎負載測試

專案已內建 k6 測試腳本，位於 `bench/phase25.js`。執行：

```bash
cd bench
k6 run --vus 20 --duration 30s phase25.js
```

**參數說明**：
- `--vus 20` — 20 個虛擬使用者（並發度）
- `--duration 30s` — 執行 30 秒

**預期輸出**：

```
execution: local
...
checks
  ✓ 95%+ orders succeed     [✓] 1234 (✗ 0)
  ✓ no stock underflow      [✓] true
duration: 30.2s
...
```

### 7.3 模擬庫存耗盡場景

編輯 `bench/phase25.js`，修改 stock 初始值為較小值（如 100），重新執行，觀察系統如何在庫存不足時退回 HTTP 409。

---

## 📝 第八步：重置測試資料

### 8.1 保留容器，重置資料

```bash
docker compose exec mysql mysql -uroot -p123456 esun_shop < backend/DB/01_schema.sql
docker compose exec mysql mysql -uroot -p123456 esun_shop < backend/DB/02_data.sql
docker compose exec mysql mysql -uroot -p123456 esun_shop < backend/DB/03_stored_procedures.sql
docker compose exec mysql mysql -uroot -p123456 esun_shop < backend/DB/04_add_order_request.sql
```

### 8.2 完全清除容器並重新初始化

```bash
docker compose down -v   # 刪除容器與 volume
docker compose up -d     # 重新啟動（自動初始化）
```

---

## 💾 第九步：備份與恢復演練

使用 `scripts/mysql-backup-restore.ps1`（Windows PowerShell）驗證「備份檔真的可以完整還原」，而不只是
「檔案被寫出來」。三種用法：

### 9.1 產生一次備份

```powershell
.\scripts\mysql-backup-restore.ps1 -Action Backup
```

預期輸出：

```
BACKUP_OK file=C:\...\backups\esun_shop-<timestamp>.sql sha256=<64碼十六進位雜湊>
```

備份檔存放在 `backups/`（已加入 `.gitignore`，因含真實會員/訂單資料，不可提交）。

### 9.2 還原到獨立資料庫（不覆寫來源）

```powershell
.\scripts\mysql-backup-restore.ps1 -Action Restore -TargetDatabase esun_restore_check `
  -BackupFile "backups\esun_shop-<timestamp>.sql" -Force
```

**安全防護**（皆為刻意設計、預期失敗，不會更動任何資料）：
- 少了 `-Force` → 直接報錯拒絕執行（Restore 會重建目標 DB，必須明確確認）。
- `-TargetDatabase` 等於來源資料庫（預設 `esun_shop`）且沒加 `-AllowSourceOverwrite` → 直接報錯拒絕，
  避免不小心覆蓋正在使用的來源資料。

還原完成後可用 `SHOW TABLES;` 確認表數（應為 8 張：doc_embedding, faq, member, order_detail,
order_request, payment_transaction, product, shop_order），驗證完記得手動
`DROP DATABASE esun_restore_check;` 清理。

### 9.3 完整演練（Drill）——一次跑完「備份 → 還原 → 模擬損毀 → 重建還原 → 驗證 → 清理」

```powershell
.\scripts\mysql-backup-restore.ps1 -Action Drill
```

**預期輸出**：

```
DRILL_OK source=esun_shop temporary=esun_restore_drill_<timestamp>
  data_sha256=<資料內容雜湊> full_sha256=<完整結構雜湊> tables=<資料表清單>
  evidence=backups\drill-<timestamp>\
```

這個動作全程只操作一個暫存資料庫（`esun_restore_drill_<timestamp>`），`finally` 區塊保證暫存資料庫
一定會被刪除、來源 `esun_shop` 全程不會被寫入。若中途任何一步失敗（例如還原後資料或資料表清單和來源
對不上），指令會以非 0 結束碼終止並印出差異，不會留下半途而廢的暫存資料庫。

**疑難排解**：若看到 `esun_shop: command not found` 或密碼相關的 stderr 警告被當成錯誤中止，代表使用
的是修正前的舊版腳本；目前版本已改用 `MYSQL_PWD` 環境變數傳密碼、SQL 一律透過暫存檔匯入，兩者皆已修
正（詳見 `docs/tasks/024-phase30-backup-restore-result.md`）。

---

## ⚠️ 疑難排解

### 問題 1：MySQL 無法連接

**症狀**：後端啟動時顯示 `Connection refused` 或 `Public Key Retrieval is not allowed`

**解決步驟**：
1. 確認 MySQL 容器運行：
   ```bash
   docker compose ps mysql
   ```
2. 檢查日誌：
   ```bash
   docker compose logs mysql
   ```
3. 若容器崩潰，重啟：
   ```bash
   docker compose up -d mysql
   ```

### 問題 2：Ollama 模型未下載

**症狀**：客服功能顯示「服務暫時無法使用 (503)」

**解決步驟**：
1. 檢查 Ollama 容器：
   ```bash
   docker compose ps ollama
   ```
2. 手動下載模型：
   ```bash
   docker exec esun-ollama ollama pull llama3.1
   docker exec esun-ollama ollama pull nomic-embed-text
   ```
3. 驗證模型：
   ```bash
   curl -s http://localhost:11434/api/tags | jq .
   ```

### 問題 3：前端無法連接後端

**症狀**：瀏覽器 F12 控制台顯示 CORS 或 404 錯誤

**解決步驟**：
1. 檢查後端是否運行：
   ```bash
   curl -s http://localhost:8080/api/products/available
   ```
2. 檢查防火牆：確保 8080 埠未被阻擋
3. 重新啟動後端服務

### 問題 4：訂單無法建立（HTTP 500）

**症狀**：下單時顯示「伺服器錯誤」

**解決步驟**：
1. 檢查後端日誌：
   ```bash
   # 若用 mvn spring-boot:run，在該終端查看日誌
   # 若用 Docker，執行：
   docker compose logs backend --tail 50
   ```
2. 常見原因：
   - 庫存不足（應為 409，不是 500）
   - 資料庫連接問題
   - 無效的 JWT token（若已登入失敗）
3. 重置資料後重試（見第 8 步）

### 問題 5：JWT token 過期

**症狀**：登入後片刻收到 401 「Unauthorized」

**解決步驟**：
- Token 預設有效期 24 小時
- 重新登入取得新 token
- 檢查伺服器時間是否同步

---

## 🎯 測試檢查清單

在完成所有測試後，勾選以下項目：

- [ ] **後端啟動**：`mvn clean spring-boot:run` 無誤
- [ ] **前端啟動**：`npm run dev` 成功，http://localhost:5173 可訪問
- [ ] **商品查詢**：首頁顯示 6 種商品及正確庫存
- [ ] **使用者認證**：註冊、登入、登出正常
- [ ] **單商品訂單**：建立訂單成功，庫存正確扣減
- [ ] **多商品訂單**：建立多品項訂單，小計與總計計算正確
- [ ] **庫存不足**：下單時庫存不足返回 409 錯誤
- [ ] **冪等性**：重複下單返回原訂單，庫存不重複扣減
- [ ] **AI 客服**：提問並接收回答與引用來源（若 Ollama 啟用）
- [ ] **API 直接測試**：curl 測試各端點返回正確狀態碼
- [ ] **性能測試**：k6 運行 20 虛擬使用者，成功率 ≥95%
- [ ] **資料重置**：執行重置腳本後，資料恢復至初始狀態
- [ ] **備份與恢復演練**：`mysql-backup-restore.ps1 -Action Drill` 回傳 `DRILL_OK`，暫存資料庫已清理，來源資料庫未被更動

---

## 📞 更多幫助

- **專案文檔**：`docs/` 資料夾
- **任務記錄**：`docs/tasks/` 記錄了各階段的實作細節
- **API 詳情**：見 README.md 的 API 章節
- **技術深潛**：見 Notion 的「Spring Boot MVC 購物車專案」頁面

**祝您測試愉快！** 🚀
