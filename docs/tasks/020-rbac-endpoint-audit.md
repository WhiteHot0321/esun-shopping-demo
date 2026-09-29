# Task 020 — RBAC API 端點稽核

**稽核日期**：2026-09-18（Asia/Taipei）  
**模式**：唯讀靜態稽核；未修改應用程式碼、未執行測試  
**稽核基準**：`feature/frontend-ux-revamp` @ `6ae06414a884aff7dd173fa7b2f52ecf4213ad8a`（包含稽核開始前既有的未提交變更）  
**範圍**：4 個 Controller、JWT filter/service、Member model/schema，以及既有 Phase 3 角色規劃文件

## 結論摘要

目前共有 **6 個 Controller 業務端點**：4 個公開、2 個要求有效 JWT、0 個具角色授權。系統只能判斷「是否登入」，不能區分 `BUYER`、`SELLER`、`ADMIN`。

已確認的高風險缺口：

- `POST /api/products` 目前只要求有效 JWT；任何已登入帳號都能上架商品，缺少 `SELLER`／`ADMIN` 限制。
- `POST /api/orders` 目前也只要求有效 JWT；尚未明確限制為 `BUYER`。若採單一角色模型，`SELLER`／`ADMIN` 也能以下單者身分呼叫。
- `member` schema 與 `Member` model 均無 `role`；JWT 只含 email subject；filter 只驗證 token 並放入 `authenticatedEmail`，沒有 authority／role context，也沒有任何回傳 403 的授權判斷。

因此，在新增角色資料、JWT role claim 與伺服器端角色檢查前，不能只靠前端隱藏按鈕補洞。

## 現行認證機制

`JwtAuthFilter.isPublic()` 以 HTTP method + 完整 URI 白名單放行下列路由，其他請求均要求 `Authorization: Bearer <token>`：

- 公開：`POST /api/auth/register`
- 公開：`POST /api/auth/login`
- 公開：`GET /api/products/available`
- 公開：`POST /api/support/ask`
- 公開（基礎設施）：所有 `OPTIONS` 預檢請求

有效 JWT 目前只證明 token 的簽章、期限及非空 email subject。它不證明帳號具有任何角色；filter 也不會重新查詢 member 狀態或角色。

## 端點－角色對照表

圖例：`✓` 可呼叫；`—` 不應允許；「所有人」包含未登入訪客。建議權限採**單一角色、最小權限**模型；若未來允許一人多角色，應改以 authorities 集合表達，但端點最低權限不變。

| Controller | Method / Path | 業務性質 | 現況 | 現況可呼叫者 | 建議最低權限 | BUYER | SELLER | ADMIN | 缺口／備註 |
|---|---|---|---|---|---|:---:|:---:|:---:|---|
| `AuthController` | `POST /api/auth/register` | 寫：建立會員 | 公開 | 所有人 | 公開 | ✓ | ✓ | ✓ | 公開註冊應只能建立 `BUYER`；不得接受 client 傳入 `SELLER`／`ADMIN` role。商家與管理員升權需獨立、受控流程。 |
| `AuthController` | `POST /api/auth/login` | 認證：簽發 token | 公開 | 所有人 | 公開 | ✓ | ✓ | ✓ | 登入成功後 token 應攜帶伺服器端取得的角色；不得信任 client 自報角色。 |
| `ProductController` | `GET /api/products/available` | 讀 | 公開 | 所有人 | 公開 | ✓ | ✓ | ✓ | 商品目錄可維持公開。 |
| `ProductController` | `POST /api/products` | 寫：上架商品 | 僅登入 | 任何有效 JWT | `SELLER` 或 `ADMIN` | — | ✓ | ✓ | **重大遺漏保護**：目前 BUYER 也能上架商品，且無商品所有權概念。 |
| `OrderController` | `POST /api/orders` | 寫：建立訂單／扣庫存 | 僅登入 | 任何有效 JWT | `BUYER` | ✓ | — | — | **角色保護缺口**：目前三種角色只要有 token 都可下單。建議單一角色模型下只允許 BUYER；若業務允許商家同時購物，應使用多角色／獨立 buyer authority，而非默認所有登入者可下單。 |
| `SupportController` | `POST /api/support/ask` | 業務讀：問答（HTTP POST） | 公開 | 所有人 | 公開 | ✓ | ✓ | ✓ | 雖使用 POST，但未寫入核心業務資料；可維持公開，另加 rate limit、輸入長度與資源配額保護。 |

> `ADMIN` 欄表示管理角色在正常職責下應有的權限，不等於「管理員自動擁有所有 BUYER 行為」。基於最小權限，ADMIN 可管理商品，但不應默認代替買家建立訂單。若產品需求明確要求管理員代客下單，應建立獨立且可稽核的 admin operation，而不是放寬一般下單端點。

## 各角色可呼叫的端點（建議狀態）

公開端點對三種角色及未登入訪客都可用；以下清單列出登入後的完整可用集合。

### BUYER

- `POST /api/auth/register`（公開；只能註冊成 BUYER）
- `POST /api/auth/login`（公開）
- `GET /api/products/available`（公開）
- `POST /api/support/ask`（公開、業務唯讀）
- `POST /api/orders`（BUYER 專屬寫入）
- 不得呼叫 `POST /api/products`

### SELLER

- `POST /api/auth/login`（公開）
- `GET /api/products/available`（公開）
- `POST /api/support/ask`（公開、業務唯讀）
- `POST /api/products`（SELLER／ADMIN 寫入）
- 不得透過公開註冊自行取得 SELLER；不得在單一角色模型下呼叫 `POST /api/orders`

### ADMIN

- `POST /api/auth/login`（公開）
- `GET /api/products/available`（公開）
- `POST /api/support/ask`（公開、業務唯讀）
- `POST /api/products`（管理例外；後續最好拆出可稽核的管理流程）
- 不得透過公開註冊自行取得 ADMIN；不得在單一角色模型下默認呼叫 `POST /api/orders`

## 被遺漏或不足的保護

| 嚴重度 | 位置 | 現象 | 風險 |
|---|---|---|---|
| 高 | `POST /api/products` | 只有 JWT 有效性檢查，沒有 SELLER／ADMIN 授權 | 任一 BUYER 可建立商品、污染目錄與庫存資料 |
| 高 | `member` schema / model | 沒有 role 欄位 | 系統無可信角色來源，無法實作伺服器端 RBAC |
| 高 | JWT 產生與驗證 | token 只有 email subject，沒有 role／authorities | 路由層無法依 token 做角色判斷 |
| 高 | `JwtAuthFilter` | 僅設定 `authenticatedEmail`，無 authorization decision／403 | 所有有效 token 在受保護路由上權限相同 |
| 中 | `POST /api/orders` | 未限制 BUYER | 單一角色模型下 SELLER／ADMIN 也能執行買家交易；職責與稽核界線不清 |
| 中 | `POST /api/auth/register` | 未來加 role 時若直接綁定 request，可能形成自助升權 | 攻擊者可註冊成 SELLER／ADMIN；role 必須由伺服器固定為 BUYER |
| 中 | 公開 `POST /api/support/ask` | 無角色門檻是合理產品選擇，但 LLM／embedding 資源可被匿名濫用 | 成本耗盡與拒絕服務；應以 rate limit／配額處理，不應誤用 RBAC |
| 低 | filter 適用範圍 | 註解稱保護 `/api/**`，實作實際上對所有非白名單、非 OPTIONS request 套用 JWT | 未來加入健康檢查、Swagger、靜態資源或新公開 API 時可能被意外擋下；應明確限制 matcher 並集中維護政策 |

## 安全建議

1. **建立可信角色來源**：`member.role` 使用受限值（`BUYER`、`SELLER`、`ADMIN`），既有會員 migration 明確回填 `BUYER`，資料庫加 `NOT NULL` 與約束。公開註冊一律由後端指定 `BUYER`。
2. **在 token 中攜帶角色但以資料庫為簽發依據**：登入時由 member record 取得角色並簽入 JWT；不可採用 request body、query string 或前端狀態中的角色。定義角色變更後既有 token 的失效策略（短效 token、token version 或即時 DB 查核）。
3. **集中授權政策並採 deny-by-default**：將公開路由與角色規則集中在安全設定；新增端點若未明列，預設拒絕。未登入回 401，已登入但角色不足回 403，兩者不可混用。
4. **優先封鎖商品寫入缺口**：`POST /api/products` 僅允許 `SELLER`／`ADMIN`。後續商品編輯、下架、補貨端點除了角色外，還必須驗證 seller ownership；ADMIN 例外操作應留下稽核軌跡。
5. **明確定義角色組合**：本表採單一角色最小權限。如果賣家也能購物，應採多角色 authorities（例如同時具有 BUYER、SELLER），不要以 `ADMIN > SELLER > BUYER` 的隱含階層讓高權限帳號自動取得所有交易能力。
6. **補上授權驗收案例**：實作階段至少驗證匿名 401、角色不足 403、BUYER 上架 403、SELLER／ADMIN 上架成功、BUYER 下單成功，以及公開端點不帶 token 仍可用。另驗證偽造 role、過期 token、角色異動後舊 token 的行為。
7. **公開 LLM 端點做濫用防護**：維持匿名可用時，增加 per-IP／帳號 rate limit、請求大小與逾時限制、併發上限、監控與成本告警。這屬資源保護，不取代 RBAC。

## 實作階段的建議政策表

下列為後續 RBAC 實作可直接採用的最低政策；本次沒有實作：

| Policy | Routes |
|---|---|
| `permitAll` | `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/products/available`, `POST /api/support/ask`, `OPTIONS /**` |
| `hasRole(BUYER)` | `POST /api/orders` |
| `hasAnyRole(SELLER, ADMIN)` | `POST /api/products` |
| `authenticated` | 僅限未來確實適用所有登入角色、且經明確盤點的端點 |
| `denyAll` | 其餘未宣告路由（RBAC policy 層）；404 等錯誤處理需避免被 filter 錯誤轉成 401 |

## 證據與限制

程式碼證據：

- `AuthController`：兩個公開認證端點。
- `ProductController`：一個公開讀取端點及一個受保護商品建立端點，Controller 本身無角色註解或判斷。
- `OrderController`：一個受保護訂單建立端點，Controller 本身無角色註解或判斷。
- `SupportController`：一個公開問答端點。
- `JwtAuthFilter`：公開白名單、JWT email 驗證與 `authenticatedEmail` request attribute；沒有角色與 403。
- `JwtService`：JWT 僅含 email subject、issued-at、expiry 與 signature。
- `Member`／`04_member.sql`：沒有 role 欄位。

限制：

- 這是指定 checkout 的靜態快照，不代表其他分支或 worktree 的 Phase 3 實作狀態。
- 依任務要求未跑測試、未啟動服務、未以 HTTP 動態驗證。
- 報告只盤點目前 Controller 已存在的端點；訂單查詢、商品編輯／下架／補貨、管理後台等規劃中端點不列為現有 API，待新增時必須先加入政策表。

## 工程概念

認證（authentication）回答「你是誰」，授權（authorization）回答「你能做什麼」。目前 JWT filter 只完成前者；RBAC 必須用可信的伺服器端角色來源完成後者，並在每個敏感動作上以最小權限與預設拒絕原則落實。
