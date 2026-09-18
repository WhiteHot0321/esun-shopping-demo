# 020 - RBAC 端點盤點與安全稽核（唯讀）

- 類型：安全稽核（Read-only audit，不變更程式碼、不跑測試）
- 範圍：`backend/src/main/java/com/esun/shop/**`（所有 `@RestController`）
- 稽核日期：2026-09-18
- 對應分支：`claude/rbac-endpoint-audit-3ppb6r`

---

## 0. 重要前提修正（先於分析）

任務背景描述為「member 表無 role 欄位」「JWT token 未攜帶角色資訊」，這隱含專案已具備會員資料表與 JWT 登入機制，只是缺少角色欄位/角色宣告。

**實際盤點結果與此不符，且缺口更大：**

| 假設 | 實際現況 |
|---|---|
| 有 `member` 資料表，但缺 `role` 欄位 | **`backend/DB/schema.sql` 中完全沒有 `member` 表**。`shop_order.member_id` 只是一個 `VARCHAR(20)` 欄位，沒有對應的會員主檔、沒有 FK 約束、任何字串都能寫入 |
| 有 JWT 簽發/驗證機制，只是 payload 未帶角色 | **專案完全沒有登入端點、JWT、Session 或任何認證機制**。`pom.xml` 未引入 `spring-boot-starter-security`、`jjwt` 或任何 auth 相關套件；沒有 `SecurityConfig`、`Filter`、`Interceptor`、`@PreAuthorize` 等任何存取控制程式碼 |
| 路由層無權限檢查邏輯（暗示認證層存在，只是授權沒做） | 正確，但需更正為：**連「認證」都不存在，不只是「授權」沒做**。目前所有 API 對任何匿名使用者完全開放 |

因此本稽核以「目前程式碼庫的實際狀態」為準：這是一個**尚未導入任何身份驗證/授權機制的展示型專案**（README 中定位為玉山銀行後端實作題的 Demo）。以下分析同時涵蓋「現況」與「若要落地 RBAC 應該長成什麼樣子」兩部分。

---

## 1. Controller 與端點盤點

專案僅有 2 個 controller，共 **3 個 API 端點**，全部位於 `backend/src/main/java/com/esun/shop/controller/`：

| # | Controller | 方法 | 路徑 | 讀/寫 | 說明 | 程式位置 |
|---|---|---|---|---|---|---|
| 1 | `ProductController` | `POST` | `/api/products` | 寫 | 新增商品（含建立庫存） | `ProductController.java:25-29` |
| 2 | `ProductController` | `GET` | `/api/products/available` | 讀 | 查詢庫存 > 0 的商品清單 | `ProductController.java:31-34` |
| 3 | `OrderController` | `POST` | `/api/orders` | 寫 | 建立訂單（含扣庫存、寫入訂單明細） | `OrderController.java:23-27` |

沒有其他 controller（無 `MemberController`、`AuthController`、`UserController`、`AdminController` 等）。整個後端目前只覆蓋「商品」與「訂單」兩個資源，且沒有查詢單一訂單、查詢會員訂單歷史、修改/刪除商品、取消訂單等端點。

### 存取控制相關基礎設施盤點

| 項目 | 是否存在 | 備註 |
|---|---|---|
| Spring Security | ❌ 無 | `pom.xml` 未引入 |
| JWT / Session 驗證 | ❌ 無 | 無任何 filter/interceptor |
| 登入 / 登出 / 註冊端點 | ❌ 無 | |
| `member` 資料表 | ❌ 無 | `schema.sql` 只有 `product`、`shop_order`、`order_detail` |
| 角色（role）概念 | ❌ 無 | 程式碼中無任何 role/permission 字樣 |
| CORS 限制 | ✅ 有 | `CorsConfig.java` 僅允許 `http://localhost:5173` 來源，但**這只限制瀏覽器端跨源請求，非身份或角色驗證**，用 curl/Postman 或偽造 Origin header 皆可繞過 |
| 輸入驗證（Bean Validation） | ✅ 有 | `@Valid` + `jakarta.validation` 註解，防止畸形資料，但與授權無關 |

---

## 2. 現況分類：公開 / 需登入 / 應受角色限制

| 端點 | 目前現況 | 應有的現況（依業務語意） |
|---|---|---|
| `GET /api/products/available` | **完全公開**，無需任何憑證 | ✅ 應維持公開（一般電商「瀏覽商品」本就對所有訪客開放，含匿名買家） |
| `POST /api/products` | **完全公開**，任何匿名呼叫端皆可新增商品 | ❌ 應限定「需登入 + SELLER/ADMIN 角色」。目前對外等同任何人都能上架任意商品，這正是背景描述指出的資安缺口 |
| `POST /api/orders` | **完全公開**，`memberId` 由前端傳入的自由文字欄位決定，後端不做任何身份比對 | ❌ 應限定「需登入」，且下單者身分應取自登入憑證（如 JWT 內的 subject/member id），而非信任 request body 內的 `memberId` 欄位；否則任何人都能冒用他人會員編號建立訂單（IDOR / 身分冒用風險） |

即：**3 個端點中，1 個理應公開，2 個理應要求登入，其中 1 個（新增商品）更應加角色限制，但目前 3 個全部無差別開放。**

---

## 3. 角色-端點對照表（目標設計，供未來實作參考）

由於系統尚無角色與會員機制，以下是**建議的目標狀態**（不是現況），對應任務要求的「商家 (SELLER)、買家 (BUYER)、管理員 (ADMIN) 各自能調用的端點」：

| 端點 | 匿名訪客 | BUYER（買家） | SELLER（商家） | ADMIN（管理員） |
|---|:---:|:---:|:---:|:---:|
| `GET /api/products/available` | ✅ | ✅ | ✅ | ✅ |
| `POST /api/products`（新增商品） | ❌ | ❌ | ✅（僅能新增/管理自己名下商品，需搭配商品-賣家歸屬欄位） | ✅ |
| `POST /api/orders`（建立訂單） | ❌ | ✅（僅能以自己的會員身分下單，不可代填他人 memberId） | ⚠️ 視業務需求，若商家也能購物則比照 BUYER | ✅ |

補充：目前的資料模型（`product` 表沒有 `seller_id`/`owner_id` 欄位）也不足以支撐「商家只能管理自己商品」這個角色語意，若要落地 SELLER 角色，資料表需一併擴充。

---

## 4. 被遺漏的保護（缺口清單）

| # | 缺口 | 風險等級 | 說明 |
|---|---|---|---|
| G1 | `POST /api/products` 無任何身份/角色檢查 | **高** | 任何匿名使用者可任意上架商品、竄改商品目錄，屬背景描述明確點名的缺口 |
| G2 | `POST /api/orders` 無登入檢查，且 `memberId` 由前端自由填寫 | **高** | 可冒用任意會員編號下單（身分冒用/IDOR），並可能被用於灌單、庫存耗盡攻擊或誣陷他人消費紀錄 |
| G3 | 系統無 `member` 資料表與帳號體系 | **高（前置阻斷項）** | 沒有「使用者」這個實體，RBAC 無從掛載；須先設計會員/帳號模型才能談角色 |
| G4 | 無任何驗證層（JWT/Session） | **高（前置阻斷項）** | 路由層無法辨識「誰在呼叫」，因此也無法做任何角色檢查；這是 G1、G2 的根因 |
| G5 | 無角色（role）欄位與角色列舉 | **中** | 即使補上會員機制，若無 role 欄位仍無法區分 BUYER / SELLER / ADMIN |
| G6 | `product` 表無商品歸屬（seller_id）欄位 | **中** | 即使有角色，SELLER 也無法被限制「只能改自己的商品」，只能做粗粒度的「是不是 SELLER」判斷 |
| G7 | 無 method-level 或 route-level 授權宣告（如 `@PreAuthorize`、Security filter chain） | **高** | 目前即使加了角色欄位，也沒有任何機制在請求進入 controller 前做攔截與比對 |
| G8 | CORS 設定常被誤認為安全邊界 | **低（認知風險）** | `CorsConfig` 僅擋瀏覽器發出的跨源 XHR/fetch，對伺服器對伺服器呼叫、curl、Postman 完全無效，不能作為授權替代方案 |

---

## 5. 安全建議（優先順序）

1. **建立最小可行的認證機制**：新增 `member` 資料表（含 `role` 欄位，建議用列舉如 `BUYER`/`SELLER`/`ADMIN`）與登入端點，簽發帶有 `sub`（會員 ID）與 `role` claim 的 JWT。
2. **導入路由層授權**：引入 Spring Security（或至少一個自訂 `HandlerInterceptor`/`OncePerRequestFilter`）解析 JWT，將角色寫入 `SecurityContext`，並在 controller 或 config 層以 `@PreAuthorize("hasRole('SELLER')")` 或等效機制保護 `POST /api/products`。
3. **修正 `POST /api/orders` 的信任邊界**：下單者身分應改由伺服器端從已驗證的登入憑證取得，**不應繼續信任 request body 中的 `memberId` 欄位**；若仍需保留該欄位（如管理員代下單），須額外加角色檢查並記錄稽核軌跡。
4. **依商品歸屬做細粒度授權**：`product` 表加上 `seller_id`，讓 SELLER 只能新增/修改屬於自己的商品，ADMIN 不受此限。
5. **保留 `GET /api/products/available` 為公開端點**：此為正常電商行為，不需强制登入，但仍建議加上速率限制（rate limiting）以防止爬蟲/濫用，屬次要優化，非本次稽核重點。
6. **不要把 CORS 當作授權機制**：CORS 設定應維持，但需另外落實上述 1-4 點的伺服器端驗證與授權，兩者互不替代。

---

## 6. 結論

- 目前 3 個 API 端點（`GET /api/products/available`、`POST /api/products`、`POST /api/orders`）**全數對外公開，無任何登入或角色檢查**。
- 根因不是「JWT 缺角色欄位」這麼單純，而是**整個認證/授權層（帳號模型、登入機制、角色欄位、路由攔截）都尚未建立**，任務背景描述的前提與實際程式碼有落差，已在第 0 節註明。
- 本次稽核為唯讀，未修改任何程式碼、未執行測試，僅完成端點盤點、現況分類、角色-端點目標對照與缺口標記，供後續 RBAC 實作規劃使用。
