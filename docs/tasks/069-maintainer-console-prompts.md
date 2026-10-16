# 069 維護者視角提示詞（Phase 4.#1–#4）

**日期**: 2026-10-16
**來源規劃**: `068-maintainer-console-plan.md`
**狀態**: 提示詞已備妥，全部 ⏳ 未開始
**執行順序**: Phase 4.#1（M1）→ Phase 4.#2（M2）→ Phase 4.#3（M0）→ Phase 4.#4（M3）

## 使用規則（每份都適用，不在各段重複）

- 開始前先讀 `docs/project-state.md`、`AGENTS.md`；分支依 phase2-branching-strategy：每項一條從 `advanced-v2` 切出的 `feature/phase4-<n>-<slug>`。
- 每項拆成 **A 定位 / B 實作 / C 驗收**，三個階段分開下提示詞（不同 session 或不同回合）；重型階段不要並行。
- A 只讀、只產出 task 檔中的「已確認範圍 + 待決事項」，不改程式碼。B 只做 A 確認過的範圍。C 另開 session，只看 diff 與測試結果，不從頭再審。
- 各段的「可讀 / 可改 / 要跑」是硬限制；超出範圍的發現寫進 task 檔的 Follow-up，不要順手做。
- **Schema 變更的雙路徑陷阱**（M0、M3 會碰到）：dev compose 走 `backend/DB/NN_*.sql` initdb 鏈，prod 走 Flyway `db/migration/`；目前只有 `V1__baseline_schema.sql`。新欄位必須同時加：新的 `backend/DB/16_*.sql` 與 `V2__*.sql`（不要改 V1）。`FlywayAdoptionConfiguration` 的釘選 fingerprint 描述的是**舊版 legacy schema**，A 階段要確認 V2 不會讓 `FlywayAdoptionIntegrationTest` 失敗、以及 adopt 後 V2 會正常套用。
- 完成後依 CLAUDE.md 的 Documentation template 寫筆記（可用 `/note`），並更新 `docs/project-state.md`。

---

## Phase 4.#1 — M1 開發用測試帳號快速切換（風險 Low，單一 agent）

**要解的問題**：手動測試要在 buyer/seller/admin 間來回登出登入，而且升級賣家目前只能改 SQL。

**關鍵事實**：專案**沒有 `dev` profile**，預設 profile 就是開發環境；`@Profile("prod")` 只用在 `ProductionConfigValidator`。所以開關要用屬性（例如 `shop.dev-tools.enabled`，預設 `false`），不要依賴不存在的 profile。

### A 定位
```
任務：Phase 4.#1 A 階段，只讀，不改任何檔案（task 檔除外）。
目標：決定開發用帳號切換的開關與 seed 方式。
請確認並寫進 docs/tasks/070-phase4-1-dev-login-as.md：
1. 開關：屬性名稱與預設值（預設必須 false）；用 @ConditionalOnProperty 讓 controller 在關閉時不存在（404），
   並確認 ProductionConfigValidator 在 prod 開啟時拒絕啟動。
2. 端點：POST /api/dev/login-as {email} 只允許登入已存在、且在白名單內的 seed 帳號，回傳與 /api/auth/login 相同的 AuthResponse。
   JwtAuthFilter.isPublicRoute 是否需要加這條（需要：未登入也要能用）——加上後端點關閉時仍是 404，不是 401，確認這點。
3. Seed：三個帳號（buyer/seller/admin）放在哪裡（backend/DB/02_data.sql？還是開關開啟時啟動建立？），密碼不得寫進 repo 明碼。
4. 前端：只在 import.meta.env 開發旗標下顯示的下拉選單，放在 AuthPanel 還是頂欄。
5. 待決事項列給使用者。
可讀：security/JwtAuthFilter.java、security/JwtService.java、service/AuthService.java、controller/AuthController.java、
      config/ProductionConfigValidator.java、application.yml、application-prod.yml、backend/DB/02_data.sql、
      docker-compose.yml、frontend/src/components/AuthPanel.vue、frontend/src/stores/auth.js
不跑測試。
```

### B 實作
```
任務：Phase 4.#1 B 階段，依 docs/tasks/070 已確認範圍實作，不擴大。
可改：新 controller/DevLoginController.java（或 070 決定的名稱）、JwtAuthFilter.isPublicRoute、
      ProductionConfigValidator（+ 拒絕規則）、application.yml（預設 false）、070 決定的 seed 位置、
      AuthPanel.vue 或 070 決定的前端元件、對應測試檔。
不可改：AuthService 登入邏輯、JwtService、任何其他 controller。
測試（先寫會失敗的再實作）：
- 開關關閉：POST /api/dev/login-as → 404（不是 401/403）
- 開關開啟：白名單帳號拿到可用 token；非白名單 email → 403/404（依 070）
- ProductionConfigValidatorTest：prod + 開關開啟 → 啟動失敗，訊息只提設定名不提值
- Vitest：非開發 build 不渲染下拉選單
要跑：mvn '-Dtest=ProductionConfigValidatorTest,JwtAuthFilterTest,<新測試>' '-Djacoco.skip=true' test；cd frontend && npx vitest run <相關 spec>
```

### C 驗收
```
任務：Phase 4.#1 C 階段，唯讀。只看 git diff advanced-v2...HEAD 與 B 的測試輸出。
檢查：預設關閉、prod 拒絕、關閉時 404、沒有明碼密碼進 repo、diff 沒超出 070 範圍。
結論寫 PASS/FAIL + 具體 file:line，不修改程式碼。
```

---

## Phase 4.#2 — M2 以自己帳號預覽角色畫面（風險 Low，單一 agent，前端為主）

**要解的問題**：ADMIN 想確認「買家/賣家看到的畫面長怎樣」，不想另開帳號。
**誠實邊界**：這只是 UI 透鏡。後端權限完全不變（ADMIN 本來就通過所有 seller 檢查），文件與 UI 文字都要寫清楚。

### A 定位
```
任務：Phase 4.#2 A 階段，只讀。
請確認並寫進 docs/tasks/071-phase4-2-role-preview.md：
1. 目前所有依角色顯示的地方（App.vue 的 isSeller/isAdmin、ShopWorkspace、ProductManagement、OrdersPanel、ProductReviews 等），列成清單。
2. 設計：auth store 新增 viewAs（null | 'BUYER' | 'SELLER'），新增 effectiveRole computed；只有真實 role === 'ADMIN' 才能設定；
   logout 與 setSession 時清除；是否持久化到 localStorage（建議不要，重新整理就恢復）。
3. 哪些元件要從讀 role 改成讀 effectiveRole；有沒有地方「必須」繼續讀真實 role（例如結束預覽按鈕本身）。
4. 預覽中橫條的位置與文字（需含「僅畫面預覽，權限未改變」）。
可讀：frontend/src/stores/auth.js、frontend/src/App.vue、frontend/src/components/*.vue（只看角色判斷）、App.spec.js
不跑測試。
```

### B 實作
```
任務：Phase 4.#2 B 階段，依 docs/tasks/071 實作。
可改：frontend/src/stores/auth.js、App.vue、071 清單內的元件、新 RolePreviewBanner.vue（如需要）、對應 spec。
不可改：任何後端檔案、api.js 的請求邏輯。
測試：
- 非 ADMIN 呼叫 setViewAs 無效
- ADMIN 預覽 BUYER：優惠券/稽核/賣家面板全部隱藏，橫條出現；結束後恢復
- ADMIN 預覽 SELLER：賣家面板顯示、ADMIN 面板隱藏
- logout 後 viewAs 歸零
要跑：cd frontend && npx vitest run（全量，前端測試很快）；npm run build
最後用 built-in browser 走一遍 ADMIN → 預覽買家 → 預覽賣家 → 結束，截圖。
```

### C 驗收
```
任務：Phase 4.#2 C 階段，唯讀。只看 diff 與 B 的 vitest/build 輸出、截圖。
檢查：沒有任何後端變更、非 ADMIN 不可觸發、真實 role 沒被覆寫進 localStorage、UI 有「權限未改變」字樣。
PASS/FAIL + file:line。
```

---

## Phase 4.#3 — M0 會員管理 + token 撤銷（風險 Critical：權限變更 → 需 Codex/Claude 獨立審查）

**要解的問題**：(1) 升級賣家只能改 SQL；(2) `JwtAuthFilter` 只信 token 裡的 role claim、不查 DB，所以改角色或停權後舊 token 在過期前（預設 1 小時）仍有效。M3 的模擬登入要能被撤銷，必須先有這個機制。

### A 定位
```
任務：Phase 4.#3 A 階段，只讀。這是 Critical 項目，A 階段要把設計與風險講清楚。
請確認並寫進 docs/tasks/072-phase4-3-member-admin.md：
1. 撤銷機制選型：member.token_version INT（JWT 帶 ver claim，filter 比對 DB）vs 只靠短效 token vs 黑名單表。
   給出推薦與理由；若選 token_version，決定「每請求查 DB」或「短 TTL 本機快取」，以及快取造成的撤銷延遲上限。
   評估對 HighConcurrencyOrderIntegrationTest / k6 路徑的額外查詢成本（OrderServiceQueryCountIntegrationTest 是否會受影響）。
2. member.status（ACTIVE/DISABLED）：停用帳號在 login 與 filter 兩處都要擋；回應碼（401 vs 403）。
3. 端點：GET /api/admin/members（email 前綴搜尋、角色篩選、分頁）、PATCH /api/admin/members/{id}/role、
   POST /api/admin/members/{id}/disable 與 /enable。全部 ADMIN-only，沿用 CouponController.requireAdmin 模式。
4. 不變量：不能改自己的角色或停用自己；不能讓系統沒有任何 ACTIVE ADMIN（並發下兩個 ADMIN 互相降級怎麼防——SELECT ... FOR UPDATE 鎖住 ADMIN 列？）。
5. 稽核：AuditAction 新增哪些值；before/after 記什麼（不可含 password_hash）。
6. Schema 雙路徑：backend/DB/16_member_admin.sql + V2__member_admin.sql；確認 FlywayAdoptionIntegrationTest 與 adopt 流程不受影響。
7. 既有 token 相容：上線前簽發、沒有 ver claim 的 token 要拒絕還是當 ver=0。
可讀：security/*、model/Member.java、repository/MemberRepository.java、service/AuthService.java、service/AuditLogService.java、
      controller/CouponController.java、controller/AuditLogController.java、db/migration/V1__baseline_schema.sql、backend/DB/04_member.sql、
      config/FlywayAdoptionConfiguration.java、integration/AuthIntegrationTest.java、integration/AbstractMySqlIntegrationTest.java
不跑測試。
```

### B 實作
```
任務：Phase 4.#3 B 階段，依 docs/tasks/072 已確認設計實作，不擴大。
可改：security/JwtService.java、security/JwtAuthFilter.java、model/Member.java、repository/MemberRepository.java、
      service/AuthService.java（只改 login 擋停用帳號）、新 service/MemberAdminService.java、新 controller/MemberAdminController.java、
      AuditAction、backend/DB/16_member_admin.sql、db/migration/V2__member_admin.sql、
      前端新 components/MemberAdminPanel.vue + App.vue 的 ADMIN 按鈕、對應測試。
不可改：訂單/付款/庫存相關程式。
測試（RED → GREEN）：
- 升級 BUYER→SELLER 後，舊 token 呼叫 seller 端點被拒，重新登入後成功
- 停用後：舊 token 401/403（依 072）、login 被拒；啟用後可登入
- 改自己角色 / 停用自己 → 409
- 最後一個 ADMIN 不可降級；兩個 ADMIN 並發互相降級，結果至少剩一個 ADMIN（真 MySQL 並發測試）
- 每個操作產生 audit_log，且 before/after 不含 password_hash
- 非 ADMIN 呼叫任一端點 → 403
- Vitest：MemberAdminPanel 列表、改角色、停用
要跑：mvn '-Dtest=JwtServiceTest,JwtAuthFilterTest,AuthIntegrationTest,FlywayAdoptionIntegrationTest,<新 MemberAdmin 測試>' '-Djacoco.skip=true' test；
      通過後 mvn clean test（全量 + JaCoCo）；cd frontend && npx vitest run
```

### C 驗收（獨立審查，另一個 agent）
```
任務：Phase 4.#3 C 階段，獨立唯讀審查（依 AGENTS.md：Critical 由非作者 agent 執行，不讀作者報告）。
範圍：git diff advanced-v2...HEAD，加上 docs/tasks/072 的設計段落。
重點：撤銷是否真的即時（或在 072 宣告的延遲內）、filter 有無繞過路徑（public route、OPTIONS、例外分支）、
最後 ADMIN 不變量的並發正確性、舊 token 相容處理、V2 migration 在 legacy adopt 流程下的行為、稽核不洩漏敏感欄位。
不修改程式碼；PASS/FAIL + 每個 finding 的 file:line 與失敗情境。
```

---

## Phase 4.#4 — M3 模擬登入特定使用者（風險 Critical → 需 Codex/Claude 獨立審查）

**前置**：Phase 4.#3 必須已 PASS（撤銷機制是模擬安全的前提）。
**使用者已決定**：模擬期間「可寫，但禁止敏感操作」。

**預設敏感操作黑名單**（A 階段逐條確認，B 階段集中實作在 filter 一處，比照 `isPublicRoute` 的單一政策模式）：
| 類別 | 路徑 |
|---|---|
| 付款 | `POST /api/orders/{id}/payment`、`/api/payments/**` |
| 下單（金錢承諾） | `POST /api/orders`、`POST /api/cart/checkout`、`POST /api/orders/{id}/cancel` |
| 優惠券 | `POST /api/cart/coupon-preview` |
| 帳號安全 | `/api/auth/**`（含 change-password） |
| 個資 | `PUT /api/member/profile`、`/api/member/addresses` 的所有寫入 |
| 管理功能 | `/api/admin/**`（含模擬端點本身 → 不可巢狀） |

允許（會被稽核標記）：商品管理寫入、`/api/seller/**` 訂單狀態與評論隱藏、購物車增刪改、自己的評論。

### A 定位
```
任務：Phase 4.#4 A 階段，只讀。前置確認 Phase 4.#3 已在 docs/project-state.md 標記 PASS，否則停止並回報。
請確認並寫進 docs/tasks/073-phase4-4-impersonation.md：
1. Token 設計：sub=目標 email、role=目標角色、act=管理員 email、imp=true、exp=15 分鐘、ver=目標 token_version；
   不可續期；管理員本身被停用或降級時，模擬 token 也要失效（filter 同時驗 act 的 ver？）。
2. 逐條確認上方黑名單/允許清單，對照所有 controller 路徑（含 seller/admin 雙路徑別名），列出任何遺漏的寫入端點。
   黑名單回應碼（403）與錯誤訊息。
3. 拒絕條件：目標是 ADMIN、目標停用、呼叫者本身是模擬 token。
4. 稽核：audit_log 與 order_status_history 新增 impersonator VARCHAR(255) NULL；AuditLogService.record 的簽章怎麼擴充
   才不用改所有呼叫點（例如從 request attribute 取）；模擬開始/結束本身寫 IMPERSONATION_START/END（含 reason）。
5. Schema 雙路徑：backend/DB/17_impersonation.sql + V3__impersonation.sql。
6. 前端：管理員 token 暫存在哪（sessionStorage？記憶體？）、橫條倒數、結束模擬還原；過期時的 UX。
7. 威脅模型：模擬 token 外洩、XSS 下管理員 token 與模擬 token 同時在瀏覽器、reason 欄位注入。
可讀：security/*、所有 controller/*.java（只看路徑與方法）、service/AuditLogService.java、repository/AuditLogRepository.java、
      service/OrderStatusService.java（只看 history 寫入）、docs/tasks/072、frontend/src/stores/auth.js、App.vue
不跑測試。
```

### B 實作
```
任務：Phase 4.#4 B 階段，依 docs/tasks/073 已確認範圍實作。
可改：security/JwtService.java、security/JwtAuthFilter.java（黑名單集中於一個 static 方法）、新 service/ImpersonationService.java、
      新 controller/ImpersonationController.java、service/AuditLogService.java、repository/AuditLogRepository.java、
      service/OrderStatusService.java（只加 impersonator 欄位寫入）、dto/AuditLogEntry.java、
      backend/DB/17_impersonation.sql、db/migration/V3__impersonation.sql、
      前端 stores/auth.js、新 ImpersonationBanner.vue、MemberAdminPanel.vue（加「以此身分操作」）、對應測試。
不可改：付款/庫存/訂單建立邏輯（黑名單在 filter 擋，不在 service 層散落判斷）。
測試（RED → GREEN）：
- 黑名單每一類至少一例 → 403，且沒有產生任何資料變更
- 允許清單：賣家商品編輯成功，audit_log.impersonator = 管理員 email；訂單狀態變更寫入 order_status_history.impersonator
- 模擬 ADMIN / 停用帳號 / 巢狀模擬 → 拒絕
- 15 分鐘過期 → 401（用可注入的 Clock 或短 expiration 設定測，不要 sleep）
- Phase 4.#3 停用目標或降級管理員後，模擬 token 立即失效
- 一張表格驅動測試列出全部 controller 寫入端點，斷言每個都被分類（黑名單或允許），新增端點忘了分類就失敗
- Vitest：橫條倒數、結束模擬還原管理員身分
要跑：mvn '-Dtest=JwtServiceTest,JwtAuthFilterTest,AuditLogIntegrationTest,OrderStatusIntegrationTest,<新 Impersonation 測試>' '-Djacoco.skip=true' test；
      通過後 mvn clean test（全量 + JaCoCo）；cd frontend && npx vitest run；
      built-in browser 走一遍：ADMIN → 模擬賣家改商品成功 → 嘗試付款被擋 → 結束模擬，截圖。
```

### C 驗收（獨立審查，另一個 agent）
```
任務：Phase 4.#4 C 階段，獨立唯讀審查，不讀作者報告。
範圍：git diff advanced-v2...HEAD + docs/tasks/073 威脅模型段。
重點：黑名單能否被繞過（路徑別名、尾斜線、大小寫、URL encoding、HTTP method override、OPTIONS）、
「所有寫入端點都已分類」測試是否真的掃到全部、模擬 token 能否用來簽發新 token、稽核是否每個寫入都帶 impersonator、
前端是否把管理員 token 放在比原本更不安全的位置。
不修改程式碼；PASS/FAIL + file:line + 失敗情境。
```

---

## 之後（M4–M11）

見 `068-maintainer-console-plan.md` 的表格；Phase 4.#4 完成後再挑下一項展開成同樣格式。
