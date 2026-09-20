# Independent Audit — Phase 3.1 #9 product review system

- Task ID: REVIEW-030
- Parent: Phase 3.1 #9 product reviews
- Baseline commit: `6f317fb4dfaf4035bb6e6b593232bd17ce3445ef`
- Mode: read-only audit; do not edit files

Derive expected behavior from this contract before inspecting implementation: public users can page and deterministically sort visible reviews and see visible-only average/count; only a verified purchaser whose order_detail contains the product may create one review per member/product; rating is 1–5 and content is bounded; only the author may update/delete; seller lists reviews only for owned products (including hidden) and may only hide/restore; ADMIN may moderate across products; hidden reviews disappear from public/buyer views and aggregates. The database migration must be idempotent for existing installations and usable for a fresh schema. Buyer and seller UI paths must expose only authorized actions.

Inspect only these files (up to 14 reads):

- `backend/DB/09_product_review.sql`
- `backend/src/main/java/com/esun/shop/controller/ProductReviewController.java`
- `backend/src/main/java/com/esun/shop/dto/ReviewPageResponse.java`
- `backend/src/main/java/com/esun/shop/dto/ReviewRequest.java`
- `backend/src/main/java/com/esun/shop/model/ProductReview.java`
- `backend/src/main/java/com/esun/shop/repository/ProductReviewRepository.java`
- `backend/src/main/java/com/esun/shop/service/ProductReviewService.java`
- `backend/src/test/java/com/esun/shop/integration/ProductReviewIntegrationTest.java`
- `frontend/src/components/ProductReviews.vue`
- `frontend/src/components/ProductCatalog.vue`
- `frontend/src/components/ShopWorkspace.vue`
- `frontend/src/stores/auth.js`
- `frontend/src/App.spec.js`
- `frontend/src/api.js`

Tests already executed by the author: product-review integration scenarios passed, full Maven suite 104/104 with JaCoCo gate, frontend tests 26/26, Vite production build passed. Do not execute commands. Report exactly one verdict: PASS, FAIL, BLOCKED, or NEEDS_ARCH_DECISION. For non-PASS, list concrete file/line evidence, expected behavior, and the smallest correction scope. Ignore style-only comments and unrelated pre-existing issues.
