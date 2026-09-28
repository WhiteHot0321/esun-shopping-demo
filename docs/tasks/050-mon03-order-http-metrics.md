# MON-03 訂單計數與 HTTP 延遲指標

- Task ID: MON-03
- Baseline: `advanced-v2` @ `69517faaa84ac70a914d08420e3d0a17969bd82a`
- Class: Critical（訂單交易結果與 retry 邊界）
- Risk: 計數位置錯誤可能把 rollback、冪等重播或中途 retry 誤算為新訂單／最終失敗。
- Implementer: Terra worker（受限於本文件範圍；單一 writer）
- Reviewer: Terra 唯讀獨立審查（初審 FAIL；correction 後 re-review PASS）
- Allowed files: `OrderService.java`、`OrderRetryTest.java`、`application.yml`、本文件
- Exclusions: 不改 `OrderTransactionService`、repository／schema、庫存／Redis 流程、HTTP 回應、正式環境、commit／push／merge；全套與 JaCoCo 留 MON-05。

## Acceptance

1. `shop.orders.success` 只計新訂單成功提交；rollback 與同 requestId 冪等重播不增加。
2. `shop.orders.failure` 每次 service 操作最終失敗只增加一次；成功 retry 不增加，retry 耗盡增加一次。
3. 驗證失敗發生在 service 外者不納入訂單 failure；HTTP 拒絕由 `http.server.requests` 觀測。
4. 計量錯誤不得遮蔽原始例外、改變交易提交／回滾，或造成訂單失敗。
5. HTTP meter 在真實請求後可查，並設定 histogram 與 p95/p99；tag 不得含 orderId、memberId、email、requestId 或 raw URL。
6. 文件註明 counter 為 process-local、重啟歸零；跨 instance percentile 不可直接視為全域分位數。

## Minimum verification

- `mvn -Dtest=OrderRetryTest test`（成功、冪等重播、rollback／非重試失敗、retry 成功、retry 耗盡的 counter 增量）
- MON-03 HTTP meter 窄測（先送實際 HTTP 流量，再查 meter／actuator 輸出）

## Result

實作進行中，尚未達驗收。`OrderService` 已加入以 `OrderCreationResult.newlyCreated` 判斷的新訂單成功計數、retry 耗盡計數，以及不應影響訂單結果的計量例外隔離；`application.yml` 已設定 `http.server.requests` histogram 與 p95/p99（Spring MVC 的標準低基數 method/status/uri/outcome/exception tags，不加入 orderId、memberId、email、requestId 或 raw URL）。這些 counter 為 process-local，應用程式重啟即歸零；多 instance 的本機 percentile 不能直接視為全域 percentile。

驗證未通過，故不可視為完成：

- `mvn -Dtest=OrderRetryTest test`（第一次，exit 1）：測試編譯被工作樹既有 `RedisLiveOutageIntegrationTest.java:137` 對原兩參數 `OrderService` 建構子的使用阻擋；已以相容建構子修復，未改該測試。
- `mvn -Dtest=OrderRetryTest test`（第二次，exit 1）：5 tests，2 failures，0 errors，0 skipped。`nonRetryableFailureCountsOnceAndPreservesTheOriginalException` 實測 counter 增量為 2 而非 1，原因是非重試例外已在 `createOrder` catch 計數，仍再進入 `@Recover(BusinessException, ...)`；`actualHttpTrafficIsRecordedAndExposedThroughTheMetricsEndpoint` 的 `/actuator/health/liveness` 實際請求回 401（JWT filter），測試不可假設匿名管理端點為 200。

依本任務最多一輪 repair、最多兩個測試命令的限制，未進行第二輪修復或第三次測試。下一步應在新 bounded session：移除 generic `@Recover` 的重複 failure 計數（保留 retry 耗盡的特定 recover 計數），並將 HTTP 窄測改為驗證實際 401 流量產生的 `http.server.requests` meter，或以合法 JWT 查 actuator 輸出。開始時工作樹已有 MON-01／MON-02 及其他使用者變更，全部保留。耗時約 6 分鐘；token／成本 unknown；使用者介入 0；repair round 1；有效 review defect 尚未審查。

### Correction result — current state

Independent-review correction completed. `OrderRetryTest` now first sends the real unauthenticated liveness request and proves its 401 `http.server.requests` timer/count and standard low-cardinality registry tags. It then uses a test-generated `JwtService` Bearer token to query `/actuator/metrics/http.server.requests`, asserting HTTP 200, meter name, available tag keys, and absence of `orderId`、`memberId`、`email`、`requestId`、`url`.

- `mvn '-Dtest=OrderRetryTest' '-Djacoco.skip=true' test` — exit 0; 6 tests, 0 failures, 0 errors, 0 skipped; test class 7.330 s, Maven total 13.011 s. JaCoCo was explicitly skipped because MON-03 does not include the repository-wide coverage gate.
- The prior generic `@Recover(BusinessException|DataAccessException)` duplicate failure-counter defect was corrected before this run; the test covers original-exception preservation and one increment for both non-retry failure types.
- Test bootstrap still logs a local MySQL access-denied warning from `EmbeddingIndexRunner`; it did not fail or exercise these six tests.

Implementation evidence was submitted for independent re-review. No commit, push, merge, full suite, or coverage validation was performed in the correction session; user interventions 0, repair rounds 1, elapsed about 3 minutes, token/cost unknown.

### Final acceptance

**PASS — MON-03 已完成實作、針對性驗證與獨立審查。** 初次獨立審查指出 HTTP 測試只讀
`MeterRegistry`、未查 Actuator metrics endpoint（有效 defect 1）；correction 在允許範圍內補上授權後的
`GET /actuator/metrics/http.server.requests`，指定窄測 6/6、exit 0，之後同一 reviewer 唯讀 re-review
確認缺口已關閉並判定 PASS。成功計數只記 `newlyCreated=true`；冪等 replay 不重複；retry 成功不記
failure；非 retry failure 與 retry 耗盡各只記一次；計量例外不改變訂單結果。HTTP histogram 與
p95/p99 已設定，真實 401 流量與 Actuator meter/tag 輸出均有測試證據。

未執行且不宣稱完成：完整 backend suite、JaCoCo gate（留 MON-05）、commit、push、merge、deploy。
總修復輪次 2（首輪修正重複計數／HTTP 401 假設，次輪修正獨立審查的 Actuator endpoint 缺口）；
使用者介入 0；有效 review defect 1；總耗時約 15 分鐘；token／成本 unknown。下一步：MON-04。
