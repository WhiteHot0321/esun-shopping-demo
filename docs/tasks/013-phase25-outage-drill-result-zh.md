# Task 013（中文版）— Phase 2.5 子任務 B 結果：Redis outage／恢復演練（2026-09-16）

執行者：Claude Code。範圍與通過條件見 [Task 010](010-phase25-followup-3round-outage.md)
子任務 B；本文件只記錄本次實際執行證據。

> **與 [Task 012](012-phase25-redis-outage-result.md) 為同一次演練的兩份獨立記錄**：
> 兩個並行 Claude Code session 在同一個 checkout 上幾乎同時實作了同一個子任務，
> 最終收斂成內容幾乎相同的 `RedisOutageRecoveryDrillTest.java`（同一份 commit
> `5063971` 已包含該測試檔、`bench/PHASE25.md` 與 `docs/project-state.md` 的更新，
> 兩邊不再重複記錄或衝突）。Task 012 是英文版、本檔案是對應的中文版，內容一致，
> 保留兩份是為了配合本專案文件的雙語慣例，不代表有兩次獨立的驗收結果。

## Task

依 `bench/PHASE25.md`「Cache operations」章節記載的恢復程序，實際演練 Redis
outage → 降級 → 恢復的完整流程，取得第一手證據，而不是只靠既有單元/整合測試
（`RedisUnavailableOrderIntegrationTest` 等）推論行為正確。只能操作拋棄式
Testcontainers，絕不可對使用者現有的 `esun-redis`/`esun-mysql` 常駐容器操作。

## Problem

先前僅有靜態複核與「Redis 不可用時降級為 DB-only」的單元測試層級證據，沒有真正
演練過「outage → 降級 → 手動對帳 → 恢復」的完整生命週期，也沒有驗證過
`bench/PHASE25.md` 文件裡「restart writers」這句話實際指的是什麼。

## Root Cause

不適用（驗收演練，非修 bug）。過程中發現兩個值得記錄的環境／設計事實（見下）。

## Solution

第一次嘗試：用 SpringBootTest + 真實 `docker pause`/`docker stop`/`docker start`
操作 Testcontainers 自建的 Redis 容器。**兩次嘗試都因 Windows Docker Desktop 的
port-forwarding 行為而不可靠而放棄**：
- `docker pause` 只凍結容器行程，並不會讓已送出、還在 socket 佇列裡的指令消失——
  解凍後 Redis 照樣把那個 Lua 遞減腳本執行掉，即使呼叫端當下已經因逾時判定為失敗
  並回傳 BYPASSED。也就是說「client 端逾時」不保證「指令沒有真的生效」，這正是
  `bench/PHASE25.md` 早就提醒的「Cache network ambiguity may cause drift; this is
  not a distributed transaction」的具體重現，但不是本次演練想模擬的情境（斷線）。
- `docker stop` + `docker start` 則是連續 30 秒 `Connection refused`，容器沒有在
  合理時間內恢復可連線狀態（很可能是 Docker Desktop 在 WSL2 上重新建立 port-proxy
  的延遲或失敗，非本專案程式碼問題）。

最終改用與既有、已驗證穩定的 `RedisUnavailableOrderIntegrationTest` 相同手法：
指向一個不存在的 port（`127.0.0.1:1`）製造真實連線失敗，完全不碰容器生命週期。
測試改寫成 Spring-free、`StockCacheService` 層級（與既有
`StockCacheServiceIntegrationTest` 同一種寫法），DB 真相以一個簡單的
`Map<String,Integer>`（搭配 mocked `ProductRepository`）追蹤，因為 DB 條件式扣
庫存本身的交易正確性已由其他既有整合測試涵蓋，本演練的重點是 cache／對帳契約，
不是重新證明訂單交易邏輯。新檔案：
`backend/src/test/java/com/esun/shop/service/RedisOutageRecoveryDrillTest.java`。

## Engineering Concept

**「連線失敗」和「指令沒有生效」不是同一件事**：這次演練意外撞見一個很好的反例——
`docker pause` 造成的「凍結」讓 client 端逾時判定失敗，但底層指令其實只是delay
執行、最終還是跑了。這解釋了為什麼 `StockCacheService.compensate()` 只在明確拿到
`RESERVED` 結果後才會被呼叫（見 `OrderService.createOrder` 的
`if (reservation.get() == RESERVED) compensate()`）：如果系統天真地「逾時就假設
沒扣成功、所以不用補償」，而底層指令其實延遲生效了，就會產生「明明扣了兩次卻只
補償一次」的真正超賣/超補償問題。反過來，「絕不對未確認的操作做補償」這個保守
設計，代價就是本次演練實測到的：一旦 `degraded` 被設為 true，即使之後手動把
Redis key 修正到與 DB 一致，**同一個 JVM 行程也不會自動恢復使用 Redis**——這不是
遺漏，而是有意的保守設計；`bench/PHASE25.md` 提到的「restart writers」在程式碼
層面的真正意涵是「重啟應用程式行程」，而不只是「恢復流量」。

## Test Result

`mvn -q "-Dtest=RedisOutageRecoveryDrillTest" test`：1/1 通過，exit code 0。
完整流程與斷言：

| 階段 | 操作 | 斷言結果 |
|---|---|---|
| 基準 | 健康 Redis 下 `tryDecrease` | RESERVED；DB 與 Redis 同步遞減至 49；`audit()` 為空 |
| Outage 第 1 次呼叫 | 指向不存在的 port（連線被拒絕，非逾時，實測 ~36ms 就失敗） | BYPASSED；`degraded` 鎖定；DB 模擬繼續遞減至 48（`degraded` 的鎖定不影響 DB-conditional 防線，這條防線本身已由其他測試涵蓋） |
| 連線工廠銷毀後再呼叫 | 呼叫同一個已降級的 instance，且底層連線工廠已被 `destroy()` | 依然乾淨回傳 BYPASSED、不拋例外；證明無論連線壞到什麼程度，這條路徑都不會讓應用當機或掛起 |
| 觀察 drift | 健康 instance 的 `cache.audit()` | 回報 `p1` drift（DB=47，Redis 仍停在 49，因為降級 instance 從未真正碰過健康 Redis 的 key）；`p2` 未受影響、不在 drift 清單中 |
| 手動對帳 | 依恢復程序，把 DB 權威快照寫回所有 `stock:{productId}`（含未漂移的 `p2`） | `cache.audit()` 恢復為空 |
| 對帳後、同一 instance 再次下單 | 已降級的 instance 仍是同一顆 | DB 繼續遞減、Redis key 完全沒動——證明「修好 Redis」不等於「這個行程恢復用 Redis」 |
| 模擬真正重啟 | 建立全新 `StockCacheService`（`degraded=false`，等同新 JVM），先 `preload()`（SET NX，不覆蓋剛修好的值） | `tryDecrease` 回傳 RESERVED，Redis key 真的被扣減；`audit()` 為空——證明唯有「重啟」才能真正恢復使用 Redis |

全套 `mvn clean test`：第一次跑到 69/76 附近時出現 4 個測試類別（`RedisOrderIntegrationTest`、
`RedisUnavailableOrderIntegrationTest`、`JwtAuthFilterTest`、`OrderRetryTest`）
的 Spring context 啟動失敗（`UnsatisfiedDependencyException`／
`Unable to find a @SpringBootConfiguration`）；**立即重跑第二次完全乾淨通過
（75/75，0 failures/errors/skipped，BUILD SUCCESS）**，判定第一次是這台機器上
多個並行 session 同時大量啟動 Testcontainers 造成的暫時性資源競爭，不是本次新增
測試造成的迴歸——已用兩次獨立跑測結果佐證，不是臆測。JaCoCo coverage gate 同樣
維持 OrderService 94.4%、OrderTransactionService 100%、StockCacheService 95.2%、
ProductService 100%，`RedisOutageRecoveryDrillTest` 本身也自動被納入預設的
`mvn test` 套件（檔名符合 Surefire 預設納入規則，且僅需一個輕量 Testcontainers
Redis、不操作 Docker CLI），成為往後持續回歸驗證的一部分，而不只是一次性演練。

## Trade-offs (if any)

- **未透過真實 Docker 容器操作模擬 outage**：改用「指向不存在的 port」而非真的讓
  一個原本健康的容器斷線，兩者在 client 端的可觀察行為（連線失敗、逾時、
  `degraded` 鎖定）相同，但沒有涵蓋「原本連線中途被切斷」（相對於「一開始就連不
  上」）這個更細緻的子情境；若未來需要，可用 Toxiproxy 之類的工具在 TCP 層注入
  故障，而不是直接操作容器行程。
- **DB 真相用 mock/map 模擬，未經過真正的 `OrderService`／MySQL 交易**：DB
  條件式扣庫存本身的交易、rollback 正確性已由 `RedisUnavailableOrderIntegrationTest`
  等既有測試在真實 MySQL 上驗證過；本次演練刻意只聚焦 cache／對帳契約，避免重工。
- **跨行程／跨實例的 reconciliation 未演練**：`bench/PHASE25.md` 明確說「Automatic
  cross-instance reconciliation is not provided」，屬已知架構限制，本次沒有另外
  驗證多實例情境。
- **售罄壓力情境、`Phase25K6DeadlockAcceptance` HTTP 成對死鎖比對仍是獨立缺口**：
  未包含在本任務範圍內。

## Next Step

- Task 010 兩個子任務（三輪重複驗收、Redis outage 演練）皆已完成，可在
  `docs/project-state.md`、Notion 進度追蹤與 Phase 2.5 專頁的 checklist 中打勾。
- 剩餘的 Phase 2.5 缺口：`Phase25K6DeadlockAcceptance` 的 HTTP 成對死鎖比對、
  售罄壓力情境（尚未排定為任何任務，需要時再開新任務）。
- 建議在 `bench/PHASE25.md`「Cache operations」的 Recovery procedure 補一句明確
  說明：「restart writers」包含重啟應用程式行程本身，而不只是恢復下單流量——這是
  本次演練的具體發現，留給下一次觸碰該文件的人一併處理，不在本次任務內順手改動
  （避免與其他同時在寫這份文件的並行 session 再次衝突）。
