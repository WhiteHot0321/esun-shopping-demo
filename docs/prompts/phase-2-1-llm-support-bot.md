# Phase 2 2.1：LLM 商品客服問答（RAG + Ollama）

- **Branch**：`phase-2-1-llm-support-bot`
- **日期**：2026-09-10
- **前置 phase**：phase-1（既有電商功能整理）已完成

## 目標

在既有電商 demo 上新增一個**唯讀**的「商品客服問答」API：使用者用自然語言提問（例如
「有沒有防水的商品」「這個有現貨嗎」），後端以 RAG（對商品資料與 FAQ 做向量檢索）組出
上下文，交給 LLM 產生回答並附上來源。LLM 供應商先接本地 Ollama，`LlmClient` 介面設計成
日後可切換 Claude API。不碰下單、庫存與任何既有 Stored Procedure。

## 背景

- 這是 **scope 變更**：phase-1 的定位是「整理既有電商功能」，加 LLM 功能超出該範圍，
  因此另起 phase-2。需求討論結論見本專案根目錄對話紀錄——要在 demo 上展示
  「RAG + 向量檢索 + Provider 抽象」的 LLM 應用能力。
- 原始想法一次涵蓋三塊，但風險不均，**本 phase 只做第一塊**：

  | 子功能 | 讀寫 | 風險 | 排程 |
  | --- | --- | --- | --- |
  | 商品客服問答 | 唯讀 | 低（失敗只影響體驗） | **phase-2-1（本檔）** |
  | 訂單自然語言查詢 | 唯讀 | 中（NL→查詢參數；牽涉已知問題 #4 N+1） | phase-2-2 |
  | 引導式下單 | **寫入 Order/庫存/SP** | 高（已知問題 #6 併發死鎖未修） | 延後，待 #6 修好後另開 phase |

- 已知問題參照 [tools/README.md](../../tools/README.md)：本 phase 不修 #2/#3/#4，但**新程式碼
  不得重複這些反模式**（尤其 #2「錯誤一律 HTTP 200」）。

## 不可修改的既有介面

- `ProductController` 的 `GET /api/products`、`POST /api/products` 路徑與回傳格式
- `OrderController` 全部路徑、行為與回傳格式
- `backend/DB/stored_procedures.sql` 內所有 `sp_*` 的簽章與邏輯
- `frontend/src/api/client.js` 的匯出方式（單一 axios 實例）——新增的前端呼叫**必須**走
  這個實例，不得新建第二個 axios instance
- 既有資料表 `product` / `shop_order` / `order_detail` 的欄位
- DB init 腳本 `01_*` / `02_*` / `03_*` 內容不動；新增一律用 `04_` 前綴
- `docker-compose.yml` 既有 service（mysql / backend / frontend）行為不變，只允許**新增**
  service
- `application.yml` 既有區塊不改語意，只允許**新增** `llm.*` 區塊

## 明確需求

### 後端：Provider 抽象

1. 新增 package `com.esun.shop.llm`，定義介面 `LlmClient`：
   - `String chat(String systemPrompt, String userPrompt)`
   - `float[] embed(String text)`
2. `OllamaLlmClient implements LlmClient`，用 `RestClient`/`RestTemplate` 呼叫本地
   Ollama REST API（`/api/chat`、`/api/embeddings`）。讀設定：
   - `llm.provider`（`ollama` / `claude`，預設 `ollama`）
   - `llm.ollama.base-url`（預設 `http://localhost:11434`）
   - `llm.ollama.chat-model`（預設 `llama3.1`）
   - `llm.ollama.embed-model`（預設 `nomic-embed-text`）
3. `ClaudeLlmClient implements LlmClient`：**本 phase 只留可編譯的 stub**——建構子讀
   `llm.claude.*` 設定，`chat`/`embed` 直接丟 `UnsupportedOperationException("Claude
   provider 尚未實作，見 docs/prompts/phase-2-1-llm-support-bot.md")`。類別 Javadoc
   註明日後接法（`anthropic-sdk-java` 或直接 HTTP、model id `claude-sonnet-5` /
   `claude-haiku-4-5-20251001`、API key 走環境變數 `ANTHROPIC_API_KEY`）。
4. 用 `@ConditionalOnProperty(name = "llm.provider", havingValue = "...")` 或一個
   `LlmClientFactory` 依 `llm.provider` 注入對應實作。切換供應商只改設定、不改呼叫端。

### 後端：RAG 檢索（二選一）

**選項 A：嵌入存 MySQL + 記憶體暴力檢索（推薦）**

- 新增 `04_faq.sql`：
  - 建 `faq(id BIGINT PK AUTO_INCREMENT, question VARCHAR(500), answer TEXT, category VARCHAR(50))`
  - 插入 8～12 筆種子 FAQ（退換貨、運費、付款方式、缺貨補貨、發票…）
  - 建 `doc_embedding(id BIGINT PK AUTO_INCREMENT, source_type ENUM('product','faq'),
    source_id BIGINT, content TEXT, embedding JSON, updated_at DATETIME,
    UNIQUE KEY (source_type, source_id))`
- 新增 `EmbeddingIndexRunner implements ApplicationRunner`：啟動時掃 `product`
  （名稱＋描述）與 `faq`（question＋answer），對 `doc_embedding` 缺漏或 `updated_at`
  早於來源者呼叫 `LlmClient.embed()` 並 upsert。
- `VectorSearchService`：啟動後把 `doc_embedding` 全載入記憶體，查詢時對 query 向量與
  各筆做 cosine similarity，回傳 top-k。demo 資料量（數十～數百筆）足夠。
- 理由：維持 **MySQL-only**，不新增基礎設施，符合專案「刻意不用複數基礎設施」的取向。

**選項 B：獨立向量資料庫（Qdrant）**

- `docker-compose.yml` 新增 `qdrant` service，`application.yml` 增 `llm.vector.qdrant.*`
- 新增 client 呼叫 Qdrant REST API 做 upsert / search
- 較貼近正式產品，但多一個 service 與一份一致性維護成本，與現有極簡架構風格不一致。
- **若選 B**，`docker-compose.yml` 與 `application.yml` 的連線設定必須一致。

> 預設走 **選項 A**；除非 review 時認為要展示外部向量庫整合，否則不選 B。

### 後端：問答服務與 API

5. `SupportService.answer(String question)`：
   - `VectorSearchService` 取 top-k（`llm.rag.top-k`，預設 4）片段
   - 組 system prompt：限制「只根據以下提供的商品／FAQ 資料回答；資料不足就明說不知道，
     不要編造價格或庫存數字」
   - 呼叫 `LlmClient.chat()`
   - 回傳 `SupportAnswer { String answer; List<Source> sources; }`，`Source` 含
     `sourceType` / `sourceId` / `title`
6. `SupportController`：`POST /api/support/ask`，request body `{ "question": "..." }`，
   回 `ApiResponse<SupportAnswer>`。**HTTP 狀態碼要正確**（勿重蹈已知問題 #2）：
   - `question` 空白或超長 → `400`（用既有 `@Valid` + `GlobalExceptionHandler` 路徑）
   - Ollama 連不上／逾時 → `503`，body 給簡短訊息，**不可**回 200，也不可噴 stack trace
   - `llm.provider=claude` 時呼叫 → `503` 或 `501`，訊息帶「尚未實作」

### 前端

7. `frontend/src/api/support.js`：`export function askSupport(question)`，內部用
   `frontend/src/api/client.js` 匯出的 axios 實例，`POST /support/ask`。
8. 新增一個獨立的客服聊天面板元件（單輪即可，**不需**對話記憶）：輸入框 + 送出 +
   顯示 `answer` 與 `sources` 清單。載入中、錯誤（400 / 503）要有可辨識的 UI 狀態。
9. 不改 `App.vue` 既有的商品清單 / 購物車 / 下單邏輯；客服面板掛在獨立區塊或路由。

### 設定與文件

10. `application.yml` 新增 `llm:` 區塊，預設值對準本機 Ollama；`llm.rag.top-k` 預設 4。
11. `docker-compose.yml`：選項 A 下新增 `ollama` service（或在 README 說明本機自行
    `ollama serve` 並 `ollama pull llama3.1 nomic-embed-text`）。compose 與
    `application.yml` 的 base-url 要一致。
12. `README.md` 新增「LLM 商品客服」章節：
    - 啟動 Ollama、拉模型、首次啟動的 embedding 初始化流程
    - `POST /api/support/ask` 的請求／回應範例
    - **如何切換到 Claude API**（改 `llm.provider=claude`、設 `ANTHROPIC_API_KEY`、
      待實作項目）
    - 待辦：認證、rate limiting、token 成本上限、串流回應

## 不在這次範圍內（Out of scope）

- 訂單自然語言查詢（→ phase-2-2）
- 引導式下單、加入購物車自動化，以及任何寫入 `shop_order` / `order_detail` / 庫存或
  呼叫下單 SP 的行為（待已知問題 #6 死鎖修復後另開 phase）
- 對話記憶 / 多輪 context 管理
- 串流回應（SSE）——先同步回傳
- `ClaudeLlmClient` 的實際實作——本 phase 只留可切換的介面與 stub
- 修既有已知問題 #2 / #3 / #4——但新程式碼不得重複這些反模式
- 認證、rate limiting、成本上限——記入 README 待辦

## 驗收條件

- [ ] `mvn -f backend/pom.xml clean test` 通過
- [ ] `cd tools && pytest tests/unit` 通過
- [ ] `cd tools && pytest tests/integration` 通過（既有電商功能未被破壞）
- [ ] `python -m esun_ops bench --product <ID> --orders 50 --workers 20` 結果與改動前
      一致（證明未觸碰下單路徑）
- [ ] 啟動 Ollama 並拉好模型後，`POST /api/support/ask` body
      `{"question":"有沒有防水的商品？"}` → HTTP 200，`answer` 非空，`sources` 含相關商品
- [ ] `question` 為空字串 → HTTP 400（不是 200）
- [ ] Ollama 未啟動時呼叫 → HTTP 503（不是 200、不是 500 stack trace）
- [ ] 首次啟動後 `doc_embedding` 同時有 `product` 與 `faq` 兩種 `source_type` 的資料
- [ ] 前端客服面板能送出問題並顯示回答與來源；商品清單、購物車、下單流程行為不變
- [ ] `GET /api/products` 與 `OrderController` 所有回傳格式與改動前逐位元組一致
- [ ] 將 `llm.provider` 設為 `claude` 後服務能正常啟動，呼叫時回明確「尚未實作」錯誤
      （非 NPE、非啟動失敗）
- [ ] `README.md` 有「如何切換到 Claude API」段落
- [ ] `git grep -n "new axios" frontend/src` 只有 `client.js` 一處

---

## 給開發 session 的提示詞

你是 esun-shopping 電商 demo 的全端工程師。技術棧固定：Vue 3 + Vite 前端、Spring Boot
3.3.5 + **Spring JDBC（非 JPA）** 後端、MySQL 8（`127.0.0.1:3307`）。前端 HTTP 一律走
`frontend/src/api/client.js` 的單一 axios 實例。

**目標**：新增一個唯讀的「商品客服問答」功能。使用者用自然語言問商品／FAQ 問題，後端做
RAG（向量檢索）組上下文後交給 LLM 回答。LLM 供應商先接本地 Ollama，介面要能日後切
Claude API。**完全不碰下單、庫存、既有 SP。**

**做法**：

1. `com.esun.shop.llm.LlmClient` 介面：`chat(systemPrompt, userPrompt)`、`embed(text)`。
2. `OllamaLlmClient`：呼叫本機 Ollama（`/api/chat`、`/api/embeddings`），設定讀
   `llm.ollama.*`（base-url `http://localhost:11434`、chat-model `llama3.1`、embed-model
   `nomic-embed-text`）。
3. `ClaudeLlmClient`：只留可編譯 stub，方法丟 `UnsupportedOperationException`，Javadoc
   註明日後接法（model `claude-sonnet-5`、key 走 `ANTHROPIC_API_KEY`）。用
   `llm.provider`（預設 `ollama`）切換注入。
4. RAG 走**選項 A**：新增 `backend/DB/04_faq.sql` 建 `faq` 與 `doc_embedding` 表 + FAQ
   種子資料；`ApplicationRunner` 啟動時對 product＋faq 補 embedding 存 `doc_embedding`；
   `VectorSearchService` 載入記憶體做 cosine top-k（`llm.rag.top-k` 預設 4）。
5. `SupportService.answer(question)`：檢索 → 組限制性 system prompt（只依提供資料回答、
   不足就說不知道、不得編造價格庫存）→ 呼叫 `LlmClient.chat` → 回
   `SupportAnswer{answer, sources}`。
6. `SupportController`：`POST /api/support/ask`，回 `ApiResponse<SupportAnswer>`。
   **狀態碼要對**：空問題 400、Ollama 不可用 503、claude provider 501/503。**不要**
   重蹈「錯誤一律回 200」（已知問題 #2）。
7. 前端：`frontend/src/api/support.js`（走既有 axios 實例）+ 一個單輪聊天面板元件，顯示
   回答與來源、處理 loading／錯誤。不動 `App.vue` 既有商品／購物車／下單邏輯。
8. `application.yml` 加 `llm.*`；`docker-compose.yml` 加 `ollama` service（或 README 說明
   本機自跑）；README 新增「LLM 商品客服」章節，含「如何切換到 Claude API」。

**限制**：
- 不改 `ProductController` / `OrderController` 的任何路徑與回傳格式。
- 不改 `01_/02_/03_` init 腳本、不改既有表欄位、不新增第二個 axios 實例。
- 不做訂單 NL 查詢、不做引導式下單、不做對話記憶、不做串流。

**驗收**：見本檔「驗收條件」。關鍵三項——(a) `pytest tests/integration` 與 `bench`
與改動前一致（證明沒動下單）；(b) 空問題 400、Ollama 掛掉 503（不是 200）；(c)
`llm.provider=claude` 時能啟動、呼叫回「尚未實作」而非崩潰。

開發觸碰後端邏輯後，依 CLAUDE.md 流程跑 `tools/tests/` 與 `bench`，開 PR 前跑
`/code-review high`。

---

## 參考

- `backend/src/main/java/com/esun/shop/controller/`：既有 Controller
- `backend/src/main/java/com/esun/shop/dto/ApiResponse.java`：統一回傳包裝
- `backend/src/main/java/com/esun/shop/exception/GlobalExceptionHandler.java`：錯誤處理路徑
- `backend/DB/stored_procedures.sql`、`backend/DB/01_*`～`03_*`：DB 腳本與順序慣例
- `frontend/src/api/client.js`：唯一 axios 實例
- `docker-compose.yml`、`backend/src/main/resources/application.yml`：連線設定需一致
- [tools/README.md](../../tools/README.md)：已知問題（#2 HTTP 200、#4 N+1、#6 死鎖）
- Ollama REST API：`/api/chat`、`/api/embeddings`
