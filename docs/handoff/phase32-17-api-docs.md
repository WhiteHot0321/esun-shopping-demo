# Phase 3.2 #17 handoff

Goal: API 文件 [Maintainer] — 以 springdoc-openapi 自動產生 OpenAPI 3 規格與 Swagger UI（`/v3/api-docs`、`/swagger-ui.html`），文件標示的驗證需求與 `JwtAuthFilter` 共用同一份公開路由規則。

Changed: `backend/pom.xml`（springdoc-openapi-starter-webmvc-ui 2.6.0）; `application.yml`（`API_DOCS_ENABLED` 控制 api-docs 與 swagger-ui）; `OpenApiConfig`（metadata、bearerAuth scheme、依 `isPublicRoute` 標註 security 的 customizer）; `JwtAuthFilter`（`isPublicRoute` 抽成 public static，放行 GET 文件路徑）; 12 個 Controller 加 `@Tag` / `@Operation(summary)`; tests `OpenApiDocsTest`(5)、`OpenApiDocsDisabledTest`(1); `README.md` API 文件小節; `docs/tasks/040-api-docs.md`.

Validated: backend `mvn test` **246/246**（240 + 6），JaCoCo gate PASS；實機啟動（port 8099，已關閉）：api-docs 200 / 51 paths / 12 tags、swagger-ui.html 302、swagger-ui/index.html 200、無 token 的 `/api/orders` 仍 401。前端未改動，未重跑。細節：`docs/tasks/040-api-docs.md`。

Not proven: 獨立審查與 Codex 審查；欄位級 `@Schema` 說明與錯誤回應範例；正式部署設定實際帶 `API_DOCS_ENABLED=false`。

Risks: 預設開啟會公開端點清單，正式環境必須關閉；`ProductController.createProduct` 為無 mapping 的死碼（未處理）；本機 8080 使用者的舊後端未重啟，仍是不含 springdoc 的舊 jar。

Next: 部署設定預設關閉文件；CI 匯出 OpenAPI 規格做 API diff；回 Phase 3 工程深度主線（Redis Lua、CI/CD）或收藏/心願單。
