# Independent Audit — Phase 3.1 #9 prerequisites

- Task ID: REVIEW-029
- Parent: Phase 3.1 #9 product reviews
- Baseline commit: `6f317fb4dfaf4035bb6e6b593232bd17ce3445ef`
- Mode: read-only audit; do not edit files

Derive expected behavior from this contract before inspecting implementation: authenticated identities must carry a trusted BUYER/SELLER/ADMIN role; only SELLER or ADMIN may create products; newly created products must retain the authenticated seller as owner; seller moderation must be restricted to products owned by that seller while ADMIN may cross product boundaries. Anonymous and BUYER product creation must be rejected. Existing public product browsing and authenticated order behavior must remain functional.

Inspect only these files (up to 12 reads):

- `backend/DB/01_schema.sql`
- `backend/DB/04_member.sql`
- `backend/DB/09_product_review.sql`
- `backend/src/main/java/com/esun/shop/model/Member.java`
- `backend/src/main/java/com/esun/shop/model/Product.java`
- `backend/src/main/java/com/esun/shop/repository/MemberRepository.java`
- `backend/src/main/java/com/esun/shop/repository/ProductRepository.java`
- `backend/src/main/java/com/esun/shop/security/JwtService.java`
- `backend/src/main/java/com/esun/shop/security/JwtAuthFilter.java`
- `backend/src/main/java/com/esun/shop/service/AuthService.java`
- `backend/src/main/java/com/esun/shop/service/ProductService.java`
- `backend/src/main/java/com/esun/shop/controller/ProductController.java`

Tests already executed by the author: full Maven suite 104/104 with JaCoCo gate, frontend tests 26/26. Do not execute commands. Report exactly one verdict: PASS, FAIL, BLOCKED, or NEEDS_ARCH_DECISION. For non-PASS, list concrete file/line evidence, expected behavior, and the smallest correction scope. Ignore style-only comments and unrelated pre-existing issues.
