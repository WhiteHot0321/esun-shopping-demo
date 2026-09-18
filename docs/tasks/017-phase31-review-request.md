# CLAUDE INDEPENDENT REVIEW REQUEST

MODE: REVIEW_ONLY

TASK_ID: 017-phase31-pay-status-foundation
BASELINE: ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287
WORKTREE: C:\GitHub\esun-shopping-phase31
ALLOWED_FILES_TO_READ:
- docs/tasks/017-phase31-pay-status-foundation.md
- backend/src/main/java/com/esun/shop/dto/CreateOrderRequest.java
- backend/src/main/java/com/esun/shop/service/OrderTransactionService.java
- backend/src/test/java/com/esun/shop/service/OrderServiceTest.java
- frontend/src/components/ShopWorkspace.vue
- frontend/src/App.spec.js
READ_LIMIT: 6 core files plus git diff/status
FILES_CHANGED_BY_REVIEWER: 0
TEST_COMMAND: none; inspect supplied evidence only
EXCLUSIONS: no edits, no tests, no payment-provider design, no product administration, no unrelated findings

Review the uncommitted diff against these requirements before relying on the author notes:
1. A buyer-controlled JSON payStatus must not determine a new order's persisted state.
2. New orders must persist PayStatus.PENDING ordinal 0.
3. Checkout UI and its API payload must not expose/send payStatus.
4. Existing Java fixtures may retain source compatibility only if that cannot restore JSON control.

Evidence supplied: focused backend tests executed 23 tests with 0 failures/errors/skips, then Maven failed only at the repository-wide JaCoCo gate because the focused invocation did not cover unrelated gated services. Frontend checkout tests passed 3/3; Vitest passed 8/9, with the sole failure in an unchanged register-test selector expecting old placeholder `密碼` instead of current `密碼（至少 8 碼）`.

End with exactly one outcome: PASS, FAIL, BLOCKED, or NEEDS_ARCH_DECISION. Report only concrete blocker defects with file/line and expected/actual behavior; distinguish optional follow-ups.
