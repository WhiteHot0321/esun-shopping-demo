# 068 維護者視角（Maintainer Console）功能規劃

**日期**: 2026-10-16
**狀態**: 規劃完成，未實作；提示詞待更新
**前身**: `018-maintainer-feature-list.md`（其 Tier 1-3 基礎設施項目已在 Phase 3.x 大致完成）

## Context
使用者想暫停 Phase 3.3 其餘項目（#18 B3、#21 主機層、#22 ECPay 都卡在外部資源），轉做「維護者視角」。
018-maintainer-feature-list.md 的 Tier 1-3（RBAC、Actuator、備份、稽核、告警、密鑰、SQL、CI/CD、Swagger、軟刪除）
大多已在 Phase 3.x 完成，所以這次的「維護者」是**業務營運後台**，不是基礎設施。

現況（已驗證）：
- `Member.Role` = BUYER/SELLER/ADMIN；`JwtAuthFilter` 只信 token 內的 role claim，**不查 DB** → 改角色/停權無法讓舊 token 失效。
- 升級賣家目前只能直接改 SQL（project-state 明載「no self-service seller-upgrade endpoint」）。
- ADMIN 已有：優惠券（`CouponController`）、稽核日誌（`AuditLogController`，唯讀）、seller/admin 訂單與商品端點。
- 前端：`App.vue` 用 `isSeller`/`isAdmin` computed 控制面板；`stores/auth.js` 存 role。
- 沒有會員管理、沒有帳號停用、沒有 token 版本/撤銷機制。

使用者決定：三種切換模式**全部做，一個一個來**；模擬登入採「**可寫但禁止敏感操作**」。

## 本文件的產出
1. 新增 `docs/tasks/068-maintainer-console-plan.md`：以下功能清單、依賴、風險等級、驗收標準。
2. 之後再依此更新提示詞（Notion 提示詞頁 + `project-prompts-index` 記憶），**本輪不寫程式**。

## 功能清單與順序

### M0 會員管理（地基，Critical：改權限）
- `GET /api/admin/members`（搜尋 email/角色、分頁）、`PATCH .../{id}/role`、`POST .../{id}/disable|enable`。
- 新增 `member.token_version` + `member.status`（Flyway V-next）；JWT 帶 `ver`，`JwtAuthFilter` 每請求比對 DB（或短 TTL 快取）→ 改角色/停權立即撤銷舊 token。
- 防呆：不能降級/停用自己、不能移除最後一個 ADMIN；全部寫 audit_log。
- 取代「SQL 升級賣家」這個手動步驟。
- 概念：stateless JWT 的撤銷問題、最後管理員不變量。

### M1 開發用測試帳號快速切換（Low）
- 僅 `dev` profile 註冊的 `POST /api/dev/login-as`（或前端下拉 + seed 帳號），`ProductionConfigValidator` 在 prod 拒絕開啟；`isPublicRoute` 不需改（profile 未啟用時 handler 不存在 → 404）。
- 測試：prod profile 下端點 404 的整合測試。
- 概念：環境隔離、feature flag 的 fail-closed。

### M2 以自己帳號預覽角色畫面（Low，前端為主）
- auth store 加 `viewAs`（ADMIN 才可設 BUYER/SELLER），`isSeller/isAdmin` 改看 effective role；頂部橫條「預覽中」。
- 誠實標註：這只是 UI 透鏡，後端權限不變（ADMIN 本來就通過 seller 檢查）。
- 測試：Vitest 切換後面板顯示/隱藏。

### M3 模擬登入特定使用者（Critical → 需 Codex/Claude 獨立審查）
- `POST /api/admin/impersonations {memberId, reason}` → 短效 token（15 分鐘）：`sub`=目標、`act`=管理員 email、`imp=true`、不可續期。
- 禁止：模擬 ADMIN、巢狀模擬、被停用帳號。
- **敏感操作黑名單集中在 filter 一處**（單一政策，同 `isPublicRoute` 模式）：付款相關、改密碼/重設、個資與地址、優惠券套用、所有 `/api/admin/**`、模擬端點本身 → 403。其餘寫入（商品編輯、訂單狀態、購物車）允許。
- 稽核：`audit_log`、`order_status_history` 新增 `impersonator` 欄位；開始/結束本身也記錄（含 reason）。
- 前端：紅色橫條「正在以 X 身分操作（剩 mm:ss）」＋「結束模擬」按鈕還原管理員 token。
- 測試：黑名單每類一例 403、允許清單成功且稽核帶 impersonator、過期 401、模擬 ADMIN 被拒、M0 停權後模擬 token 失效。

### 其他維護者功能（M3 之後，按價值排序，可再挑）
| # | 功能 | 重用 | 風險 |
|---|---|---|---|
| M4 | 營運儀表板：今日訂單/營收、付款失敗、低庫存、Redis 降級狀態 | 既有 Micrometer counters、`StockCacheService` snapshot | Low |
| M5 | 付款異常佇列：`REFUND_REQUIRED`、卡在 `INITIATED` 的付款，人工標記處理 | `PaymentStatus`、`PaymentRepository` | Medium |
| M6 | 庫存核對：一鍵比對 Redis vs DB、手動重新同步 | `cache.audit()`、#21 新增的 after-commit sync | Medium |
| M7 | 稽核日誌強化：依 impersonator/actor 篩選、CSV 匯出 | `AuditLogController` | Low |
| M8 | 全站評論管理（跨賣家）＋ 處理「訂單取消仍可評論」觀察項 | `ProductReviewService` | Medium |
| M9 | FAQ / 客服知識庫管理 + 重建 embedding | `faq`、`doc_embedding` 表 | Medium |
| M10 | 系統公告 / 維護模式（擋下單、保留瀏覽） | filter 層 | Medium |
| M11 | 登入失敗鎖定與安全事件列表 | M0 的 `member.status` | Medium |

## 依賴
M0 → M3（撤銷機制是模擬安全的前提）；M1、M2 獨立，可先做暖身；M4–M11 在 M3 之後，各自獨立分支（依 phase2-branching-strategy）。
建議實際順序：**M1 → M2 → M0 → M3**（先做低風險的兩個熟悉前端/profile，再做地基與 Critical）。

## 驗證（各項實作時）
- 後端：每項 targeted `mvn -Dtest=... test`（Testcontainers），M0/M3 結束跑 `mvn clean test` + JaCoCo。
- 前端：Vitest；M2/M3 用 built-in browser 走一遍切換流程截圖。
- M0、M3 為 Critical：依 AGENTS.md 由另一個 agent 做獨立唯讀審查。

## 關鍵檔案
`backend/.../security/JwtAuthFilter.java`、`JwtService.java`、`model/Member.java`、`repository/MemberRepository.java`、
`service/AuditLogService.java`、`config/ProductionConfigValidator.java`、`db/migration/`、
`frontend/src/App.vue`、`frontend/src/stores/auth.js`、新 `MemberAdminPanel.vue`。
