# Phase 2.1 LLM 商品客服問答 — 完整提示詞導覽

**Branch**：`phase-2-1-llm-support-bot`  
**日期**：2026-09-10

---

## 📚 5 個頁面的組織結構

### 1️⃣ 📋 **概述與架構決策**

**核心內容**：
- ✅ Phase 2.1 的核心目標
- ✅ 為什麼選 Ollama + RAG + 拆成三個子 phase
- ✅ 保護層（不可修改的既有介面）
- ✅ 已知問題要迴避的反模式

**適用場景**：
- 新加入開發者快速理解全景
- 需要確認邊界條件時的參考
- 決策理由的溯源

**關鍵決策點**：
```
LLM 供應商：Ollama（零成本）+ Claude API 預留
RAG 檢索：MySQL + 向量化 + 記憶體 cosine
三層拆分：只做商品客服（低風險），留訂單查詢與引導下單給後續
```

---

### 2️⃣ 🔧 **後端實作 — Provider 抽象**

**核心內容**：
- ✅ `LlmClient` 介面定義（chat、embed 兩個方法）
- ✅ `OllamaLlmClient` 實作（呼叫 `/api/chat`、`/api/embeddings`）
- ✅ `ClaudeLlmClient` stub（丟 `UnsupportedOperationException`，日後補）
- ✅ `@ConditionalOnProperty` 自動注入切換機制

**適用場景**：
- 後端工程師實作 LLM 層時的參考
- Provider 切換邏輯的實現方案

**實作要點**：
```
┌─────────────────┐
│  SupportService │ ← 只呼叫 LlmClient 介面
└────────┬────────┘
         │
    ┌────▼────────────────────┐
    │  LlmClient (interface)   │
    └────┬────────┬────────────┘
         │        │
    ┌────▼──┐  ┌──▼──────────┐
    │Ollama │  │ Claude (stub)│
    └───────┘  └──────────────┘
```

**組態驅動**：只改 `llm.provider: ollama` → 自動切換，程式碼零改動。

---

### 3️⃣ 🔍 **後端實作 — RAG 與檢索**

**核心內容**：
- ✅ `04_faq.sql` DB 結構（faq 表 + doc_embedding 表）
- ✅ `EmbeddingIndexRunner` 啟動時自動初始化 embedding
- ✅ `VectorSearchService` 向量搜索與 cosine 相似度計算
- ✅ 上下文組合策略（如何把檢索結果組成 LLM 的系統提示詞）

**適用場景**：
- 實現向量化與檢索層
- DB 結構設計與驗收
- 相似度演算法調整

**實作流程**：
```
啟動 → EmbeddingIndexRunner 掃 product & faq
       ↓
       為每筆缺 embedding 的資料呼叫 LLM
       ↓
       儲存到 doc_embedding 表
       ↓
查詢 → VectorSearchService 將 query 向量化
       ↓
       計算與所有 embedding 的 cosine similarity
       ↓
       排序、回傳 top-k 結果 + 原始文本
```

**Embedding 儲存格式**：
```sql
doc_embedding {
  source_type: 'product' | 'faq',
  source_id: <ID>,
  content: "原文本（用於展示給 LLM）",
  embedding: [0.12, -0.34, ...]  -- JSON 陣列
}
```

---

### 4️⃣ 🎯 **API 與前端實現**

**核心內容**：
- ✅ `SupportService.answer(question)` 業務邏輯
- ✅ `SupportController` 端點（`POST /api/support/ask`）
- ✅ HTTP 狀態碼正確性（400 Bad Request / 503 Service Unavailable / 501 Not Implemented）
- ✅ 前端 `SupportChat.vue` 聊天面板元件

**適用場景**：
- API 簽約與前後端整合
- 錯誤處理邏輯
- UI 實現參考

**API 流程**：
```
前端 POST /api/support/ask { question: "..." }
       ↓
SupportService.answer()
  ├─ 驗證 question 不空、不超過 500 字 → 否則 400
  ├─ VectorSearchService.search() → top-k 相關文件
  ├─ 組合 systemPrompt（限制 LLM 只用提供的資料）
  ├─ LlmClient.chat() → 呼叫 Ollama/Claude
  │  └─ Ollama 掛掉? → 503
  │  └─ Claude 未實作? → 501
  └─ 回傳 { answer: "...", sources: [...] }
```

**HTTP 狀態碼對應**：
```
200 OK          → 成功取得回答
400 Bad Request → question 無效
503 Unavailable → Ollama 無法連線或超時
501 Not Impl.   → Claude provider 尚未實作
```

**前端聊天面板**：
- 輸入框 + 送出按鈕
- 回答顯示區
- 來源清單（顯示相關商品/FAQ + 相似度分數）
- Loading / Error 狀態
- 防連點（loading 時按鍵禁用）

---

### 5️⃣ ✅ **配置、部署與驗收**

**核心內容**：
- ✅ `application.yml` 完整設定（Ollama 連線、timeout、rag.top-k 等）
- ✅ `docker-compose.yml` 新增 Ollama service
- ✅ Ollama 模型拉取與測試指令
- ✅ 完整驗收清單（單元測試、整合測試、功能驗收、文件檢查）

**適用場景**：
- 環境部署與組態驗證
- CI/CD 流程集成
- 上線前完整性檢查

**部署清單**：
```
1. 組態層
   ├─ application.yml: llm.provider / ollama.base-url / timeout
   ├─ docker-compose.yml: ollama service
   └─ 環境變數: ANTHROPIC_API_KEY (optional)

2. 模型層
   ├─ ollama pull llama3.1
   ├─ ollama pull nomic-embed-text
   └─ 驗證: curl http://localhost:11434/api/tags

3. 驗收層
   ├─ mvn test / pytest
   ├─ 功能測試: POST /api/support/ask
   ├─ 狀態碼驗證: 400 / 503 / 501
   └─ 既有功能迴歸: GET /api/products / OrderController 不變
```

**驗收項目**（完整清單見頁面）：
```
後端邏輯（mvn clean test）
  ✓ 新 Service/Controller 單元測試
  ✓ 既有功能迴歸測試

功能驗收（手動 + 工具）
  ✓ POST /api/support/ask 成功 200
  ✓ question 空值 → 400
  ✓ Ollama 斷線 → 503
  ✓ 首次啟動自動初始化 embedding
  ✓ 商品清單 / 購物車 / 下單邏輯完全不變

文件檢查
  ✓ README.md 有「LLM 商品客服」章節
  ✓ application.yml 新增 llm.* 區塊
  ✓ docker-compose.yml 與 application.yml 連線設定一致
  ✓ 新增的前端 import 都走 frontend/src/api/client.js
```

---

## 🎯 快速開發流程

### 第一天：架構確認
```
1. 讀 📋 頁面理解全景
2. 確認 🔧 + 🔍 的實作方向與 PM/Team lead
3. 檢查 4️⃣ 的 API 簽約是否符合前端期望
```

### 第二天：後端實作
```
1. 實作 🔧 (Provider 層) — 2-3h
   ├─ LlmClient 介面
   ├─ OllamaLlmClient 呼叫 HTTP
   └─ ClaudeLlmClient stub

2. 實作 🔍 (RAG 層) — 3-4h
   ├─ DB 表 & repository
   ├─ EmbeddingIndexRunner
   └─ VectorSearchService

3. 實作 4️⃣ (API 層) — 2-3h
   ├─ SupportService
   ├─ SupportController
   └─ GlobalExceptionHandler 補充
```

### 第三天：整合與部署
```
1. 實作前端聊天面板 (SupportChat.vue) — 1-2h
2. 更新 5️⃣ 的所有組態 — 30 min
3. 跑完整驗收清單 — 1-2h
4. commit + push + PR — 30 min
```

### 第四天：Review 與修正
```
1. 跑 `/code-review high`
2. 修正 reviewer 意見
3. 最終驗收 + merge
```

---

## 📍 檔案對應表

| 頁面 | 主要檔案 | 修改/新增 |
| --- | --- | --- |
| 1️⃣ 概述 | 無 | 無（參考用） |
| 2️⃣ Provider | `com.esun.shop.llm.*` | 新增 5 個類別 |
| 3️⃣ RAG | `backend/DB/04_faq.sql`, `com.esun.shop.llm.*` | 新增 2 個表、3 個類別 |
| 4️⃣ API | `com.esun.shop.service/controller/*`, `frontend/src/` | 新增 3 個類別、1 個元件、1 個 JS |
| 5️⃣ 部署 | `application.yml`, `docker-compose.yml`, `README.md` | 新增組態區塊、service |

---

## 🚀 用法

### 給 Sonnet 開發的提示詞

直接複製 [phase-2-1-llm-support-bot.md](phase-2-1-llm-support-bot.md) 末尾的「**給開發 session 的提示詞**」段落，貼給 Sonnet。

### 給 Opus Review 的檢查清單

- ✅ Provider 層是否真的支援 Ollama + Claude 切換？
- ✅ RAG 的 cosine 相似度計算是否正確？
- ✅ HTTP 狀態碼是否嚴格遵循 400/503/501？
- ✅ 是否有迴避已知問題 #2 #3 #4 #6？
- ✅ 既有 API 格式是否真的未變？

---

## 📞 常見問題

**Q：為什麼拆成三個 phase？**  
A：功能獨立性、風險隔離。商品客服低風險，訂單查詢涉 N+1，引導下單涉併發死鎖。

**Q：為什麼選 Ollama 不選 Claude API？**  
A：Demo 環節零成本、可離線。日後生產用 Claude 時只改一行設定。

**Q：Embedding 要手動初始化嗎？**  
A：不用。`EmbeddingIndexRunner` 在服務啟動時自動掃 product 和 faq，補缺漏的 embedding。

**Q：如果 Ollama 斷線怎麼辦？**  
A：回 HTTP 503，前端會收到「客服暫時無法服務」訊息。不會影響既有功能。

**Q：Next phase (2-2、2-3) 怎麼銜接？**  
A：Phase 2-2（訂單查詢）也用同一套 LlmClient 層，但改成查 order 表而非 product。Phase 2-3（引導下單）等死鎖修好再做。

---

## 📋 檢查清單（上線前）

- [ ] 5 個 Notion 頁面內容全讀過
- [ ] phase-2-1-llm-support-bot.md 「給開發 session 的提示詞」複製給 Sonnet
- [ ] Sonnet 開發完，跑 `/code-review high`
- [ ] 修正 review 意見
- [ ] 驗收清單的所有項目打 ✓
- [ ] Commit + PR + Merge
- [ ] 更新 PROGRESS.md（新增 phase-2-1 完成時間）
