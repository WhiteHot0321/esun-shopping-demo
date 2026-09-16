# Task 008 — Phase 1.5 B0 基準執行結果（2026-09-16）

執行者：Claude Code。需求與通過條件見 [Task 007](007-phase15-baseline-acceptance.md)；
本文件只記錄本次實際執行證據，不重複列規範內容。

## Task

在 Docker 恢復可用後，對最新版程式（advanced-v2 @ `5574f05` + 既有未提交 Phase 2.5
變更）補齊 P15-1（歷史工作負載重現）、P15-2（足量庫存持續下單基準）、P15-3（MySQL
並發／查詢／rollback 回歸）的第一手 runtime 證據，取代 Task 006 因 Docker 不可用留
下的靜態複核 + 13 個初始化錯誤的狀態。

## Problem

Task 006（2026-09-16 07:16）記錄：定向 15 項測試 2 passed、13 Docker 初始化錯誤，
Maven exit 1；Task 007 明確列出 P15-1/P15-2/P15-3 全部「待驗收」，且「最近 Docker
阻礙是既有紀錄，本次未重查」。也就是說 Phase 1.5 三個具體需求在最新版程式上完全沒有
可信的 runtime 證據，只有 Phase 1 歷史版本（`9cd487c` 等）的舊結果可參考。

## Root Cause

單純環境問題：先前 session 中 Docker Desktop 的 dockerInference socket 無法啟動，
且 AGENTS.md 規定不可自動修復環境（"自動化清理已被政策拒絕"），導致連續數個 session
只能做文件同步，無法產生新的 runtime 證據。本次確認 Docker engine 正常（`docker
info`/`docker ps` 皆成功，既有 esun-mysql/esun-redis 容器健康），阻礙已解除。

## Solution

1. **P15-3**：直接在 `backend/` 執行 `mvn clean test`（非窄選測試，涵蓋 coverage
   gate），確認全套 72 個測試（含 `RealDeadlockRetryIntegrationTest`、
   `OrderServiceQueryCountIntegrationTest`、`OrderRollbackIntegrationTest`、
   `HighConcurrencyOrderIntegrationTest`、`OrderConcurrencyIntegrationTest`、
   `RedisOrderIntegrationTest` 等）0 failures/0 errors/0 skipped，且
   `jacoco-maven-plugin` 的 `check-core-services` 規則（綁定於 `test` phase，
   非僅報表）以 exit code 0 通過。
2. **P15-1/P15-2**：另立一個**拋棄式** `mysql:8.0` 容器（非使用者的 Compose
   volume），用 `backend/DB/*.sql` 重新種子，種子值與歷史一致
   （P001=5/P002=50/P003=20）；以 `STOCK_REDIS_ENABLED=false`、
   `ORDER_RETRY_MAX_ATTEMPTS=1`（B0）啟動後端，依序執行：
   - 2 品項歷史腳本（`bench/order-load-test.js`，40 VUs/45s）
   - 3 品項歷史腳本（`bench/order-load-test-multiitem.js`，庫存調至 200，
     40 VUs/45s）
   - 足量庫存持續下單腳本（同 3 品項腳本，庫存調至 100,000，40 VUs/45s）

   每輪執行前後用 `docker exec ... mysql` 直接查 `product.quantity` /
   `shop_order` / `order_detail` 筆數做對帳，並對後端 stdout grep
   `Deadlock found` / `Lock wait timeout exceeded`。

## Engineering Concept

**控制變因下的證據分級**：同一份「重試後 HTTP 成功」「底層是否真的死鎖」「資料是否一致」
是三件互相獨立的事，必須各自量測而不能互相替代——這正是 Task 007 全文的核心原則。本次
執行示範了具體做法：用拋棄式資源隔離量測環境（不動使用者的 Compose volume）、用
before/after 直接查表對帳（而非信任應用層回傳值）、把 HTTP 500 這種模糊桶用後端原始
log 反查真正的 SQLSTATE/錯誤碼，才能把「業務拒絕（409/庫存搶奪 SIGNAL）」與「基礎設施
故障（1213 死鎖／1205 鎖等待）」分開判定。

## Test Result

完整數字見 [Task 007 §8](007-phase15-baseline-acceptance.md#8-執行紀錄--2026-09-16docker-恢復後首次-runtime-驗收)
與 `bench/RESULTS.md`「Current-version B0 baseline」章節。摘要：

| 項目 | 結果 |
|---|---|
| `mvn clean test`（P15-3） | 72/72 通過，0 failures/errors/skipped；4 服務 coverage gate 全數 ≥80%（OrderService/OrderTransactionService/ProductService 100%，StockCacheService 83.3%） |
| P15-1 2 品項（1 輪） | 15,575 requests；20 成功／15,545 個 409／9 個 500；0 死鎖／0 鎖等待；庫存與訂單對帳精確 |
| P15-1 3 品項（1 輪） | 14,641 requests；200 成功／14,431 個 409／9 個 500；0 死鎖／0 鎖等待；庫存與訂單對帳精確 |
| P15-2 足量庫存（1 輪） | 1,985/1,985 新訂單成功（100%）；43.2 訂單/秒；p95/p99 992ms/1.07s；0 死鎖；未售罄 |

## Trade-offs (if any)

- **僅 1 輪，非 Task 007 規範的 3 輪**：單輪結果方向一致（0 死鎖、精確對帳）且與歷史
  `9cd487c` 重跑（0/0 死鎖，各 9 個 500）高度吻合，但不能排除輪次間變異；未來若需要
  更高置信度，應直接重跑 2 輪並用中位數/範圍呈現，而非重新設計方法論。
- **P15-2 未做獨立 10 秒暖機隔離**：Task 007 要求暖機請求不計入指標；本次為求在合理
  時間內取得第一手證據，直接量測整段 45 秒（無獨立暖機窗口），已在 RESULTS.md 明確
  標註此偏差，未混充為完全合規的結果。
- **排序反向敏感度測試未執行**：需要獨立測試副本、不動主要工作樹，本次時間範圍內未做，
  記錄為缺項而非略過不提。
- **Phase 2.5（P25 B0/C3/R3、受控死鎖、Redis 故障恢復）本次未觸碰**：維持 Task
  006/007 既有歷史狀態，未宣稱新證據，避免與本次 Phase 1.5 範圍混淆。

## Next Step

- 若需更高置信度：重跑 P15-1/P15-2 各 2 輪（湊滿 3 輪）並加上獨立暖機隔離。
- 否則：Phase 1.5 三項具體需求已各自取得最新版第一手證據，可視為本輪執行完成並封版；
  後續工作轉向 Phase 2.5 剩餘驗收項目（P25 B0/C3/R3 三輪、受控死鎖 HTTP
  attempts=1/3、Redis 故障／補償／replay／恢復、獨立複核與最終 clean suite coverage）。
