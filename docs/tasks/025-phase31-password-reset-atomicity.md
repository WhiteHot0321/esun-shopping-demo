# Task 025 — Phase 3.1 #5 password-reset atomicity correction

Updated: 2026-09-19 Asia/Taipei  
Mode: IMPLEMENT  
Baseline: `8fa3096506f7a7566b01eccb0b0e894a4c168514` on `feature/frontend-ux-revamp`  
Risk: security / transaction / concurrency  
Implementer: Codex  
Independent reviewer: Terra (`password_reset_review`)
Correction commit: `50ce7f9`

## Scope and acceptance

- Make reset-token validation, one-time consumption and password replacement one transaction.
- Two concurrent reset requests using the same token must produce exactly one success and one
  rejection.
- Reject sequential replay and expired tokens without changing the password.
- Preserve all unrelated pre-existing working-tree changes.

## Implementation

- `PasswordResetTokenRepository.findValidByTokenHashForUpdate` uses the unique token hash to lock
  the valid token row with `SELECT ... FOR UPDATE`.
- `markUsedIfUnusedAndUnexpired` performs a second conditional claim and returns the affected-row
  count.
- Expiry generation and SQL comparison both use UTC (`ZoneOffset.UTC` / `UTC_TIMESTAMP()`), so
  application-host and MySQL session time zones cannot silently extend token validity.
- `AuthService.resetPassword` is `@Transactional`; it requires exactly one claimed row before
  updating the password. A failed claim or password update rolls the whole transaction back.
- Unit and MySQL integration tests cover failed claims, successful reset, sequential replay,
  expiration and same-token concurrent requests.

## Verification

- Java 17 production and test compilation: **PASS**.
- `AuthServiceTest`: **12/12 PASS**.
- Independent static security/transaction review: **PASS**, no blocking finding.
- First `AuthIntegrationTest` attempt was blocked before application startup because Docker Desktop
  could not initialize its local `dockerInference` runtime socket.
- After the user restored Docker Desktop, the first executed MySQL run exposed a real timezone
  defect: an application-host-local expired timestamp was still in the future relative to MySQL's
  UTC `NOW()`. Result: 17 tests, 16 passed and 1 failed (`expired token expected 400, got 200`).
  Repair round 1 standardized both sides on UTC.
- Final targeted rerun against a fresh MySQL 8 Testcontainer: **17/17 PASS**, 0 failures/errors/
  skipped, Maven exit 0. This includes successful reset, sequential replay rejection, expired-token
  rejection and the two-request same-token concurrency case (exactly one 200 and one 400).

Attempted command:

```powershell
cd backend
mvn '-Dtest=AuthServiceTest,AuthIntegrationTest' '-Djacoco.skip=true' test
```

## Review result

Static **PASS**. The reviewer confirmed that the public proxied `@Transactional` method covers
the locking read, conditional consume and password update; `BusinessException` and Spring data
access failures roll back by default. The unique `token_hash` equality lookup gives an exact
InnoDB row lock, so a waiting concurrent locking read re-evaluates `used_at IS NULL` after the
winning transaction commits.

## Remaining

Local project state and the required Notion tracking pages were synchronized and read back after
the correction commit. SMTP delivery, rate limiting and frontend component automation remain the
previously documented follow-ups, outside this correction.

## Metrics

- Task class: security/concurrency correction.
- Model/reasoning: coordinator; Terra medium independent review.
- Test rounds: one environment-blocked invocation, one executed MySQL invocation (16/17), and one
  final targeted MySQL rerun (17/17 PASS).
- Repair rounds: 1 (timezone consistency defect found by the expired-token integration test).
- User interventions: 2 (explicit `MODE: IMPLEMENT` authorization and Docker Desktop restart).
- Valid review defects: 0 after correction.
- Token/cost and five-hour usage change: unknown.
