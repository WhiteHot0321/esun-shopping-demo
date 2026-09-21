# Phase 3：工程深度主線 — 提示詞庫

**戰略背景**：停止商城功能堆疊。Phase 3 聚焦於面試價值最高的工程基礎：併發控制、原子性、可測性、自動化。每項任務按「A 定位」「B 實作」「C 驗收」三階段拆分，避免單一提示詞過載。

---

## 📋 Phase 3 執行清單

| 順序 | 任務 | 難度 | 預期天數 | 履歷價值 |
|------|------|------|---------|---------|
| 1 | **Deadlock 分析 & lock order 修復** | ⭐⭐⭐ | 2 | 並發控制深度 |
| 2 | **Idempotency 模式整合** | ⭐⭐ | 2 | 分佈式系統設計 |
| 3 | **Redis Lua + Testcontainers** | ⭐⭐⭐ | 3 | 原子性 & 整合測試 |
| 4 | **k6 壓測套件** | ⭐⭐ | 2 | 性能基準化 |
| 5 | **GitHub Actions CI/CD** | ⭐⭐ | 1 | 自動化成熟度 |

---

## 🔒 Task 1：Deadlock 分析 & lock order 修復

### A. 定位（研究）

**範圍**：識別當前 order checkout 流程中的潛在 deadlock，分析根本原因，設計 lock order 修復方案。

**提示詞模板**：
```
讀 OrderService.checkout() → StockCacheService.decrease() → MySQL sp_decrease_stock，識別何處持有哪些行鎖。
用 InnoDB LOCK MONITOR 記錄 Phase 2.5 的 10 件商品並發訂單場景中的所有 1213 deadlock。
分析：鎖序不一致嗎？列出所有可能的迴圈等待路徑（A wait B, B wait A）。
設計修復：如果鎖序不一致，提議按 productId ASC 固定順序加鎖。
文檔：原因、當前行為、修復方案、測試驗證計畫。
```

**輸出文檔**：`docs/tasks/101-phase3-deadlock-analysis.md`

### B. 實作（代碼修復）

**範圍**：實裝 lock order 修復（通常在 `sp_decrease_stock` 中加 `ORDER BY product_id`），確保 MySQL 一致性。

**提示詞模板**：
```
根據 A. 定位的分析，修改 sp_decrease_stock stored procedure：
- 在迴圈內對商品按 product_id 排序
- 確保每次調用都以一致的順序鎖定行

新增 OrderTransactionServiceDeadlockTest：
- 20 個併發 HTTP 請求，各買 5 件不同商品（例：P001-P005）
- 預期結果：0 次 deadlock，20/20 訂單成功
- 否則：每個 deadlock 記錄 MySQL error 1213 詳情

mvn clean test 通過，JaCoCo coverage >=80%。
```

**輸出文檔**：`docs/tasks/102-phase3-deadlock-fix.md` + 代碼提交

### C. 驗收（驗證）

**範圍**：用 k6 和 Testcontainers 驗證 deadlock 已消除。

**提示詞模板**：
```
運行 RealDeadlockRetryIntegrationTest（20 個並發 HTTP，attempts=1）：
- 預期：20/20 成功（無 1213），或若仍有則記錄並改進 lock order

運行 k6 壓測（100 VU, 10s, 無 retries）：
- 預期：0 HTTP 500，0 MySQL 1213，stock 精確對帳

生成報告：
- 修復前後的 deadlock 數量對比
- 修復後延遲變化（p95/p99）
- 結論：lock order 是否完全消除了 deadlock

記錄在 docs/tasks/103-phase3-deadlock-verification.md
```

---

## 🔄 Task 2：Idempotency 模式整合

### A. 定位（設計）

**範圍**：分析現有訂單重試機制中的冪等性風險（例：同一 request 被重試 3 次，會不會重複下單）。設計 idempotency key 方案。

**提示詞模板**：
```
檢查 Order 表和 CreateOrderRequest：
- 是否已有 idempotency_key / request_id 欄位？
- 現有重試邏輯（OrderTransactionService.executeWithRetry）是否使用了它？

設計冪等鍵方案：
1. 於 CreateOrderRequest DTO 中添加 idempotencyKey（客戶端生成或伺服器分配）
2. 在 order_request 表中添加 UNIQUE(member_id, idempotency_key) 約束
3. checkout 流程：先檢查該鍵是否存在，有則返回既有訂單，無則新建

列舉測試場景：
- 同一 key 重複投遞 3 次，只建 1 筆訂單
- 不同 key 同秒投遞，各建各的訂單（stock 分別扣）
- 重試後股票狀態與訂單一致

文檔：冪等設計、異常邊界、測試計畫。
記錄於 docs/tasks/201-phase3-idempotency-design.md
```

### B. 實作（代碼修復）

**範圍**：實裝 idempotency key 檢查和去重邏輯。

**提示詞模板**：
```
1. 在 `order_request` 表中：
   - 添加 idempotency_key VARCHAR(64) NOT NULL 欄位
   - 添加 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
   - 添加 UNIQUE KEY `uq_member_idempotency` (member_id, idempotency_key)

2. 修改 OrderService.checkout()：
   - 若 CreateOrderRequest 中缺少 idempotencyKey，使用 requestId 作為備選
   - 先用 SELECT ... FOR UPDATE 查詢既有的 order_request（該鍵已被處理）
   - 若存在，查出關聯的 order_id，直接返回（冪等成功）
   - 若不存在，新建 order_request 記錄 + 執行 checkout（原邏輯）

3. OrderTransactionService 改造：
   - 不再盲目重試；改為冪等鍵 + retry 的組合
   - 重試時仍用同一鍵，自動命中冪等分支

新增 IdempotencyIntegrationTest：
- 測試 3 次重投同鍵、不同鍵、邊界情況
- 驗證 stock 和 order 一致性

mvn clean test 通過，JaCoCo >=80%。
```

**輸出文檔**：`docs/tasks/202-phase3-idempotency-impl.md` + 代碼提交

### C. 驗收（測試）

**範圍**：用併發測試和 E2E 驗證冪等性。

**提示詞模板**：
```
運行 IdempotentCheckoutConcurrencyTest：
- 10 個 HTTP 請求用同一 idempotencyKey，併發投遞
- 預期：只有 1 筆訂單被建立，其餘 9 個返回同一 order_id（HTTP 200，非 409）
- 驗證 stock 扣減 1 份（不是 10 份）

運行 IdempotentRetryIntegrationTest：
- 1 個請求故意在 50% 成功率下重試（模擬網路抖動）
- 同一鍵重投 3 次：第 1 次成功、第 2 次重連失敗、第 3 次重投
- 預期：仍只建 1 訂單，stock 對帳精確

手動 E2E：
- 用同一訂單 API 呼叫，用 curl 或 Postman 連發 3 次，檢視日誌確認只入庫 1 次

記錄在 docs/tasks/203-phase3-idempotency-verification.md
```

---

## 💾 Task 3：Redis Lua 原子操作 & Testcontainers 整合

### A. 定位（架構設計）

**範圍**：設計 Redis Lua script，實現原子庫存預留（single roundtrip，無網路往返）。規劃 Testcontainers MySQL + Redis 雙容器測試。

**提示詞模板**：
```
當前狀況：StockCacheService 用 Redis INCR + CAS，但分散在多個 JVM 呼叫（有爭用視窗）。

設計 Lua script：
1. 入參：productId, quantity, max_stock
2. 邏輯：INCR product:stock:{id} 後檢查是否 > max_stock
   - 若 <=max，返回當前值（成功）
   - 若 >max，DECR 回退，返回 -1（失敗）
3. 單一 EVALSHA 調用，原子保證

設計 Testcontainers 多容器：
- GenericContainer<MysqlTestContainer> + GenericContainer<RedisTestContainer>
- 兩容器在同一 Network，測試代碼可連接兩者
- 初始化：MySQL load schema + seed，Redis 清空

測試場景：
- 10 VU concurrent reserve，同 productId，stock=100，各搶 15 件 → 最多 6 個成功
- 驗證 Redis 與 MySQL 最終一致性

文檔：Lua script 邏輯、容器配置、測試計畫。
記錄於 docs/tasks/301-phase3-lua-design.md
```

### B. 實作（代碼修復）

**範圍**：實裝 Lua script，改造 StockCacheService，建立 Testcontainers 基礎測試。

**提示詞模板**：
```
1. 新建 src/main/resources/lua/reserve-stock.lua：
   ```lua
   local key = KEYS[1]  -- product:stock:{id}
   local max_stock = tonumber(ARGV[1])
   local qty = tonumber(ARGV[2])
   
   local current = redis.call('GET', key)
   if not current then current = 0 else current = tonumber(current) end
   
   if current + qty <= max_stock then
       return redis.call('INCRBY', key, qty)
   else
       return -1
   end
   ```

2. StockCacheService：
   - 添加 loadLuaScript() 在 @PostConstruct 中
   - reserve(productId, qty, maxStock) 用 EVALSHA 調用
   - 檢查返回值：>= 0 = 成功，-1 = 庫存不足

3. 新建 LuaStockIntegrationTest extends AbstractMySqlIntegrationTest：
   - GenericContainer<RedisTestContainer> as test field
   - setUp() 中建立兩容器 + 初始化
   - testConcurrentReserve(): 10 VU, 100 stock, 15/VU, 預期 6 成功
   - 驗證最終 stock audit（MySQL vs Redis）

mvn clean test 通過，新測試 3/3 或以上。
```

**輸出文檔**：`docs/tasks/302-phase3-lua-impl.md` + 代碼提交

### C. 驗收（性能 & 一致性驗證）

**範圍**：性能對比（舊 CAS vs 新 Lua）、故障恢復、資料一致性。

**提示詞模板**：
```
性能對比（k6 script）：
- 測試 A（舊）：Redis INCR + 應用層 CAS，100 VU, 30s
- 測試 B（新）：Redis Lua EVALSHA，100 VU, 30s
- 指標：throughput, p95/p99 latency, failed requests
- 預期：B 的延遲應顯著低於 A（single roundtrip vs multi）

故障恢復測試（RedisRecoveryTest）：
- 測試中途停止 Redis container（模擬故障）
- 應用層應切換到 degraded mode（MySQL-only path）
- 重啟 Redis 後應自動恢復，且 cache audit 正確同步

一致性測試（ConsistencyAuditTest）：
- 100 VU 並發購買，中途隨機暫停 Redis 1-2 秒
- 事後運行 cache.audit()：MySQL stock == Redis stock
- 檢驗零遺漏、零重複

記錄在 docs/tasks/303-phase3-lua-verification.md
```

---

## 📊 Task 4：k6 壓測套件

### A. 定位（測試計畫）

**範圍**：定義 3 個標準工作負載（正常購買、deadlock 壓力、售罄場景），建立性能基準。

**提示詞模板**：
```
場景 1 — Normal Purchase (B)：
- VU: 50, Duration: 60s, Stock: 10,000 per product
- 預期：高成功率 (>95%), p95 <1s, no 409/500
- 代表：穩定購買流量

場景 2 — Deadlock Stress (D)：
- VU: 100, Duration: 30s, Stock: 100 per product, 10 products
- 每 VU 一次買全部 10 商品（觸發 lock contention）
- 預期：無 MySQL 1213, 所有訂單成功或正常 409
- 代表：最壞並發情況

場景 3 — Stock Depletion (S)：
- VU: 200, Duration: 10s, Stock: 500 per product, 各買 3 件
- 預期：200*3=600 > 500 stock，需要精確 409 分配
- 無超賣（stock < 0）、無 orphaned order_request
- 代表：售罄邊界

測試設計：
- B/D/S 各單獨執行一輪（不混合）
- 記錄原始 JSON metrics（k6 summary + 自訂 CSV）
- 生成對比圖表

文檔：場景定義、metric 欄位、acceptance criteria。
記錄於 docs/tasks/401-phase3-k6-plan.md
```

### B. 實作（k6 腳本）

**範圍**：實裝 k6 JavaScript，指標採集，報告生成。

**提示詞模板**：
```
新建 bench/phase3-load-tests.js：

export let options = {
  stages: [
    { duration: '10s', target: 50 },  // ramp-up
    { duration: '60s', target: 50 },  // steady
    { duration: '10s', target: 0 }    // ramp-down
  ],
  thresholds: {
    'http_req_duration': ['p(95)<1000'],
    'http_req_failed': ['rate<0.05']
  }
};

// 場景 B (Normal)
export function scenarioNormal() { ... }
// 場景 D (Deadlock)
export function scenarioDeadlock() { ... }
// 場景 S (Stockout)
export function scenarioStockout() { ... }

// 運行方式（bench/README.md）：
// k6 run --vus 50 --duration 60s --scenario scenarioNormal phase3-load-tests.js

每個場景後執行 ResultsSummary.java，生成 CSV + Markdown 報告。
mvn exec:java -Dexec.mainClass="bench.ResultsSummary" -Dexec.args="phase3-b phase3-d phase3-s"

生成 bench/PHASE3_RESULTS.md 對比表。
```

**輸出文檔**：`docs/tasks/402-phase3-k6-impl.md` + bench/ 代碼

### C. 驗收（基準建立）

**範圍**：運行 3 場景各 1 輪，記錄基準指標，建立 CI 門檻。

**提示詞模板**：
```
執行以下命令，記錄結果（每個場景 1 輪）：

# 場景 B: 50 VU, 60s
k6 run -e ENV=test --out json=results-b.json bench/phase3-load-tests.js

# 場景 D: 100 VU, 30s
k6 run -e ENV=test --scenario=deadlock --out json=results-d.json bench/phase3-load-tests.js

# 場景 S: 200 VU, 10s
k6 run -e ENV=test --scenario=stockout --out json=results-s.json bench/phase3-load-tests.js

驗收標準（CI gate）：
- B: http_req_duration p95 <= 1000ms, fail_rate <= 5%
- D: zero MySQL 1213, fail_rate <= 1%  
- S: zero oversell (stock >= 0), 409 rate matches load

生成 bench/PHASE3_RESULTS.md：
- 三場景對比表（VU/duration/throughput/p95/p99/fail_rate）
- 圖表（可選：throughput over time, latency heatmap）
- 結論：是否滿足 Phase 3 acceptance criteria

提交 docs/tasks/403-phase3-k6-verification.md + bench/PHASE3_RESULTS.md
```

---

## 🤖 Task 5：GitHub Actions CI/CD Pipeline

### A. 定位（架構設計）

**範圍**：設計 CI/CD 流程（test → build → push）。制定分支保護、status checks。

**提示詞模板**：
```
CI/CD 流程設計：

1. Trigger: PR 開啟時、advanced-v2 push 時
2. Jobs（順序）：
   - Test: mvn clean test + JaCoCo gate + SonarQube (optional)
   - Build: Docker image build (layered, cache optimization)
   - Push: 推送至 ghcr.io/whitehot0321/esun-shopping:{branch}-{short-sha}
   - Deploy: (optional) 部署至 staging（k8s or Cloud Run）

3. 分支保護規則：
   - advanced-v2: 需通過 all status checks（test + build）
   - main: 需 PR + code review + all checks

4. 狀態徽章：README.md 中展示 test/build/push 狀態

設計文檔：workflow steps、secrets 管理、回滾策略。
記錄於 docs/tasks/501-phase3-cicd-design.md
```

### B. 實作（GitHub Actions workflow）

**範圍**：實裝 .github/workflows/test-build-push.yml。

**提示詞模板**：
```
新建 .github/workflows/ci-cd.yml：

name: Test, Build & Push
on:
  push:
    branches: [advanced-v2, main]
  pull_request:
    branches: [advanced-v2, main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: esun_shop
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Maven test
        run: mvn -B clean test
      - name: JaCoCo report
        run: mvn jacoco:report
      - name: Check JaCoCo gates
        run: mvn verify -Dskip.tests -P coverage

  build:
    needs: test
    runs-on: ubuntu-latest
    if: github.event_name == 'push'
    steps:
      - uses: actions/checkout@v3
      - name: Build Docker image
        run: docker build -t esun-shopping:${{ github.sha }} .
      - name: Push to GHCR
        run: |
          docker tag esun-shopping:${{ github.sha }} ghcr.io/${{ github.repository }}:${{ github.ref_name }}-${{ github.sha }}
          docker push ghcr.io/${{ github.repository }}:${{ github.ref_name }}-${{ github.sha }}
        env:
          DOCKER_USERNAME: ${{ github.actor }}
          DOCKER_PASSWORD: ${{ secrets.GITHUB_TOKEN }}

分支保護規則設定：
- Settings > Branches > advanced-v2
- Require status checks to pass before merging: ✅ test, build
- Dismiss stale PR approvals: ✅
- Require code review: 1 approval
```

**輸出文檔**：`docs/tasks/502-phase3-cicd-impl.md` + .github/workflows/ 代碼

### C. 驗收（測試 & 文檔）

**範圍**：驗證 workflow 正常運作，撰寫部署手冊。

**提示詞模板**：
```
驗收步驟：

1. 提交一個 PR 至 advanced-v2，觀察 workflow 執行：
   - test 應通過（✅）
   - build 應完成（✅）
   - 推送至 GHCR 成功
   - 檢視 PR 狀態頁面，確認 status checks 齊全

2. 合併 PR，檢視 advanced-v2 branch 的最新 push 觸發：
   - workflow 再次執行
   - Docker image 已推送至 ghcr.io

3. 撰寫 docs/DEPLOYMENT.md：
   - 預置環境：Docker, kubectl（或 gcloud CLI）
   - 本地測試：docker run -e SPRING_DATASOURCE_URL=... esun-shopping:tag
   - 部署至 k8s（或 Cloud Run）：指令範例
   - 回滾策略：推送舊版 image tag

4. README.md 中添加狀態徽章：
   ![CI/CD](https://github.com/.../workflows/CI%2BCD/badge.svg)
   ![Docker](https://github.com/package-registry/...)

記錄在 docs/tasks/503-phase3-cicd-verification.md
```

---

## 📝 使用此提示詞庫

### 逐任務發起提示詞

1. **Phase 3.1 開始前**，讀 `docs/project-state.md` 確認優先級無變。
2. **每個 Task** 分成 **A 定位 / B 實作 / C 驗收**，分開發起提示詞（不要一次丟進去）。
3. **A 定位** → 若同意方案，進 **B 實作** → B 完成後進 **C 驗收**。
4. **完成後**，在 Notion 記錄：Task / Problem / Root Cause / Solution / Engineering Concept / Test Result / Trade-offs / Next Step。

### 範圍控制

- **A 定位**：純研究，不寫代碼。輸出設計文檔、test plan、code review checklist。
- **B 實作**：寫代碼。输出可測試、能 commit 的代碼。
- **C 驗收**：測試 & 報告。確保指標達成，記錄數據。

### 風險分級（見 AGENTS.md）

- **Task 1-2** (Deadlock/Idempotency)：使用 `implementation-engineer` 或 `test-engineer`，B 完成後獨立 code review。
- **Task 3** (Redis Lua/Testcontainers)：`test-engineer` 主導，integration test 複雜度高，建議 Codex 與 Claude 交叉審查。
- **Task 4-5** (k6/CI/CD)：`implementation-engineer`，routine 工作，review 門檻較低。

---

## 相關資源

- **docs/project-state.md** — 實時進度
- **CLAUDE.md** — 項目全貌、agent 定義
- **AGENTS.md** — Codex×Claude 分工規則
- **prompt-scope-control.md** — 提示詞拆分原則
- **docs/phase3-engineering-depth-prompt.md** — 此文件

---

**最後更新**：2026-09-21（戰略轉向確認）
