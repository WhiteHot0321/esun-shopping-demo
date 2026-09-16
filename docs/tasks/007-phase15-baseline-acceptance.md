# Task 007 — Phase 1.5 最新基準線與 Phase 2.5 分組驗收

## Session gate

- 日期：2026-09-16（Asia/Taipei）；執行者：Codex。
- 目標：明確記錄兩階段的配置、具體需求、通過條件、既有結果及尚缺證據，並同步 Notion。
- 分類：Small，文件工作；未委派 agent。範圍：訂單併發驗收文件。
- 核心證據：docs/project-state.md、Task 005、Task 006、bench/RESULTS.md、bench/PHASE25.md；另讀直接相關 Notion 頁面。
- 允許修改：本文件、docs/project-state.md、bench/PHASE25.md，最多 3 個本地文件；Notion 進度、階段工作表、策略、Phase 1.5／2.5 及執行計劃。
- 最低驗證：文件差異／git diff --check、Notion 所有修改頁面回讀及表格儲存格核對。
- 排除：production code、測試碼、實際壓測、Docker 修復、資料重置、其他 Phase 驗收、commit/push/merge。
- 基準觀察：advanced-v2 @ 5574f058583c28869656f6ae77eab848795efa4e，含既有未提交 Phase 2.5 變更；本文件不是已封版的執行快照。

## 1. 配置必須分開

以下都是「同一最新版程式」的配置比較，不等同回到 Phase 1.5 歷史原始碼。JWT、冪等 requestId、DB 條件式扣庫存與最新 schema 保留。

| ID | 用途 | stock.redis.enabled | order.retry.max-attempts | 解讀 |
|---|---|---|---|---|
| B0 | Phase 1.5 最新 DB 基準；Phase 2.5 共用控制組 | false | 1 | 一次交易嘗試，不能用重試隱藏鎖衝突 |
| C3 | Phase 2.5 C 重試效果 | false | 3 | 與 B0 比較；最多三次總嘗試，不是額外重試三次 |
| R3 | Phase 2.5 B Redis 效果 | true | 3 | 與 C3 比較，避免同時改兩個因素 |

每次執行保存：run ID、Asia/Taipei 時間、完整 commit、tracked diff、新增檔案內容與檔案雜湊、JAR／腳本雜湊、實際生效設定（不含密碼/token）、Java/MySQL/Redis/k6 版本、Docker 資源、資料初值、命令、exit code、原始輸出路徑。未提交版本須保存完整快照，只有 commit 或 git diff 不足以涵蓋 untracked 檔案。執行前驗證實際開關與嘗試次數；正式比較期間不得改 code／腳本／資源。

## 2. Phase 1.5 具體需求與通過條件

### P15-1：歷史工作負載重現（B0）

- 重跑既有兩品項與三品項腳本：40 VUs、45 秒、相同品項／排列／節奏；兩品項採歷史 seed（P001=5、P002=50、P003=20），三品項每商品 200。
- 認證與 UUID 為最新版必要適配，須列出與歷史腳本的差异；不能稱為完全相同系統的純效能 A/B。
- 每種場景執行 3 輪，使用獨立可拋棄資料庫／cache 或專屬測試資源，每輪回到相同初值。不得刪除使用者 Compose volume 來重置。
- 本輪通過：三輪均未觀察到 MySQL 1213／1205，且資料對帳一致；任何失敗分類清楚。不把「0 次觀察」寫成永不死鎖。
- 售罄屬業務拒絕，不套用 ≥95% 下單成功率；最新版預期業務拒絕以 409 表達，若仍是 500，單列 HTTP 語意缺口，不混為死鎖或宣稱 API 全通過。

### P15-2：足量庫存持續下單基準（B0）

- 另立場景：沿用三品項六排列、每品項數量 1，40 VUs；獨立 10 秒暖機，再以乾淨等量資料正式量測 45 秒，執行 3 輪。暖機與註冊請求不計入訂單指標。
- 每商品初始 100,000，測後須仍有庫存；若不足，該輪不能作持續下單基準，先調整一致初值再重跑全部比較組。
- 報告每輪及跨輪中位數／範圍：新訂單成功數、成功新訂單/秒、成功訂單 p95/p99、全部訂單 p95/p99、各類錯誤率。不得以大量快速 409 的總 req/s 代替成功下單吞吐量。
- 不臆造毫秒或吞吐量 SLO；本輪先建立可信基準。觀測到的錯誤仍須揭露，無超賣／回滾／對帳是硬門檻。

### P15-3：真實 MySQL 回歸與反向驗證

- 執行既有 12／16 執行緒併發、多品項反向順序、查詢次數及整單 rollback 測試。選定命令前確認目前測試類別，勿照抄過期檔名。
- B0 下每筆新的有效訂單只發一次商品批次 SELECT；C3/R3 另以每次交易嘗試計數，不將合理重試誤判為查詢回歸。冪等重放另列。
- 整單失敗後 order、detail、request claim 與庫存共同 rollback；最後一份庫存競爭不超賣、無孤兒明細。
- 保留原「移除排序」反向需求：僅在隔離測試副本操作，不修改主要工作樹。若最新版無法重現，記錄 null result 與測試敏感度缺口，不能拿 Phase 2.5 人工協調的真實死鎖證明排序測試有效。
- JUnit failures/errors/skipped、Maven exit code、JaCoCo 結果分開寫。窄測試 assertion 通過但 coverage gate 失敗時，不寫整體命令成功。

## 3. Phase 2.5 獨立需求

- 普通負載：B0/C3/R3 使用同一快照、三品項排列、20 VUs、20 秒、每商品 10,000、相同節奏；各 3 輪，每輪乾淨等量資料。這組與 P15 的 40 VUs／45 秒不直接比較。
- C3/R3 足量庫存的訂單成功率 ≥95%；B0 是控制組，原樣報告。原「由約 50% 提升」保留為歷史假設，不強制製造新版 50% 對照；沒有可重現提升就寫無提升證據。
- C：執行 RealDeadlockRetryIntegrationTest 的 attempts=1/3，20 個真實 InnoDB 協調衝突週期，確認 1213、回滾後才重試、總嘗試上限、實際 retry 次數與耗盡時 409 CONCURRENT_CONFLICT；其他 DB 故障仍為 500 DB_ERROR。
- C 的 HTTP 受控死鎖：Phase25K6DeadlockAcceptance attempts=1/3 成對比較；受控結果獨立於普通 k6，不能用它推算自然負載失敗率。已有 runner 的完整啟動參數需在執行工作階段確認，本次未試跑。
- B/A：真實 Redis/MySQL 驗證同 key 20 執行緒只建一單／扣一次、售罄後 replay、不同 key、重複品項、DB 失敗補償、Redis 不可用時 DB fallback；只補償已確認 reservation。
- 收斂後核對所有商品（含零庫存）的 Redis/DB。網路不確定狀態可能有 drift；降級後保持 DB-only，完成停寫／排空／DB 權威快照校正／對帳才重新開啟 Redis，不能把重啟當作自動修復。
- 保留 DB 条件扣庫存防線；feature-off 業務語意與 B0 一致。C/B 獨立修正複核已是靜態 PASS，不因補數據重做整輪審查；若 code 改變再定向複核。
- 最終一次 mvn clean test 必須含必要整合測試且成功退出，OrderService、OrderTransactionService、StockCacheService、ProductService 各 ≥80% line coverage；不得放寬門檻。顯式 k6 runner 不在預設 suite，須另有結果。

命令來源：bench/PHASE25.md；其中已列 B0/C3/R3 與真實死鎖 1/3 的顯式 Maven 指令。本次未執行任何測試命令。

## 4. 共用指標與對帳

- 成功率分母＝正式期間所有 POST /api/orders 嘗試（含 timeout/連線失敗），不含 auth、暖機、audit。成功率與新建訂單數分開；重放的 HTTP 200 不是新建。
- HTTP 400、401/403、404、409 庫存不足、409 CONCURRENT_CONFLICT、500 DB_ERROR、其他 status、無回應分桶。足量正常負載出现認證／驗證錯誤須先修測試配置，不能算有效基準。
- 另外統計 MySQL 1213、1205、SQLSTATE 45000/code 1644、實際 retry、最終耗盡；按 requestId＋attempt 去重，不能把多行 exception stack trace 當多次事件。
- 待在途交易結束後，每商品：期末庫存＝期初庫存−本輪已提交訂單明細數量；不得負值。訂單數用 before/after delta 排除 seed；成功回應 orderId 去重後對帳，timeout/回應遺失需用 requestId 追查是否已提交。
- 失敗交易沒有殘留 order/detail/claim 或 DB 庫存異動；重放不重扣。Redis 啟用且健康時，收斂後所有 stock key 等於 DB；降級／恢復場景另外紀錄差異與校正結果。
- 既有 k6「收到回應」check 不能代表業務驗收。未具備上述分類／對帳／明確判定的腳本或 runner，先標記量測工具缺口，另開小任務補足再驗收。

## 5. 結果登錄（歷史與本次嚴格分開）

本次僅讀取既有報告，不代表重新執行或獨立核實全部原始 logs。

| 證據來源／版本 | 已記錄結果 | 可以支持 | 不能支持／尚缺 |
|---|---|---|---|
| bench/RESULTS.md，36185d9 → a197192/11521c8 | 兩品項死鎖 144→148；三品項 1,345→1,212 | 早期排序修復實測，含 null result | 不是最新版本 |
| 同文件，9cd487c 歷史後續 | 40 VUs/45 秒；兩／三品項死鎖 0/0、lock timeout 0/0；各 9 個 SQLSTATE 45000/code 1644 導致的 HTTP 500 | 後續 FK 鎖順序修復已有歷史重跑；修正 Notion 漏列 | 各僅一輪；仍有 API 庫存拒絕語意問題；不證明最新版 |
| 9cd487c 三品項 | 15,376 requests，200 成功，15,167 個 409，9 個 500；總 340.94 req/s，全體 p95 5.77ms，成功 p95 1.84s | 售罄場景實測 | 總吞吐／全體 p95 不是持續成功下單效能 |
| Task 005，2026-09-15 | 59/59；Redis 20 VUs/10 秒 715/715，P002/P003 DB/Redis=285/285 | 舊短測試與兩商品 stock audit 紀錄 | 非 P15 同條件基準，非現版完整驗收 |
| Task 005／bench/PHASE25.md | 受控真實死鎖 attempts=1 0/20，attempts=3 20/20，20 retries；窄 Maven coverage exit 1 | 真實 1213 反向測試已有歷史證據 | 非普通 k6 失敗率；非整體 gate 通過 |
| Task 006 核對 9/15 artifacts | 普通 B0 836/836、C3 849/849、R3 812/812；受控 HTTP attempts=1 821 成功＋10 conflicts | 已有普通配置分組歷史數字 | 缺當前完整成對受控 HTTP 對照、重複測量与快照，不能宣稱改善 |
| Task 006，2026-09-16 07:16 | Claude 修正複核靜態 PASS；定向 15 項：2 pass、13 Docker 初始化 errors、0 failures、0 skipped，Maven exit 1 | 靜態複核完成；最近執行遭遇環境阻礙 | 未完成最新 fault/k6／clean suite／coverage；本次未探測 Docker 現況 |
| 本文件工作階段 | 配置、驗收需求、結果分開登錄；本地／Notion 文件同步 | 文件交付 | 沒有新增 runtime 證據；不勾整個 Phase 完成 |

### 最新版待填結果

| 項目 | 狀態 | 缺少證據 |
|---|---|---|
| P15-1 B0 歷史工作負載 2/3 品項，各 3 輪 | 待驗收 | 固定快照、各輪 raw summary/log、錯誤分類與 DB 對帳 |
| P15-2 B0 足量庫存 40 VUs/45 秒，各 3 輪 | 待驗收 | 暖機隔離、成功吞吐與延遲、未售罄證明 |
| P15-3 MySQL 並發／SELECT／rollback／排序反向 | 歷史已有；最新版待驗收 | 當前 test report；排序反向敏感度結果 |
| P25 B0/C3/R3 20 VUs/20 秒，各 3 輪 | 歷史已有；最新版待驗收 | 一致快照與條件、逐輪結果及 stock audit |
| P25 受控真實死鎖及 HTTP attempts=1/3 | 歷史部分已有；最新版待驗收 | 完整正反成對結果／retry／rollback 證據 |
| P25 Redis 故障／補償／replay／恢復 | 歷史部分已有；最新版待驗收 | 當前 real Redis/MySQL 證據，恢復後全商品對帳 |
| P25 獨立複核 | 靜態 PASS（Task 006） | 不代替 runtime；有 code 變更才定向複核 |
| P25 clean suite／四 service ≥80% gate | 待驗收 | 當前成功退出與覆蓋率報告 |

每輪結果固定填：配置 ID、run ID、快照 ID、條件、成功／總數、新訂單數、成功 TPS、成功及全體 p95/p99、各類錯誤、1213/1205、retry、期初／期末／應有庫存、Redis drift、exit code、原始檔案位置、判定與缺項。未量測填「未執行／未知」，不得填 0。

## 6. 下一步與追蹤

- 下一個單一任務：確認 Docker 可用，固定完整程式／設定／腳本快照，盤點 P15 量測工具是否滿足本文件；具備條件後在獨立驗證 session 執行 B0。Phase 2.5 使用同快照接續成對比較，不同時跑兩個重型工作。
- 本次僅完成文件；驗收與修復分開，出現程式缺陷先記錄具體位置和重現證據，不自動擴張實作。
- 工程概念：控制變因比較與證據分級。重試後 HTTP 成功、底層無死鎖、資料一致性是三件不同的事，需各自量測。
- Metrics：使用者一次指示要求分組記錄並同步 Notion；本次程式修復 0 輪、runtime test 0 輪、獨立 review 未執行，缺陷數不適用；模型推理級別、context、token/cost、五小時用量變化未知。耗時及同步檢查見本文件收尾紀錄。

## 7. 文件交付與同步核對 — 2026-09-16 07:42 Asia/Taipei

- 本地變更共三份：本文件、docs/project-state.md、bench/PHASE25.md；保留既有程式與文件變更，未 commit/push/merge。
- 已直接更新 Notion 表格列的具體需求、驗收結果、狀態及相關 checklist，非僅頁尾 Log：
  - [提示詞整理／階段工作表](https://app.notion.com/p/3c2708da9f9280b083d3f000ff381579)
  - [進度追蹤](https://app.notion.com/p/3c4708da9f9280d89606c93c3a6d3e53)
  - [執行順序與策略](https://app.notion.com/p/3d7708da9f928173be92dfc94182db9f)
  - [Phase 1.5](https://app.notion.com/p/3d7708da9f9281da882aefa94be1d6d3)
  - [Phase 2.5](https://app.notion.com/p/3d8708da9f9281bda389d0599e1499e7)
  - [執行計劃](https://app.notion.com/p/3d4708da9f9281dc8b3ddb0423cbc0ad)
- 六頁均已回讀；核對配置、負載、歷史數字、最新未完成項、表格三個欄位與 checklist 一致。無未同步項目。
- 本地 git diff --check 通過（僅既有 LF/CRLF 提示）；另讀回三份文件檢查 B0/C3/R3、待驗收表與行尾空白（0）。未追蹤文件不受一般 git diff 涵蓋，已另外檢查。
- 驗證腳本最初把 P25 的 80% 字串錯套為 P15 頁的必要欄位，調整為按頁面職責檢查後通過；無文件內容修復。約 12 分鐘，工具呼叫數精確值未知；無額外使用者介入／scope expansion，runtime test 0 輪。
- Goal: 已完成分組驗收需求、既有結果／缺項與本地／Notion 同步。
- Remaining/Risks: 最新版測試與壓測尚未執行；最近 Docker 阻礙是既有紀錄，本次未重查；歷史結果不等於當前驗收。
- Next: 確認 Docker、固定完整快照、核對 B0 量測工具，接續獨立驗證工作。
