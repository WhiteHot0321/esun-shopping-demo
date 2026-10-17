# 073 維護者介面 demo 切片提示詞（M0 會員管理 + M4 營運儀表板）

**日期**: 2026-10-17
**來源**: `068-maintainer-console-plan.md`、`069-maintainer-console-prompts.md`
**目標**: 最短路徑做出可 demo 的維護者介面：管理員在畫面上管理會員（升級賣家、停用、撤銷舊 token），並看到營運儀表板。
**順序**: Session 1 = M0（Phase 4.#3）→ Session 2 = M4（Phase 4.#5）。兩者都會改 `App.vue` 的 ADMIN 區，**不要同時 B 階段**；M4 的 A（只讀）可與 M0 並行。

## 使用規則
- 通用規則同 069「使用規則」：先讀 `docs/project-state.md`、`AGENTS.md`；分支從 `advanced-v2` 切 `feature/phase4-<n>-<slug>`；A/B/C 分開下；超出範圍的發現寫進 task 檔 Follow-up。
- **編號修正**：069 的 Phase 4.#3 寫的 task 檔 `docs/tasks/072-phase4-3-member-admin.md` 已被 ECPay runbook 佔用（072）。本切片一律改用 **073-** 之後的編號：M0 → `docs/tasks/074-phase4-3-member-admin.md`，M4 → `docs/tasks/075-phase4-5-ops-dashboard.md`。
- 下面 M0 各階段提示詞 = 069 的 Phase 4.#3 同名階段，**把其中所有 `docs/tasks/072` 換成 `docs/tasks/074`**。

---

## Session 1 — Phase 4.#3 M0 會員管理 + token 撤銷（Critical）

### A 定位（只讀）
```
任務：Phase 4.#3 A 階段，只讀。先讀 docs/project-state.md、AGENTS.md、docs/tasks/068 與 docs/tasks/069 的「Phase 4.#3 — A 定位」段落，
照該段 1–7 點逐項確認，結果寫進 docs/tasks/074-phase4-3-member-admin.md（不是 072，072 已被佔用）。
額外確認兩件事：
(a) 目前最新的 Flyway 版本與 backend/DB 最大編號（V2 與 16_ 是否仍可用，近期有新 migration 的話順延）。
(b) 前端 App.vue 現在的 ADMIN 區塊結構，MemberAdminPanel 要掛在哪、與 M4 儀表板（之後會掛在同區）如何共存。
不改程式碼、不跑測試。
```

### B 實作
```
任務：Phase 4.#3 B 階段。依 docs/tasks/074 已確認設計實作，範圍、可改／不可改檔案、測試清單、要跑的命令
全部照 docs/tasks/069「Phase 4.#3 — B 實作」段落（把 072 讀成 074）。
分支：feature/phase4-3-member-admin（從 advanced-v2 切）。
完成後：mvn clean test 與 npx vitest run 結果寫進 074；用 built-in browser 走一遍「升級賣家 → 舊 token 失效 → 重新登入」並截圖；更新 docs/project-state.md。
不 merge、不 push 到 advanced-v2。
```

### C 驗收（獨立審查，另開 session，最好換 Codex）
```
任務：Phase 4.#3 C 階段，獨立唯讀審查（Critical，非作者 agent）。
內容照 docs/tasks/069「Phase 4.#3 — C 驗收」段落；設計段落讀 docs/tasks/074。
範圍：git diff advanced-v2...feature/phase4-3-member-admin。不修改程式碼；回報 PASS/FAIL + 每個 finding 的 file:line 與失敗情境。
```

---

## Session 2 — Phase 4.#5 M4 營運儀表板（風險 Low，單一 agent）

**要解的問題**：維護者沒有一個畫面能一眼看到「今天店面狀況」。重用既有資料，不新增寫入路徑。

### A 定位（只讀，可與 M0 並行）
```
任務：Phase 4.#5 A 階段，只讀。先讀 docs/project-state.md、AGENTS.md、docs/tasks/068 的 M4 段落。
請確認並寫進 docs/tasks/075-phase4-5-ops-dashboard.md：
1. 要顯示哪些指標（建議：今日訂單數與營收、各 payStatus 筆數、付款失敗數、低庫存商品清單〔門檻可設定〕、Redis 庫存快取狀態）。
2. 資料來源二選一並說明理由：DB 聚合查詢 vs Micrometer counters。注意 shop.orders.* / shop.payments.* 是 process-local，重啟歸零，
   不適合「今日」數字；Redis 狀態來自 StockCacheService snapshot。判斷哪些指標只能走 DB。
3. 端點：GET /api/admin/dashboard（ADMIN-only，沿用 CouponController.requireAdmin 模式），回應 shape 與時區（Asia/Taipei 的「今日」邊界）。
4. 成本：查詢是否走索引（參考 bench/sqlperf 與 docs/tasks/062 的基準方法）；要不要沿用 PublicRecommendationCache 類似的短 TTL（建議 10–30 秒，需講明延遲）。
5. 取消／軟刪除訂單是否計入營收；金額一律 BigDecimal。
6. 前端：App.vue ADMIN 區如何放入口（與 M0 MemberAdminPanel 共存），新增 components/OpsDashboardPanel.vue。
可讀：controller/CouponController.java、controller/OrderController.java、repository/OrderRepository.java（或等價）、repository/PaymentRepository.java、
      service/StockCacheService.java、service/PublicRecommendationCache.java、frontend/src/App.vue、frontend/src/stores/auth.js、docs/tasks/062。
不改程式碼、不跑測試。
```

### B 實作
```
任務：Phase 4.#5 B 階段，依 docs/tasks/075 已確認設計實作，不擴大。分支 feature/phase4-5-ops-dashboard（從 advanced-v2 切；若 M0 已 merge 先同步）。
可改：新 controller/OpsDashboardController.java、新 service/OpsDashboardService.java、必要的 repository 唯讀查詢方法、
      前端新 components/OpsDashboardPanel.vue + App.vue 的 ADMIN 入口、對應測試。
不可改：訂單／付款／庫存的寫入路徑、schema（本項不需要 migration；若發現需要，停下來寫進 Follow-up）。
測試（RED → GREEN）：
- 非 ADMIN → 403；未登入 → 401
- 造資料（今日／昨日訂單、已取消、各 payStatus、低庫存）後，各指標數字正確（真 MySQL 整合測試，含邊界：午夜 Asia/Taipei）
- 營收不含已取消訂單（依 075 決定）
- Redis 停用時回應仍成功並標示狀態
- Vitest：面板載入、空資料、錯誤狀態
要跑：mvn '-Dtest=<新 OpsDashboard 測試>' '-Djacoco.skip=true' test；通過後 mvn clean test；cd frontend && npx vitest run。
用 built-in browser 以 ADMIN 登入走一遍儀表板並截圖。完成後更新 docs/project-state.md。不 merge。
```

### C 驗收（Low 風險，作者外的快速唯讀審查即可）
```
任務：Phase 4.#5 C 階段，唯讀審查。範圍 git diff advanced-v2...feature/phase4-5-ops-dashboard 與 docs/tasks/075。
重點：ADMIN 授權是否每條路徑都擋、查詢是否可能掃全表、時區邊界、金額精度、回應是否洩漏個資（email、地址）。
PASS/FAIL + file:line + 失敗情境，不改程式碼。
```

---

## Demo 腳本（兩項都完成後）
1. 以 ADMIN 登入 → 儀表板顯示今日營收、低庫存、Redis 狀態。
2. 會員管理搜尋一個 BUYER → 升級為 SELLER → 該使用者舊 token 立即失效，重新登入後看得到賣家後台。
3. 停用該帳號 → 無法登入；稽核日誌看得到三筆操作且不含 password_hash。
