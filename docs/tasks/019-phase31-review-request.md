# CLAUDE INDEPENDENT REVIEW REQUEST

MODE: REVIEW_ONLY

TASK_ID: 019-phase31-payment-ux-ownership
BASELINE: Tasks 017–018 current uncommitted state
WORKTREE: C:\GitHub\esun-shopping-phase31
ALLOWED_FILES: Task 019 diff plus directly affected controller/filter/frontend/tests; maximum 8 core files
CHANGE_LIMIT: 0
TEST_COMMAND: none; inspect supplied evidence
EXCLUSIONS: no edits, no tests, no B2 product-admin review, no live sandbox call

Review against Task 019 acceptance. Derive expectations from code/tests before reading result notes. Pay special attention to whether JWT identity really overrides crafted memberId, whether retry retains a safe payload, whether arbitrary server form fields can create DOM/script issues, whether payment return query data is non-authoritative, and whether callback accepted/rejected responses match the ECPay protocol without weakening signature checks. Existing evidence: backend focused 21/21 exit 0, frontend checkout 3/3 plus App 11/11 and production build PASS. End with exactly PASS, FAIL, BLOCKED, or NEEDS_ARCH_DECISION and report only concrete blocker defects with file/line and trigger.
