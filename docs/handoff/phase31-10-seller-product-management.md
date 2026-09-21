# Phase 3.1 #10 handoff

Goal: Seller product management is complete: owner-scoped CRUD/restock/soft delete, search + pagination, single/multiple image upload with persisted metadata and traversal-safe storage, bulk delete/restock, frontend management UI, and focused ownership/security tests.

Changed: Product schema/migration (`01_schema.sql`, `10_product_management.sql`, stored procedures); product controller/service/repository/model/DTOs; `ProductImageStorageService`; JWT filter public-image rule; static resource mapping; global exception handler (404/400/413); embedding removal on soft delete; seller `ProductManagement.vue`; unit, controller, real-MySQL integration and Vitest tests; task/project-state docs; `.gitignore` (`uploads/`).

Validated: Backend 137/137 with JaCoCo PASS (ProductService 108/110 lines); real-MySQL ProductManagementIntegrationTest 3/3; frontend checkout 3/3, Vitest 35/35, production build PASS; `git diff --check` PASS; independent read-only audit PASS (seven findings repaired and retested).

Not proven: live browser end-to-end against a running backend + MySQL; image deletion, orphan-file sweeping and upload rate limiting do not exist yet.

Next: Phase 3.1 #11 (order status flow). Optional follow-ups: image-delete endpoint + orphan sweeper, per-seller upload rate limit, `X-Content-Type-Options: nosniff` on `/uploads`.
