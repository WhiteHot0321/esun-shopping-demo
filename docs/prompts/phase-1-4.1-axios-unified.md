# Phase 1 4.1：前端 Axios 統一

- **Branch**：`phase-1-4.1-axios-unified`
- **概述**：刪除死碼、統一 HTTP 客戶端

## 目標

統一前端所有 API 呼叫透過同一個 axios instance（`frontend/src/api/client.js`），刪除重複定義與死碼。

## 不可修改的既有介面

- `ProductController` 的所有端點路徑與回傳格式
- `OrderController` 的所有端點路徑與回傳格式
- 環境變數名稱：`VITE_API_BASE_URL`

## 明確需求

1. **刪除** `frontend/src/api.js`（目前死碼，無人引用）
2. **修改** `App.vue`：
   - 第 105、130、193 行的 `fetch()` 改為 axios 呼叫
   - 讀環境變數 `VITE_API_BASE_URL`（預設 `http://localhost:8080/api`）
3. **確認**所有 API 呼叫都經過 `frontend/src/api/client.js`（複查無 import axios 的其他地點）

## 不在範圍內

- 改變 API 回傳格式
- 改變 `App.vue` 的任何業務邏輯或 UI

## 驗收條件

- [ ] `npm run build` 通過
- [ ] `frontend/src/` 無其他地方 import axios（除 client.js 外）
- [ ] 前端啟動後，DevTools 可見所有 API 都來自同一個 axios instance
- [ ] 改環境變數 `VITE_API_BASE_URL=http://127.0.0.1:8080/api` 後，前端連接成功（若後端也在此埠）

---

## 給開發 session 的提示詞

你是 esun-shopping 購物車專案的前端工程師。

**目標**：統一前端 HTTP 客戶端，刪除死碼。

**現況**：`axios` 已在 `package.json` 但未被使用；`frontend/src/api.js` 是死碼；`App.vue` 三處用原生 `fetch()`（第 105、130、193 行）；API base URL 寫死字串，無環境變數支援。

**做法**：
1. 刪除 `frontend/src/api.js`
2. 修改 `App.vue`：
   - 在 `<script setup>` 段頂端 `import apiClient from '@/api/client'`
   - 將第 105 行的 `fetch(...)` 改為 `apiClient.get(...)` 或 `apiClient.post(...)`（視 HTTP 方法而定）
   - 重複改第 130、193 行
   - 移除所有 hardcoded base URL，改用 `import.meta.env.VITE_API_BASE_URL`（已在 `client.js` 內讀取）
3. 確認 `frontend/src/` 無其他檔案 import axios

**驗收**：
- `npm run build` 綠燈
- 前端起動後，DevTools 的 Network 面板可看到所有 API 呼叫一致的 base URL
- 改環境變數再測試一次，API 連線位址跟著變（若 backend 也改埠）
- Grep 確認無多個 `axios.create()` 或多個 axios import

**備註**：`frontend/src/api/client.js` 已存在且設定完成，不需修改。
