# CLAUDE INDEPENDENT REVIEW REQUEST

MODE: REVIEW_ONLY

TASK_ID: 018-phase31-ecpay-implementation
BASELINE: ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287 plus Task 017 foundation
WORKTREE: C:\GitHub\esun-shopping-phase31
ALLOWED_FILES: current Task 018 diff; at most 10 directly relevant payment/security/schema/test files
CHANGE_LIMIT: 0 files
TEST_COMMAND: none; inspect existing test evidence
EXCLUSIONS: no edits, no new tests, no frontend/product-admin review, no real sandbox call, no broad repository review

Derive expected behavior from Task 018 acceptance and code/tests before reading implementer notes. Review payment security, ownership, CheckMacValue correctness, callback authentication, merchant/order/amount validation, transaction atomicity, idempotency/concurrency, schema/migration safety, configuration secret handling, and API response semantics. Identify only concrete blocker defects with exact file/line and trigger. Existing evidence: EcpayPaymentGatewayTest 2/2 and PaymentServiceTest 5/5 passed; focused Maven exited 1 only because repository-wide JaCoCo gates unrelated services. End with exactly PASS, FAIL, BLOCKED, or NEEDS_ARCH_DECISION.
