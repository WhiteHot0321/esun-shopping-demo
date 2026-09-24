# Phase 3.2 #15 handoff

Goal: 推薦系統 — 由訂單歷史推導的唯讀推薦：公開的「買過此商品的人也買了」與需登入的「為你推薦」。無寫入、無鎖、不碰結帳/庫存/付款路徑。

Changed: `backend/DB/15_recommendation.sql`（可重複執行，只補 `idx_order_detail_product_order (product_id, order_id)`；`01_schema.sql` 新裝即含）; `RecommendationRepository`（共購/熱銷/新品三個排序查詢，只算未取消訂單、只推可售商品，score DESC + product_id ASC 決定性排序）, `RecommendationService`（三層補位、去重、`recommendation.min-support` 預設 2、唯讀交易）, `RecommendationController`（`GET /api/products/{id}/recommendations` 公開；`GET /api/recommendations` 需 JWT，身分只取 token）, `RecommendationItem`, `ProductRepository.findAvailableByIds`, `JwtAuthFilter`（公開路徑加入 per-product 端點）; frontend `RecommendationStrip.vue`（+spec）, `ShopWorkspace.vue`（為你推薦、評論區下的也買了、加入購物車走既有數量路徑、目錄重載後刷新）, `App.spec.js`; tests `RecommendationServiceTest`(8)、`RecommendationIntegrationTest`(6, 真實 MySQL)、fixture 加 `15_recommendation.sql`。

Validated: backend `mvn test`（手動清 target，見 Risks）**240/240**，0 failures/errors/skipped，JaCoCo gate PASS（225 基線 + 14 新測試 + 工作樹中原本未提交的 `GlobalExceptionHandlerTest` 1 個）; frontend checkout 3/3 + Vitest **80/80**（71 + 9 新）, build PASS; 變異驗證：把「排除已取消訂單」改成 `1 = 1` 使 6 個整合案例中的 3 個失敗，已還原。細節：`docs/tasks/037-recommendation-system.md`。

Not proven: 獨立（非作者）審查與 Codex 審查；未做真實瀏覽器 E2E（前端由元件/App 層測試 + build 驗證，後端由真實 MySQL 整合測試驗證）；公開端點速率限制；瀏覽/點擊行為訊號、時間衰減、快取。

Risks: 資料稀疏時共購層為空（min-support=2 的隱私取捨），清單由熱銷/新品補位；個人化查詢以 `IN` 帶入已購商品 id（理論上受商品總數限制）；本機 `backend/target/shopping-backend-1.0.0.jar` 被使用者正在跑的後端（pid 50008）佔用，`mvn clean` 會失敗，因此改為手動清除 classes/報告後跑 `mvn test`，未終止該程序。

Next: 收藏/心願單，或回 Phase 3 工程深度主線（k6、Redis Lua、CI/CD）；若要推薦更準，可加瀏覽事件表（非同步寫入，避免拖慢讀取路徑）。
