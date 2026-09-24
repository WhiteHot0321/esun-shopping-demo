# Task 032 — Phase 3.1 #6 個人資料編輯收尾

## Session gate

- 任務分類：Small，既有功能狀態與驗證證據收尾。
- 分支／基線：`advanced-v2` @ `8e6cd20`，與 `origin/advanced-v2` 一致。
- 目標：確認 #6 已整合、重跑直接相關後端與前端測試，並同步本地與 Notion 狀態。
- 排除：production code 變更、功能擴充、avatar／bio／birth date、commit／push／merge。

## Result

- #6 實作已包含於 feature commit `6c79815`，並由 merge commit `10c642c` 納入
  `advanced-v2`；先前「未提交」狀態已過時。
- 現有契約：JWT 驗證 email 決定唯一更新目標；`GET/PUT /api/member/profile`；email
  唯讀；display name／phone 可清空為 `NULL` 且有長度／格式驗證；前端會員中心可載入及儲存。
- 2026-09-24 後端：`mvn -q -Dtest=MemberProfileIntegrationTest test`，2/2 PASS，exit 0，
  使用 Docker Desktop 28.4.0 與真實 MySQL Testcontainers。Ollama 缺少
  `nomic-embed-text` 只產生非阻擋 startup warning，未影響測試結果。
- 2026-09-24 前端：`npm test -- --run src/App.spec.js`，checkout 3/3、Vitest 26/26 PASS，
  exit 0；其中 profile UI 2 案例涵蓋載入／更新、email 不可編輯與無效電話不送出。
- 既有 2026-09-19 證據：production build、`git diff --check`、JaCoCo gate PASS。
- 2026-09-24 populated legacy-DB migration drill PASS（disposable MySQL 8）：
  - 升級前建立不含 `display_name`／`phone` 的 legacy `member` schema，寫入 2 筆既有
    BUYER／SELLER；既有欄位 MD5 為 `01e07ff0fad53a234a9c9ea270bd6b5f`。
  - 首次執行 `06_member_profile.sql` 後仍為 2 筆且 MD5 不變；新增
    `display_name varchar(100) NULL`／`phone varchar(30) NULL`，兩筆既有資料新欄位皆為 NULL。
  - 寫入一筆 profile 後再次執行同一 migration 成功；欄位仍恰為 2 個，既有欄位 MD5
    不變，已寫入 profile 與另一筆 NULL 值皆保持不變，證明 populated upgrade 與 no-op 重跑。
  - 一次性容器 `esun-profile-migration-drill-20260924` 已刪除並確認不存在。
- 後續 Phase 3.1 merged-tree full regressions另提供廣泛回歸證據。

## Engineering concept

個人資料更新的核心不是表單，而是 ownership 邊界：更新對象只能由已驗證 JWT principal
推導，不能信任 request body 內的 email 或 member ID。可選欄位則在 API 邊界正規化，避免
空字串與 `NULL` 混用形成多種資料語意。

## Metrics

- 執行者：Codex；使用者介入：1 次（開啟 Docker）。
- 修復輪數：0；production/test code 變更：0。
- elapsed time、token／cost、五小時 usage delta：unknown。
