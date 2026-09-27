# 042 圖片上傳併發修復

- MODE: IMPLEMENT；基準 advanced-v2 / 35099232a5bb90e8c410b5fb1e5a6f0f10c0770b。
- 目標：同商品並行上傳不得超過 10 張、不得重複排序；等待鎖後須重新確認存在與所有權。
- 範圍：ProductService、圖片上傳單元測試、新增真實 MySQL 併發整合測試、相關進度文件。排除其他模組、schema、commit/push。
- 風險：交易、REPEATABLE READ 快照與授權狀態。實作者 Codex；獨立審查 Claude Code（PASS）。
- 驗收：受控競爭重現原始失敗；修正後 9+2 僅一成功、8+2 均成功且排序唯一；鎖等待期間刪除/移轉所有權拒絕寫檔。
- 驗證：先執行新回歸案例，再執行圖片相關測試，獨立審查後最終完整 backend 驗證。
- 保留所有既有未提交修改；本任務修復、測試、獨立審查已完成，未 commit／push／merge。

## 已執行證據（2026-09-26 Asia/Taipei）

1. RED：未修改 production code，執行 `mvn '-Dtest=ProductImageConcurrencyIntegrationTest#concurrentUploadsCannotExceedTenImages' '-Djacoco.skip=true' test`。1 test / 1 failure / 0 errors / 0 skipped，exit 1，38.498 秒。預期 HTTP `[200, 400]`，實際 `[200, 200]`。透過真實 MySQL `performance_schema.data_lock_waits` 確認兩個交易均等待外部商品列鎖後才釋放鎖，並非機率式睡眠測試。
2. 修正：上傳交易中的第一個資料庫讀取改為商品 `FOR UPDATE`，取得鎖後才驗證 active/owner、COUNT、寫檔、分配排序、插入 metadata 與 audit。重用既有 Repository，不改 schema 或其他商品操作行為。
3. GREEN：`mvn '-Dtest=ProductImageConcurrencyIntegrationTest,ProductServiceTest,ProductManagementIntegrationTest,AuditLogIntegrationTest' '-Djacoco.skip=true' test`：37/37，0 failures/errors/skipped，exit 0，54.258 秒。分別為 4、21、3、9 tests。窄測明確略過全域 coverage gate。
4. 新增四案：9+2 一成功一 400；8+2 均成功且 display_order 連續唯一；等待鎖時軟刪除回 404；等待鎖時 ownership 改變回 403。均檢查 DB 圖片、實體檔案增量及 audit 數量；既有 metadata 失敗清檔單元案例保留。測試初始圖片為直接寫入的 metadata fixtures，不宣稱為瀏覽器上傳。
5. `git diff --check` PASS（只有既有 CRLF 正規化提示）。最終 `mvn clean test`：**250/250**，0 failures/errors/skipped，exit 0，3:13 min，所有 JaCoCo coverage checks PASS；包含 ProductImageStorageServiceTest 7/7 及新增 concurrency cases 4/4。Surefire / JaCoCo 報告位於 `backend/target/`。未重跑 frontend／瀏覽器 E2E（無前端變更）；既有 Ollama 模型缺少/fallback 警告不是此修復範圍。

## 獨立審查

- Claude Code，唯讀 runner `scripts/invoke-claude.ps1`，先需求與五份 core source/test，再讀四份 Surefire 報告；沒有修改 repository 或另外執行測試。結論 **PASS**，有效阻擋缺陷 0。審查契約見 `042-image-upload-concurrency-review.md`。
- 核對 transaction boundary、locking read 在 snapshot read 前、鎖後 owner/deleted 驗證、數量及 display_order 序列化、拒絕無檔案/metadata/audit，以及既有 metadata 失敗清檔。
- 審查證據：`.git/codex-claude-runs/50ad705d-c774-446d-aa0b-c37f3ac097d9/result.json`（本地、不提交）；145.634 秒、15 turns、工具回報成本約 USD 0.31。
- 審查範圍限制：每次最多 5 張由未納入五檔審查的 `ProductImageStorageService.java:33` 實作，Codex 已核對 `ProductImageStorageServiceTest.java:93-99` 的 6 張拒絕案例，納入最後完整 suite，不將其宣稱為 Claude 的審查結論。
- 已知取捨：同商品列鎖包含磁碟 I/O；未改變原有檔案/DB 非原子性的 crash 或 commit-failure 清理限制。這些不是本次併發修復新增的問題。未承諾外層已建立 snapshot 的未來交易呼叫者或直接 DB 寫入會受此 Service 協定保護。

工程概念：MySQL REPEATABLE READ 的一般 SELECT 可能建立舊快照。先鎖商品列、再第一次查圖片數，才能讓下一個上傳交易看見前一個提交後的數量；只鎖 INSERT 或在 COUNT 之後上鎖不足以守住總數。

## 收尾／量測

- 2026-09-26 09:18 Asia/Taipei 收尾；任務檔建立至收尾約 10 分鐘（不含此前定位，精確整回合時間未知）。Critical bounded implementation + requested independent review；未啟動第二模組或架構重構。
- 三次 Maven：預期 RED、針對性 GREEN、一次 final clean suite；同一修復實作 1 輪、審查返工 0 輪、有效新審查缺陷 0；使用者介入：本輪開頭明確 IMPLEMENT 授權，執行途中 0 次。
- Codex model/reasoning、起迄 context、實際 token/cost、五小時用量差及精確工具次數未知。Claude runner metrics：input 14、cache creation 38,045、cache read 169,149、output 12,683（含 thinking 9,535），不可解讀為整任務總用量。
- 3 個程式／測試檔變更：ProductService、ProductServiceTest、新 ProductImageConcurrencyIntegrationTest；Repository／schema 未改。新增本任務與 review contract，更新既有 project-state；保留 pom 與其他既有修改。
- 依 esun-shopping-notion skill 同步並回讀 6 頁：進度追蹤、歷史提示詞「階段工作」第 8 列的驗收／狀態、執行順序與策略 #10、Phase 3.1 #10/checklist、Phase 3 索引 #10 列、#10 任務頁。全部一致為已測試／獨立審查 PASS／未提交。
- 下一步：僅在使用者明確要求時提交本次變更。更大的「所有先前 code 完善」目標不因此宣稱全部獨立審查完成。
