# 074 Phase 4.#3 — M0 會員管理 + token 撤銷

**日期**: 2026-10-17　**分支**: `feature/phase4-3-member-admin`（從 `advanced-v2` 切出）　**風險**: Critical
**狀態**: A、B 完成；**C 獨立審查尚未進行**（依 AGENTS.md 須由非作者 agent 執行）。未 merge、未 push。

## 已確認設計（A）
| 項目 | 決定 | 理由 |
|---|---|---|
| 撤銷機制 | `member.token_version INT`，JWT 帶 `ver`，`JwtAuthFilter` 每請求以 email（unique index）查 `status, token_version` | 立即撤銷、無延遲視窗；多一次 index lookup，不在訂單服務路徑內（filter 層），`OrderServiceQueryCountIntegrationTest` 計 repository 查詢不受影響 |
| 停用 | `member.status` ACTIVE/DISABLED；filter 回 **401**（前端 axios 401 → 自動登出）；login 密碼正確後回 **403** | 先驗密碼，避免陌生人探測哪些 email 被停用 |
| 舊 token | 無 `ver` claim 一律 401 | 簡單且 fail-closed；代價是上線時所有人須重新登入 |
| Migration 雙路徑 | `backend/DB/16_member_admin.sql`（冪等）+ `V2__member_admin.sql` | `FlywayAdoptionIntegrationTest` 的 legacy fixture 仍只含到 15（釘選 fingerprint 不變），adopt 後 V2 自動套用（migrationsExecuted 1→2） |
| 不變量 | 不能改／停用自己（409）；不能移除／停用最後一位 ACTIVE ADMIN（409） | 並發：每次變更先 `SELECT ... FOR UPDATE` 鎖全部 ACTIVE ADMIN（id 序），再重讀操作者（已被降級者 → 403），最後鎖目標列 |
| 稽核 | `MEMBER_ROLE_CHANGE / MEMBER_DISABLE / MEMBER_ENABLE`，target=MEMBER；before/after 只記 email、role、status | 絕不含 password_hash |
| 權限 | 沿用 `requireAdmin` 模式，filter 仍用 token 內 role（版本相符即證明 role 未變） | |

## 端點
`GET /api/admin/members?q=&role=&page=&size=`（email 前綴，LIKE 跳脫；size ≤ 100）、`PATCH /api/admin/members/{id}/role`、`POST .../{id}/disable`、`POST .../{id}/enable`。

## 實作檔案
後端：`MemberAdminController`、`MemberAdminService`、`MemberRepository`（authState、鎖、search）、`JwtService`（ver）、`JwtAuthFilter`、`AuthService`（login 擋停用）、`Member`、`AuditAction`。前端：`MemberAdminPanel.vue`、`App.vue`（ADMIN 按鈕）、`AuditLogPanel.vue`（動作標籤）。

## 既有測試受影響而調整
filter 現在需要 `MemberRepository`，且 token 主體必須是真實 ACTIVE 會員：新增 `ActiveMemberTestConfig`（WebMvc 切片測試）；`ProductionProfileIntegrationTest`、`ProductImageConcurrencyIntegrationTest` 先插入會員列；`OrderRetryTest` 以 MockBean 提供 ACTIVE 狀態；`AuthServiceTest` 改 stub 三參數 `generateToken` 並新增停用登入測試。

## 測試結果
- `MemberAdminIntegrationTest`（真 MySQL）6/6：升級後舊 token 401 且重新登入成功、停用／啟用、無 ver 舊 token、非 ADMIN 403／未登入 401／自改 409／未知角色 400／404／搜尋與 LIKE 跳脫、稽核無 password、兩管理員並發互降恰一成功且 ADMIN 數為 1。
- Vitest：91/91（`MemberAdminPanel.spec.js` 7 個）。
- 全量 `mvn clean test`：**321/321**，JaCoCo 門檻通過；`CorsConfiguredOriginsTest` 新增 PATCH 預檢案例。
- 瀏覽器走查（本機 dev stack，built-in browser）：ADMIN 於面板升級買家→賣家，舊 token 立即 401，重新登入角色為 SELLER；停用後 token 401、登入 403；audit_log 兩筆且無 password。走查**抓到一個真缺陷**：CorsConfig 未允許 PATCH，預檢 403（已修並加回歸測試）。

## Follow-up（超出範圍，未做）
- 修改密碼／重設密碼後不會遞增 `token_version`，舊 token 仍有效至過期。
- 會員列表未顯示 `display_name`；無會員詳情／刪除。
- C 階段獨立審查（重點：filter 繞過路徑、並發鎖、V2 在 legacy adopt 下行為）。
