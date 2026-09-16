# Task 011 — Phase 2.5 子任務 A 結果：B0/C3/R3 三輪重複驗收（2026-09-16）

執行者：Claude Code。範圍與通過條件見 [Task 010](010-phase25-followup-3round-outage.md)
子任務 A；本文件只記錄本次實際執行證據。

## Task

把 `Phase25K6Acceptance`（B0/C3/R3）從先前的「各 1 輪」（見 `bench/PHASE25.md`
"Current-version run" 與 `docs/project-state.md` commit `6b1e690`）補齊到
Task 007 §3 要求的「各三輪」，並回報跨輪中位數／範圍，而非只取單一輪次代表整體。

## Problem

單輪結果雖然乾淨（B0 910/910、C3 895/895、R3 871/871，皆 100%），但單一樣本無法
排除輪次間變異；Task 007 明確要求三輪重複才能建立可信基準。

## Root Cause

不適用（純粹補齊統計置信度，非修 bug）。

## Solution

依 `bench/PHASE25.md` 既有指令，對每個配置連續執行 3 次（每次都是全新
Testcontainers MySQL/Redis + 全新 20 VUs/20 秒 k6 負載，測試本身保證乾淨資料，
不需手動重置）：

```powershell
mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=false" "-Dorder.retry.max-attempts=1" test   # B0 x3
mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=false" "-Dorder.retry.max-attempts=3" test   # C3 x3
mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=true"  "-Dorder.retry.max-attempts=3" test   # R3 x3
```

未修改 `Phase25K6Acceptance.java` 或任何 production code。

## Engineering Concept

**單輪 vs 多輪置信度**：一次乾淨的通過只能證明「這次沒出問題」，無法區分「系統
穩定地不出問題」與「這次剛好沒撞上」。三輪重複的價值不在於湊數字，而在於觀察
輪次之間是否存在系統性差異（例如某輪成功率明顯偏低、庫存對帳出現偏差）——若九
輪結果高度一致，才能把「單輪通過」升級為「這個配置在這個負載下穩定通過」的結論。

## Test Result

Docker 28.4.0、k6 v2.2.0，基準 commit `007f198`（含 e2 的 Phase 2 JWT/App.vue 合併）。
每輪成功率＝成功新訂單數／（成功＋409＋404＋400＋500＋其他狀態＋無回應）總數；
本次三種配置每輪均為 100%（成功數＝總請求數，未出現任何非 200 分類）。

| 配置 | 輪次 | 成功/總數 | 成功率 | Retries | 期末庫存 | Redis audit() |
|---|---|---|---|---|---|---|
| B0（Redis off, attempts=1） | R1 | 705/705 | 100% | 0 | 9295 | DB-only |
| B0 | R2 | 702/702 | 100% | 0 | 9298 | DB-only |
| B0 | R3 | 734/734 | 100% | 0 | 9266 | DB-only |
| C3（Redis off, attempts=3） | R1 | 756/756 | 100% | 0 | 9244 | DB-only |
| C3 | R2 | 728/728 | 100% | 0 | 9272 | DB-only |
| C3 | R3 | 729/729 | 100% | 0 | 9271 | DB-only |
| R3（Redis on, attempts=3） | R1 | 737/737 | 100% | 0 | 9263 | 空（無 drift） |
| R3 | R2 | 704/704 | 100% | 0 | 9296 | 空（無 drift） |
| R3 | R3 | 874/874 | 100% | 0 | 9126 | 空（無 drift） |

**跨輪彙總**：

| 配置 | 成功率中位數/範圍 | 總請求數中位數/範圍 |
|---|---|---|
| B0 | 100%（三輪皆同） | 705（範圍 702–734） |
| C3 | 100%（三輪皆同） | 729（範圍 728–756） |
| R3 | 100%（三輪皆同） | 737（範圍 704–874） |

九輪全數：期末庫存＝10,000－成功數，精確對帳；0 次 retry（20 VUs/20 秒、10,000
初始庫存的負載強度不足以觸發鎖衝突，與 attempts=3 的單輪結果一致）；R3 三輪
`cache.audit()` 皆為空，Redis／DB 零 drift。Maven exit code 皆為 0。

判定：**C3/R3 成功率 ≥95% 的通過條件在三輪中一致達成（實測 100%）**；B0 原樣
報告（同樣 100%，非通過門檻對象）。九輪之間沒有觀察到系統性差異或異常輪次，
不需要額外調查。

## Trade-offs (if any)

- **負載強度未變**：三輪重複驗證的是「同一負載強度下的穩定性」，不是更高強度下
  的行為；本次負載仍未耗盡 10,000 庫存、仍未觸發任何死鎖重試，因此不能用這九輪
  數字支持「retry 機制在高壓下有效」的結論——那屬於 `RealDeadlockRetryIntegrationTest`
  與 Task 010 子任務 B／未來售罄壓力測試的範疇。
- **未做明確的 1213/1205 分桶計數**：九輪 0 retries 已隱含「沒有死鎖」，但沒有
  另外從後端 log 逐輪 grep 確認完全零筆 1213/1205，是依賴測試斷言的 retry 計數
  而非獨立 log 稽核，與 Task 007 §「共用指標」要求的獨立分桶計數相比略有簡化。

## 補充：另一個並行 session 的獨立重複執行（同一時段，佐證證據）

另一個並行的 Claude Code session（同一 checkout，未協調）在幾乎同一時段（2026-09-16
10:31-10:40，基準 commit `96dbbad`）也獨立執行了同一個 Task 010 子任務 A 的完整 9 輪，
與本文件上方紀錄的 9 輪是**兩批各自獨立**的執行，不是同一批數字的重複貼上。這屬於
AGENTS.md「one writer per checkout」原則在本次被違反的實例（兩個 session 同時對同一
working tree 寫入同一份結果文件，先寫入的版本被後寫入的覆蓋）——記錄於此作為佐證，
而非重新執行或捨棄任何一批真實數據。

| 配置 | 輪次 | 成功/總數 | 成功率 | Retries | 期末庫存 | Redis audit() |
|---|---|---|---|---|---|---|
| B0（Redis off, attempts=1） | R1 | 687/687 | 100% | 0 | 9313 | DB-only |
| B0 | R2 | 783/783 | 100% | 0 | 9217 | DB-only |
| B0 | R3 | 785/785 | 100% | 0 | 9215 | DB-only |
| C3（Redis off, attempts=3） | R1 | 798/798 | 100% | 0 | 9202 | DB-only |
| C3 | R2 | 687/687 | 100% | 0 | 9313 | DB-only |
| C3 | R3 | 732/732 | 100% | 0 | 9268 | DB-only |
| R3（Redis on, attempts=3） | R1 | 748/748 | 100% | 0 | 9252 | 空（無 drift） |
| R3 | R2 | 714/714 | 100% | 0 | 9286 | 空（無 drift） |
| R3 | R3 | 678/678 | 100% | 0 | 9322 | 空（無 drift） |

跨輪彙總（此批）：B0 成功率中位數 100%（範圍 100%-100%），總請求數中位數 783（範圍
687-785）；C3 成功率中位數 100%，總請求數中位數 732（範圍 687-798）；R3 成功率中位數
100%，總請求數中位數 714（範圍 678-748）。九輪全數 Maven exit 0、0 retries、期末庫存
精確對帳、R3 三輪 `cache.audit()` 皆為空。

**兩批合計 18 輪的結論一致**：所有輪次成功率皆為 100%，C3/R3 沒有任何一輪出現 retry
（即使允許最多 3 次），兩批各自的總請求數都落在 678-798 的範圍內（同屬 20 VUs/20 秒
k6 負載在此硬體上的正常抖動區間）。兩個獨立 session 在同一時段各自產生一致的通過結論，
反而比單一批 9 輪更強地佐證了這個配置在這個負載強度下的穩定性；詳見
[bench/PHASE25.md](../../bench/PHASE25.md)「Three-round repeat」章節，其中同時引用了兩批數字。

## Next Step

- Task 010 子任務 A 完成，可在 `docs/project-state.md`、Notion 進度追蹤與 Phase 2.5
  專頁的 checklist 中打勾並附上本文件連結。
- Task 010 子任務 B（Redis outage／恢復演練）仍待執行，是下一個獨立 session 的
  單一任務；不與本任務或其他重型驗收並跑。
- `Phase25K6DeadlockAcceptance` 的 HTTP 成對死鎖比對、售罄壓力情境仍是未排定的
  獨立缺口，維持原樣記錄，不在本任務內擴大範圍。
