# Task 039 — Phase 3.2 #17 API 文件 (Swagger / OpenAPI) [Small]

- Baseline: branch `feature/phase32-17-api-docs` @ `2894c83`. Implementer: Claude Code (single agent; Small tier per `019`, no independent review required).
- Scope: 以 springdoc-openapi 自動產生 OpenAPI 3 規格與 Swagger UI，涵蓋全部 12 個 Controller；與 `JwtAuthFilter` 共用公開路由規則；可用環境變數關閉。Out of scope: request/response 欄位級 `@Schema` 說明、錯誤回應範例、Postman collection、前端整合。

## Requirements
1. `/v3/api-docs`（JSON/YAML）與 `/swagger-ui.html` 無需 token 可開啟，方便維護者與協作者。
2. 文件標示的驗證需求必須等於 filter 實際行為（不另維護第二份清單）。
3. 正式環境可完全移除文件端點。
4. 不放寬任何其他路由的驗證。

## Outcome
- 新增依賴 `springdoc-openapi-starter-webmvc-ui:2.6.0`（Spring Boot 3.3.x 對應版本）。
- 12 個 Controller 加 `@Tag`，51 個路徑的每個 operation 加 `@Operation(summary)`（含角色限制，如「SELLER、ADMIN」）。
- `OpenApiConfig`：文件 metadata、`bearerAuth`（HTTP bearer / JWT）scheme，`OpenApiCustomizer` 對「非公開」operation 標 bearer、公開 operation 不標。
- `JwtAuthFilter.isPublicRoute(method, path)` 抽成 public static，同時被 filter 與 `OpenApiConfig` 使用（單一來源）；加入 GET `/v3/api-docs`、`/v3/api-docs.yaml`、`/v3/api-docs/swagger-config`、`/swagger-ui.html`、`/swagger-ui/**`。
- `application.yml`：`API_DOCS_ENABLED`（預設 true）同時控制 `springdoc.api-docs` 與 `springdoc.swagger-ui`；關閉時 springdoc 不註冊 handler，路徑回 404。README 已補使用說明與「正式環境請關閉」。

## Verification
- `mvn test`: **246/246**（240 基線 + 6 新測試），JaCoCo gate PASS。
  - `OpenApiDocsTest`(5)：規格公開可讀且含 info/securityScheme；每個 operation 都有 summary 與 tag（≥40 路徑）；逐一比對每個 operation 的 bearer 標示 == `!isPublicRoute`；Swagger UI 路徑不被 filter 擋（非 401）；`GET /api/orders` 與 `POST /v3/api-docs` 仍 401。
  - `OpenApiDocsDisabledTest`(1)：`springdoc.*.enabled=false` 時 `/v3/api-docs`、`/swagger-ui/index.html` 皆 404。
- 實機（真實 Spring Boot 啟動於 8099，連本機 MySQL）：`/v3/api-docs` 200（51 paths、12 tags）、`/swagger-ui.html` 302、`/swagger-ui/index.html` 200、`/api/orders` 401。`@WebMvcTest` 無法載入 Swagger UI 靜態資源，故該項以實機驗證補足。

## Trade-offs / Risks
- 預設開啟：與本專案其他 dev 預設值（DB 密碼、JWT secret）一致，方便本機使用，但正式部署必須設 `API_DOCS_ENABLED=false`；規格會揭露全部端點（含 admin 路徑名稱），不含資料。尚無部署設定強制這件事。
- `/swagger-ui/**` 以前綴放行，僅限 GET，且僅提供 webjar 靜態資源。
- 文件為 summary 等級：欄位描述來自型別與 validation 註解，沒有逐欄位 `@Schema` 說明；角色限制寫在文字，不是機器可讀。
- 順帶發現：`ProductController.createProduct(...)` 沒有 mapping 註解，是死碼（未動）。
- 未做獨立審查、非 Codex 審查。

## Next
- 依需要補 `@Schema` 欄位說明與錯誤回應範例；在 CI 匯出 `/v3/api-docs` 作為 API 變更 diff 的產物；部署設定（compose/k8s）預設帶 `API_DOCS_ENABLED=false`。
