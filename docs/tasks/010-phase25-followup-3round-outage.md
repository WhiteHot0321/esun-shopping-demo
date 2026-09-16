# Task 010 — Phase 2.5 後續任務：三輪重複驗收 + Redis outage 演練

排定者：Claude Code，2026-09-16 09:4x（Asia/Taipei）。這是安排／範圍界定文件，
**本文件建立時未執行任何測試**；執行留給下一個獨立 session（Claude Code 或 Codex
皆可），依 AGENTS.md 的 heavy-task 規則分開跑，不與其他重型驗收並行。

來源：[Task 007](007-phase15-baseline-acceptance.md) 與 [Task 006 修正複核](006-phase25-repair-review.md)
的驗收規格；`docs/project-state.md`（commit `d4198c5`／`b335a0f`）與 Notion「Phase 2.5」
專頁 checklist 標記為尚缺的兩項後續任務。單輪 B0/C3/R3、真實死鎖 attempts=1/3、
Redis 容錯套件、clean suite coverage 已於 commit `6b1e690` 完成，**不在本任務範圍內**。

## 子任務 A — 普通 k6 三輪重複驗收

### Session gate

- 目標：把 `Phase25K6Acceptance`（B0/C3/R3）從目前的「各 1 輪」補齊到 Task 007 §3
  要求的「各 3 輪，每輪乾淨等量資料」，並回報跨輪中位數／範圍。
- 分類：Medium（重複既有 runner，不改動 production code）。
- 允許修改：`bench/PHASE25.md`、`docs/project-state.md`、新結果文件
  `docs/tasks/011-phase25-3round-result.md`（或依實際編號）；**不修改**
  `Phase25K6Acceptance.java` 本身的斷言邏輯，除非發現既有測試有缺陷（若有，先停下
  回報，不在本任務內直接改動產測程式碼）。
- 執行前提：先跑 `docker ps`/`docker info` 確認 Docker 健康；`k6 version` 確認
  k6 在 PATH 上。三種配置的指令見 `bench/PHASE25.md`：
  ```powershell
  mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=false" "-Dorder.retry.max-attempts=1" test
  mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=false" "-Dorder.retry.max-attempts=3" test
  mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=true"  "-Dorder.retry.max-attempts=3" test
  ```
  每個配置連續執行 3 次（每次都是全新 Testcontainers MySQL/Redis，测试本身已保證
  乾淨資料，不需手動重置）。
- 每輪記錄（禁止只寫「通過」）：run 序號、成功／總數、成功率、`orders.getRetryCount()`
  的 delta、期末庫存是否等於 `10000 - success`、`cache.audit()`（R3）是否為空、
  Maven exit code。跨輪回報中位數／範圍，而非只取最好的一輪。
- 通過條件：C3/R3 三輪的成功率皆 ≥95%（實測應遠高於門檻，因負載未耗盡庫存）；
  三輪之間若出現顯著差異（例如某輪失敗率明顯偏高），要找出原因而不是丟棄異常輪。
- 排除：本任務不涉及售罄壓力情境（需要更高 VUs 或更低初始庫存，屬於另一個未排定的
  後續項目，若要做建議另開任務並在 `bench/order-load-test-multiitem.js` 之外新增
  一個耗盡庫存的變體腳本，不要改動現有 3 個配置共用的腳本）。

## 子任務 B — Redis outage／恢復演練

### Session gate

- 目標：執行 `bench/PHASE25.md`「Cache operations」章節描述的 outage/recovery
  程序，取得第一手證據，而不是只靠既有單元/整合測試（`RedisUnavailableOrderIntegrationTest`
  等）推論行為正確。
- 分類：Medium-Heavy（涉及即時操作 Redis 容器，需要謹慎，但不改 production code）。
- **重要邊界**：只能對本任務自建的拋棄式 Redis/MySQL 容器操作（例如透過
  `Phase25K6Acceptance` 或新的 Testcontainers 測試自帶的容器），**絕對不可
  對使用者現有的 `docker-compose.yml` 服務或現有的 `esun-redis`／`esun-mysql`
  常駐容器**執行 FLUSHALL、stop、kill 等操作 — 這些是使用者持續使用中的環境，
  演練造成的資料遺失無法簡單復原。若要在真實 compose 環境測試，必須先取得使用者
  明確同意並確認沒有其他人依賴該環境。
- 演練步驟（對照 `bench/PHASE25.md`「Recovery procedure」）：
  1. 在 R3 設定（`stock.redis.enabled=true`）下用 `Phase25K6Acceptance` 或一個新的
     專用整合測試啟動一輪負載，中途（例如壓測進行到一半）對**該測試自己的**
     Testcontainers Redis 容器執行 `docker pause`（模擬網路不可達，而非直接
     kill，因為 kill 後 Testcontainers 生命週期管理可能提前判定容器已死）。
  2. 觀察應用日誌：`StockCacheService` 是否記錄 "Redis stock unavailable; using
     DB-only mode" 並把 `degraded` latch 設為 true；確認訂單仍能繼續透過 DB
     條件式扣庫存正常成立（不因 Redis 不可達而整體失敗）。
  3. `docker unpause` 恢復 Redis 連線；確認 `degraded` 狀態**不會自動清除**
     （目前程式碼行為：`degraded` 是單向 latch，需要應用重啟或程式邏輯變更才會
     復位 — 若演練發現它會自動復位，這是需要回報的行為落差，不要當作預期通過）。
  4. 按照文件的 Recovery procedure：停用所有 writer（測試可簡化為停止對該實例送
     新訂單）、排空在途交易、用 `productRepository.findAllStock()` 或直接查
     `product` 表取得 DB 權威快照、用該快照重寫 Redis 的 `stock:{productId}`
     鍵（含庫存為零的商品）、跑 `cache.audit()` 確認零 drift 後才視為恢復完成。
  5. 記錄整個過程中：DB 訂單/庫存是否有任何超賣或遺失（不應該有，因為 DB 條件式
     扣庫存是最後防線）、outage 期間的訂單成功率／錯誤分類、恢復後 audit() 結果。
- 通過條件：outage 期間無超賣、無資料遺失、DB-only fallback 確實生效；恢復程序
  執行後 `cache.audit()` 為空（或明確列出無法收斂的 key 並說明原因）。
- 排除：不演練「兩個應用實例同時對同一個 Redis 做 reconciliation」的跨實例情境
  （`bench/PHASE25.md` 明確說「Automatic cross-instance reconciliation is not
  provided」，屬於架構層級的已知限制，不是本任務要解決的缺陷）。

## 共用要求

- 兩個子任務各自獨立成一個 session（不要在同一個 session 裡連續做完 A 再做 B，
  避免 heavy task 疊加、context 過大、以及子任務 B 操作 Redis 容器時與子任務 A
  的壓測互相干擾）。
- 完成後比照本專案既有文件模板（Task/Problem/Root Cause/Solution/Engineering
  Concept/Test Result/Trade-offs/Next Step）各寫一份結果文件，並同步
  `docs/project-state.md` 與 Notion「進度追蹤」／「Phase 2.5」專頁的 checklist
  （比照本次 Task 010 排定前、`b335a0f` 對這兩項的標記方式：未完成前保持
  unchecked 並附上限制說明，不要提前打勾）。
- 若 Docker／k6 環境在執行時不可用，記錄為環境阻礙並回報，不得留空或假裝已執行。

## Next Step

- 建議先做子任務 A（風險較低、純粹重複既有 runner），再做子任務 B（涉及即時操作
  容器，且需要先確認 A 的三輪結果穩定，才知道 outage 演練造成的成功率下降是否
  明顯偏離基準）。
- 兩者都完成後，Phase 2.5 對照 Task 007 §3 的具體需求 1、3（僅剩 HTTP 成對死鎖
  比對，見下方）、5、6 才算補齊；`Phase25K6DeadlockAcceptance` 的 HTTP
  attempts=1/3 成對比對仍是另一個獨立缺項，未包含在本任務內，需要時再另開任務。
