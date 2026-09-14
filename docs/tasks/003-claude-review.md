## 獨立審查結論

已依需求文件逐一比對後端 DTO/Service/Repository/ExceptionHandler、schema/migration、新增的併發與 HTTP 測試、前端 App.vue/checkout.js/checkout.test.js、bench JS。**未發現會阻擋驗收的具體缺陷**。核心邏輯（claim-first insert、DuplicateKeyException 僅包在 claimRequest 周圍、REPEATABLE READ 下靠「claimRequest 是交易內第一個讀寫操作」保證後續 SELECT 建立在衝突交易已 commit 之後的一致性讀取、失敗交易靠拋出未捕捉例外回滾 claim、lock-order 與 decreaseStock-before-insert 沿用既有防死鎖設計）皆與需求描述一致，且測試（`OrderIdempotencyIntegrationTest` 20 並發同 key/20 並發異 key/決定性 rollback 三案例、`OrderControllerTest` 四個 HTTP 案例、`OrderServiceTest` mismatch/duplicate 單元測試）具體對應需求逐項覆蓋，`AbstractMySqlIntegrationTest` 未使用會遮蔽 service 回滾的 test-managed transaction。

需要留意的殘餘風險（非阻擋性缺陷，供決策參考）：

1. **前端 checkoutAttempt 僅存於記憶體，無持久化**（`frontend/src/App.vue:102`）。若使用者在「ambiguous failure（無 response 或 5xx）」後直接重新整理頁面而非點擊重試，`checkoutAttempt`／`requestId` 會遺失；若當次請求其實已在伺服器端成功執行，reload 後的下一次送出會用全新 requestId，等同繞過冪等保護造成重複下單。需求僅要求「同一次瀏覽階段內保留 key 並精確重試」，未要求跨 reload 持久化，故列為殘餘風險而非缺陷。
2. **member-mismatch（409 且不洩漏 orderId）僅有 mock 化測試**（`OrderServiceTest` 與 `OrderControllerTest`），未見對應的 Testcontainers 真實 MySQL 併發整合測試覆蓋此路徑；相較 same-key/distinct-key/rollback 三案例都有真實 DB 驗證，此路徑目前屬「未以真實 DB 執行驗證」的路徑。
3. `newCheckoutAttempt` 產生的 UUID 本身未被 `checkout.test.js` 直接斷言格式（僅間接由後端 regex 把關），若未來更換 UUID 產生方式沒有測試會第一時間攔截。

以上三點均為建議關注的殘餘風險/未執行測試範圍，非需求違反或可重現的功能缺陷。
