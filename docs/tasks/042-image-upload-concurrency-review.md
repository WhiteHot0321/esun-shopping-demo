# Independent read-only review request — Task 042

MODE: REVIEW_ONLY. Reviewer: Claude Code; author: Codex. Do not modify files, run tests, delegate, or inspect unrelated work. Baseline advanced-v2 35099232a5bb90e8c410b5fb1e5a6f0f10c0770b. Existing pom/document/untracked changes are not your scope.

Derive expected behavior from the requirements below and source/tests BEFORE reading any author explanation or task results. Do not read docs/tasks/042-image-upload-concurrency.md until you have independently formed your judgment.

Requirements: each product has at most 10 images, max 5 per request; concurrent requests must not bypass the cap or allocate duplicate display_order. Only an authenticated owner may upload to an active product; if upload waits on a concurrent deletion/owner change it must evaluate the committed state. Rejection must not leave image metadata, physical files or upload audit entries. Preserve existing file cleanup on metadata failure and same-transaction audit behavior.

Allowed core reads (5):
1. backend/src/main/java/com/esun/shop/service/ProductService.java (upload and its helpers/transaction construction).
2. backend/src/main/java/com/esun/shop/repository/ProductRepository.java (lockIncludingDeletedById, countProductImages, addProductImage, delete methods).
3. backend/src/test/java/com/esun/shop/integration/ProductImageConcurrencyIntegrationTest.java.
4. backend/src/test/java/com/esun/shop/service/ProductServiceTest.java (upload tests).
5. backend/src/test/java/com/esun/shop/integration/AbstractMySqlIntegrationTest.java (real MySQL fixture).

Then inspect existing surefire text reports for ProductImageConcurrencyIntegrationTest, ProductServiceTest, ProductManagementIntegrationTest and AuditLogIntegrationTest as execution evidence, not as substitute for reasoning. No commands/test execution permitted by runner. Review transaction boundary, MySQL repeatable-read semantics, ordering of reads and lock acquisition, lock coordination with delete, test determinism/false positives. Scope is normal controller calls to upload; no new outer transaction callers are added. Report PASS/FAIL/BLOCKED, actionable defects with file/line/evidence, and residual limitations separately. Do not propose unrelated refactors. Return a concise Traditional Chinese review and any available usage metrics.
