# Task 024 — Phase 3.0 #4 備份與恢復演練

## Session gate

- Goal: 建立可重跑的 MySQL 備份／還原工具，並完成一次不破壞來源資料的恢復演練。
- Baseline: `feature/frontend-ux-revamp` @ `6ae06414a884aff7dd173fa7b2f52ecf4213ad8a`。
- Scope: `scripts/mysql-backup-restore.ps1`、本結果文件、手動測試手冊及 Phase 3 進度列。
- Exclusions: 不修改應用程式、schema、既有資料，不 commit／push／merge，不把 `.env` 或密碼寫入證據。
- Risk: Medium；Restore 會重建目標 DB，因此要求 `-Force`，且預設拒絕覆寫來源 DB。Drill 只操作獨立暫存 DB。

## Acceptance

- [x] `Backup` 透過容器內 `mysqldump` 產生完整 SQL（single transaction、routines、triggers、events、utf8mb4）。
- [x] `Restore` 要求明確 target、backup file 與 `-Force`；預設不得覆寫來源 DB。
- [x] `Drill` 自動執行「來源備份 → 暫存 DB 還原 → 模擬損毀 → 重建還原 → SHA-256 完整比對」。
- [x] Drill 的 `finally` 一律刪除暫存 DB，不更動來源 DB。
- [ ] 排程與異地／離線保存：本階段只交付可手動重跑版本，待實際部署環境決定排程器與 retention。

## Result

**實際執行日期：2026-09-18，Claude Code（MODE: IMPLEMENT）。Container：`esun-mysql`（Up，執行中）。**

### 執行前發現並修正的缺陷（腳本此前從未真正執行過，只做過靜態審查）

1. **反引號被誤用於 shell 命令列參數，觸發 sh 命令替換。** `New-DatabaseDump`／`Restore-Database`／
   `Invoke-MySql` 把 `` `$Database` ``（MySQL SQL 識別碼跳脫語法）套用到 `mysqldump`/`mysql` command
   line 的資料庫名稱參數上——但那個位置是 **shell 參數**，不是 SQL 文字。反引號在 POSIX `sh` 裡代表
   command substitution，實際執行時變成 `` `esun_shop` `` 被當成指令執行，導致
   `esun_shop: command not found`。修正：在純 shell 參數位置移除反引號（`$Database` 已由
   `ValidatePattern('^[A-Za-z0-9_]+$')` 驗證過，本就不需要額外跳脫）；SQL 文字內（如
   `DROP DATABASE `` `$Database` ``;`）維持反引號，那裡才是正確用法。
2. **`-p"$MYSQL_ROOT_PASSWORD"` 觸發的 stderr 警告被 PowerShell 5.1 誤判為終止性錯誤。**
   `mysqldump`/`mysql` 對命令列密碼會印出 `Using a password on the command line interface can be
   insecure` 警告（寫到 stderr）；腳本用 `2>&1` 合併輸出，且設了 `$ErrorActionPreference = 'Stop'`，
   Windows PowerShell 5.1 對原生程式的 stderr 逐行包成 NativeCommandError，即使指令本身 exit 0 也會
   被視為失敗。改用 `MYSQL_PWD` 環境變數傳密碼（同時也更安全——不會出現在 `ps`／docker inspect 的
   command line 裡），此警告完全消失。
3. **`Invoke-MySql` 內嵌 `-e "SQL"` 字串在 Windows 上被 .NET 原生程序引數編碼二次跳脫，破壞原本手刻
   的 `sh -c` 跳脫序列。** 這是 Windows 專屬問題（同樣邏輯若在 Linux/WSL 下用 bash 直接呼叫
   `docker exec` 反而是對的，之前用 Bash 工具重現時就沒發現）。修正：改成先把 SQL 寫入本機暫存檔、
   `docker cp` 進容器、再用 `< file` 匯入——和既有 backup/restore 的檔案傳輸方式一致，完全不需要
   內嵌引號跳脫。
4. **Drill 原本用「整份 dump 的 SHA-256」比對，對一次完全正確的還原也會誤判失敗。** 診斷：來源
   dump 與「還原→再 dump」的還原結果逐行 diff，42 行差異全部是欄位層級的
   `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` vs. `COLLATE utf8mb4_unicode_ci` 這種
   註記差異（MySQL 對「欄位 collation 是否為 CREATE TABLE 當下顯式解析」的內部記錄，會因
   建表路徑不同而不同，即使宣告的 collation 完全相同），8/8 張表的 `INSERT` 資料列
   **逐位元組完全相同**，資料表清單也完全相同。這個差異與資料或結構正確性無關，是 dump 呈現格式的
   良性差異。修正：Drill 的通過／失敗判定改成「資料內容 dump（`--no-create-info`）SHA-256」＋
   「資料表清單排序後比對」，兩者皆需相符才算通過；同時仍保留完整結構 dump 存成證據檔供人工檢視。
   全部 `scripts/mysql-backup-restore.ps1` 的變更都是這四項修正，未變更備份檔本身的格式或內容。

### 執行紀錄

**Backup**（對真實 `esun_shop`）：
```
& .\scripts\mysql-backup-restore.ps1 -Action Backup
BACKUP_OK file=C:\GitHub\esun-shopping\backups\esun_shop-20260918-103723.sql
  sha256=41A1DDE0A6794CF2C38C25F4F8985FD695C8110751E493C191A7B3AB3FC35004
```

**Restore 安全防護驗證**（皆為預期失敗，未變更任何資料）：
- 無 `-Force`：`Restore recreates database 'esun_backup_test'. Pass -Force after confirming the target.`
- 有 `-Force` 但目標＝來源、無 `-AllowSourceOverwrite`：
  `Refusing to overwrite the source database. Pass -AllowSourceOverwrite only during an approved outage.`

**Restore 正常路徑**（還原到獨立暫存 DB `esun_backup_test`，非來源）：
```
& .\scripts\mysql-backup-restore.ps1 -Action Restore -TargetDatabase esun_backup_test `
  -BackupFile "backups\esun_shop-20260918-103723.sql" -Force
RESTORE_OK database=esun_backup_test file=C:\GitHub\esun-shopping\backups\esun_shop-20260918-103723.sql
```
還原後 `SHOW TABLES` 確認 8 張表齊全（doc_embedding, faq, member, order_detail, order_request,
payment_transaction, product, shop_order），人工驗證後手動 `DROP DATABASE esun_backup_test;` 清理，
來源 `esun_shop` 全程未被觸碰。

**Drill 完整演練**（來源備份 → 暫存 DB 還原 → 模擬損毀 → 重建還原 → 驗證 → 清理，一次執行到底）：
```
& .\scripts\mysql-backup-restore.ps1 -Action Drill
DRILL_OK source=esun_shop temporary=esun_restore_drill_20260918104356
  data_sha256=212DA67A4CF623C9059EBF61CF2728999A1DDCDDB862BBDD1B0CC30F37473E32
  full_sha256=41A1DDE0A6794CF2C38C25F4F8985FD695C8110751E493C191A7B3AB3FC35004
  tables=doc_embedding,faq,member,order_detail,order_request,payment_transaction,product,shop_order
  evidence=C:\GitHub\esun-shopping\backups\drill-20260918104356
```
`full_sha256` 與獨立執行的 Backup 結果一致（同一來源狀態的兩次 dump 互相印證）。損毀步驟（刪除
`doc_embedding` 一列 + 新增 `__restore_drill_damage` 表）確實讓損毀後的資料雜湊與資料表清單同時偏離
來源，證明偵測邏輯有效，而非永遠判定「有差異」。

**清理驗證**：Drill 結束後 `SHOW DATABASES` 只剩 `esun_shop` 與系統資料庫，容器內
`/tmp` 無殘留 `esun-*` 暫存檔；`esun_shop.shop_order` 列數（12 筆）與演練前一致，證實來源資料庫全程
未被寫入。證據檔案：`backups/esun_shop-20260918-103723.sql`（獨立 Backup）、
`backups/drill-20260918104027/`（首次除錯用的 Drill，含已發現的雜湊誤判）、
`backups/drill-20260918104356/`（修正後、通過驗證的最終 Drill，含 `*.data.sql` 資料內容 dump 與
`*.sql` 完整結構 dump）。這些檔案含真實會員/訂單資料，已將 `backups/` 加入 `.gitignore`，不會被提交。

### 補充：手動測試手冊

`docs/MANUAL_TESTING_GUIDE.md` 原本在總覽列出「備份與恢復演練」為第 4 種測試方式，但沒有對應章節；
已新增「第九步：備份與恢復演練」，涵蓋 Backup／Restore 安全防護／完整 Drill 三個子場景與預期輸出。

## Engineering concept

備份成功只代表「檔案被寫出來」；可恢復性必須由隔離還原與內容一致性檢查證明。演練把破壞限制在暫存資料庫，並用 deterministic 的內容雜湊驗證資料與結構，而非只比較幾個表的筆數。這次實際執行也印證了另一個常被忽略的教訓：「靜態審查通過」不等於「可以動」——四個缺陷（shell 反引號誤用、密碼警告被誤判為錯誤、Windows 原生引數二次跳脫、dump 格式的良性差異被當成資料損壞）全部只在真正跑過一次完整演練後才浮現，光看程式碼是看不出來的。

## Trade-offs

Drill 的通過條件從「整份 dump SHA-256 相同」改成「資料內容 dump SHA-256 相同 + 資料表清單相同」，對純 DDL 層級的損壞（例如某欄位型別被悄悄改掉但資料還在）敏感度略低於逐位元組全文比對；但原本的全文比對連一次乾淨的還原都會誤判失敗，兩害相權，選了不會誤報的版本。完整結構 dump 仍保留為證據檔，需要時可以人工或另外寫腳本比對。

## Test Result

三個子場景（Backup、Restore 安全防護、完整 Drill）均實際對 `esun-mysql` 容器執行並通過，見上方「執行紀錄」。未執行：對已存在很久的 `esun-mysql` 容器做真正的「容器摧毀後從備份檔重建全新容器」演練（本次 Drill 用暫存 DB 模擬損毀，未涉及容器層級的災難恢復）；排程／異地保存機制的驗收本就排除在此輪範圍外。

## Metrics

- Task class: Medium
- User interventions: 0（截至本次演練執行）
- Repair rounds: 4（見上方「執行前發現並修正的缺陷」1-4 項，皆為實際執行時發現的獨立根因，非同一修正的重試）
- Valid review defects: 不適用（此 Medium 維運腳本依規格不強制獨立審查；上述缺陷由執行者自行執行演練發現並修正）
- Token／cost、五小時 usage delta: unknown
