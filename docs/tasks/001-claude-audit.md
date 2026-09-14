# 任務 001：訂單併發與回滾覆蓋率靜態稽核

**聲明**：本報告為純靜態程式碼／測試盤點，**未執行任何測試或指令**。實際執行結果另見 `docs/tasks/001-order-concurrency-result.md`（Codex 已於同日執行 `mvn test`：24/24 通過）與 `bench/RESULTS.md` 的 k6 實測數據。

## 1. 覆蓋表

| 場景 | 狀態 | 證據 |
|---|---|---|
| 不可負庫存 | 已覆蓋 | DB CHECK: `DB/01_schema.sql:12`；SIGNAL 防護: `DB/03_stored_procedures.sql:35-44`；service 前置檢查: `OrderService.java:58-60`；DB 實測歸零: `HighConcurrencyOrderIntegrationTest.java:297-301` |
| 訂單不可部分失敗（rollback） | 已覆蓋 | `@Transactional`: `OrderService.java:40`；mock 層: `OrderServiceTest.java:116-161`；真實 DB 部分寫入後回滾: `OrderConcurrencyIntegrationTest.java:187-208`（已扣庫存回復、無孤兒 `order_detail`） |
| 重疊購物車 / deadlock 迴歸 | 已覆蓋，含 runtime 佐證 | 鎖序修正: `OrderService.java:71-84`；反向雙品項: `OrderConcurrencyIntegrationTest.java:85-134`；16 執行緒/5 共用商品: `HighConcurrencyOrderIntegrationTest.java:235-312`；k6 前後對照 deadlock 148→0、1212→0: `bench/RESULTS.md:389-399` |

## 2. 缺口（依影響力排序，均為測試證據缺口，非已證實生產缺陷）

1. **同商品重複列合計超賣**：`OrderService.java:58-60` 前置檢查逐列比對，未加總同商品多列數量；僅靠第二次 `sp_decrease_stock` 的 SIGNAL 攔截並整體回滾。目前僅 mock 測試 `OrderServiceTest.java:164-177`（`decreaseStock` 被 mock，未模擬真實扣減失敗），缺真實 DB 整合測試驗證此路徑回滾正確。
2. **last-unit race 測試對失敗來源判定寬鬆**：`OrderConcurrencyIntegrationTest.java:170-176` 把前置檢查(409)與 SIGNAL 兩種失敗都算合格 loser，未強制斷言失敗確實來自「已部分寫入後」的 SIGNAL 路徑，證明力不夠嚴謹（雖 REPEATABLE READ 快照下多數情況應會逼出 SIGNAL）。
3.（可選強化）`HighConcurrencyOrderIntegrationTest.java:92-96` 接受任何 `DataAccessException` 為已識別拒絕，理論上可能掩蓋非預期 DB 錯誤。

## 3. 建議後續任務

**T1：補強「同商品重複列合計超賣」的真實 DB 回滾測試**
驗收：單請求含同商品兩列各自不超賣但合計超賣；斷言拋出具體例外、該商品庫存不變、無新增 `shop_order`/`order_detail`；全套 backend 測試通過。

**T2：收緊 last-unit race 測試的失敗來源斷言**
驗收：明確斷言 loser 例外鏈可追溯到 `sp_decrease_stock` 的 SIGNAL，或分別記錄兩路徑出現次數；不變更既有並發情境；全套測試通過。
