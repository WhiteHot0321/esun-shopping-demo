# 053 — Phase 3.3 #18 B2 Flyway design and spike

Date: 2026-09-28 Asia/Taipei. Mode: IMPLEMENT, explicitly authorized by user.
Baseline: advanced-v2 @ 429346d94b8995d9025fb27ab1d722d9c764cee1.
Status: PASS — Task053-C1 correction, targeted runtime verification, and independent Claude review completed. This closes the bounded Flyway spike, not the remaining B2 topology or production deployment. Historical failures below are retained as evidence.

## Session gate

- Goal: prove safe empty-database initialization and explicit adoption of a supported existing schema before starting the rest of B2.
- Module: database initialization and migration; Critical (existing-data preservation).
- Implementer: Codex (Terra initial attempt; Sol low for the bounded correction after concrete Terra shortcomings). Independent reviewer: Claude Code, correction review PASS.
- Preserve all pre-existing MON-01–05 changes and untracked files. No commit, push, merge, deployment, or access to user databases.
- User approved the bounded expansion: read backend/DB SQL and directly related configuration; change at most six files; run at most two targeted test commands.
- Target test: `mvn '-Dtest=FlywayAdoptionIntegrationTest' '-Djacoco.skip=true' test` from backend. The final additional run was explicitly authorized and passed.
- Model/usage: inherited coordinator; bounded Sol low correction after Terra failed to converge, announced before dispatch. Final correction start: five-hour 83% remaining, weekly 34% remaining; user previously authorized continuing despite allowance. Exact context and total task cost unknown.

## Confirmed evidence

- Task 043 requires a Flyway design/spike before Compose/Caddy work. Notion #18 explicitly delegates detailed requirements to Task 043.
- Initial baseline had JDBC/MySQL dependencies but no Flyway dependency; this change adds flyway-mysql.
- Development docker-compose.yml mounts backend/DB into mysql initdb.d. This runs initialization on a new volume, not a versioned upgrade on an existing volume.
- 01_schema.sql drops multiple business tables; 04_member.sql drops member; 05_password_reset_token.sql drops password_reset_token. These cannot be replayed against user data as migrations.
- AbstractMySqlIntegrationTest initializes disposable MySQL using the legacy SQL chain. Existing integration-test success alone does not establish Flyway behavior.

## Implemented design

1. `db/migration/V1__baseline_schema.sql` is a consolidated full schema and stored-procedure baseline without development users/orders or destructive reset statements.
2. Flyway is opt-in (`FLYWAY_ENABLED=false` until the B2 production Compose path exists) and has `baseline-on-migrate=false` explicitly set. Empty enabled databases run V1 normally.
3. `shop.flyway.adopt-existing=true` is the explicit one-time existing-database path. `FlywayAdoptionConfiguration` compares a pinned trusted legacy structural fingerprint and checks metadata visibility before calling `baseline()`; mismatches refuse history creation. See the final correction result for covered invariants and offline operating requirements.
4. Reject unsupported or partially upgraded schemas without modifying business data. Do not replay legacy reset scripts or silently stamp an incompatible schema.
5. Prove empty creation, repeat migration, supported adoption with preserved representative data, and rejection of incompatible schema in disposable MySQL.
6. Keep the development initialization path intact until the new production path is verified. Compose must not run both legacy init scripts and Flyway initialization.

## Acceptance and remaining work

- [x] Complete schema inventory and choose the precise preflight/adoption interface.
- [x] Complete bounded adoption safeguards: structural fingerprint includes product_image, column definitions, indexes, constraints/enforcement, engines, routines/signatures, triggers/events; metadata visibility fails closed.
- [x] Green targeted integration verification: final authorized FlywayAdoptionIntegrationTest run passed 1/1, 0 failures/errors/skips, 75.10s, Maven exit 0; JaCoCo skipped. Prior failed setup attempts are retained in historical measurements and checkpoints below.
- [x] Independent read-only correction review PASS, no material blockers. Final report: `.git/codex-claude-runs/92206daf-d14c-4c7b-849e-fc6e924362ae/result.json`. The earlier FAIL report is retained at `.git/codex-claude-runs/f12adeb5-5050-4a25-bf85-f6fb0edc2865/result.json`.
- [x] Local Task053 current status, acceptance checklist and handoff updated; Notion progress, prompt index, historical stage-work status/acceptance cells, execution strategy, and both #18 pages synchronized to bounded Flyway PASS. Broader deployment remains incomplete. project-state.md was not changed to preserve the six-file limit; Task053 is the canonical result.

AI-support provider remains a separate pending user choice; it does not block this database design. B2 topology, B3 VM/secrets, real deployment, and C acceptance remain incomplete.

## Measurement

User interventions: explicit MODE: IMPLEMENT, permission to continue despite allowance, and bounded six-file/two-test expansion. Repair rounds: 2 (both Testcontainers fixture setup, no production behavior repair). Executed tests: two targeted attempts, both blocked before assertions by fixture configuration/privilege. Static compilation: `mvn -DskipTests test-compile` exit 0 (outputs reported up to date). Independent review: FAIL; two substantive defect groups (incomplete preflight and self-referential/insufficient acceptance tests), plus missing successful execution. Claude audit telemetry: 96.435 seconds, reported USD 0.19, 10 turns; this is tool-reported audit cost only, not total task/account cost. Overall elapsed time and token/cost allocation: unknown.

Engineering concept: a baseline records which schema version an existing database claims to have; it does not perform or verify the schema conversion itself.

## Independent review and next bounded action

Claude independently confirmed preflight-before-baseline and default opt-in ordering, but rejected the insufficient structural checks and self-generated legacy fixture. The coordinator also inspected code and the actual Surefire report (1 test, 0 failures, 1 setup error); the final fixture edit remains unexecuted. Worker: Terra, low reasoning, one worker; no parallel implementation. No application data was accessed or migrated. All six task-owned files remain uncommitted; unrelated AGENTS.md and untracked files are preserved.

### CODEX → CLAUDE CORRECTION TASK

TASK_ID: 053-C1
PARENT_TASK_ID: 053

#### OBSERVED_DEFECT
Existing-schema preflight accepts incompatible schemas with the expected subset of column names. It omits product_image and does not check column definitions, required indexes, unique/FK/check constraints, generated expressions, or stored procedures. The adoption test creates its legacy fixture using the new V1 itself. Neither test attempt reached successful assertions.

#### EXPECTED_BEHAVIOR
Only the supported actual legacy schema may be explicitly baselined. Incompatible structure must fail before history metadata or business-data mutation. Empty V1 must include the expected stored procedures and constraints; supported legacy adoption must preserve representative business data.

#### EVIDENCE
FlywayAdoptionConfiguration.java:48–76 checks table/column names only. FlywayAdoptionIntegrationTest.java:41–53 uses seed.migrate() for its legacy fixture. Surefire report: `backend/target/surefire-reports/com.esun.shop.config.FlywayAdoptionIntegrationTest.txt` — restricted account denied CREATE DATABASE. Independent review FAIL at the report path above.

#### ALLOWED_SCOPE
Next authorization/session: the existing FlywayAdoptionConfiguration.java and FlywayAdoptionIntegrationTest.java, plus this task record; read the legacy DB SQL and V1. No additional production modules.

#### ACCEPTANCE
Complete structural preflight including product_image; rejection tests for missing/changed required schema invariants with no history/data mutation; legacy fixture built from the actual legacy chain independently of V1; fresh migration routine assertions; one green targeted run; independent correction review.

#### REQUIRED_TEST
From backend: `mvn '-Dtest=FlywayAdoptionIntegrationTest' '-Djacoco.skip=true' test` against disposable MySQL only. Both originally authorized test commands have been used, so another run requires a renewed bounded allowance.

#### OUT_OF_SCOPE
Compose/Caddy, CI deployment, real databases, additional features, full regression/coverage, commit/push/merge. Do not continue automatic repair after the two fixture repair rounds already recorded.

## Handoff

Goal: completed Task053-C1 / bounded #18 B2 Flyway spike.
Changed: pom.xml, application.yml, FlywayAdoptionConfiguration.java, V1__baseline_schema.sql, FlywayAdoptionIntegrationTest.java, this Task053 (six files).
Validated: final targeted test 1/1 PASS, 0 failures/errors/skips, 75.10s; independent correction review PASS; compile and whitespace checks PASS. Earlier failures remain historical below.
Remaining: broader B2 topology and later B3/C; full regression/coverage and production deployment were not run in this bounded task.
Risks: one-time offline adoption only; supported metadata fingerprint is pinned to the verified legacy MySQL schema. Different versions/metadata fail closed. AI provider decision remains open.
Next: in a fresh bounded session, plan remaining B2 Compose/Caddy integration using this verified migration path; do not auto-start it during closeout.

## C1 resumed correction checkpoint — 2026-09-28

The user changed the goal to correcting the errors, testing, and closing out. MODE: IMPLEMENT and the six-file scope remain authorized. A renewed two-command targeted allowance was used in this correction round.

- Terra worker replaced the subset-name preflight with an information_schema SHA-256 fingerprint and replaced the self-generated legacy fixture with official MySQL initdb execution of the real DB SQL chain. This is still incomplete and not accepted.
- C1 attempt 1 failed while establishing the candidate fingerprint (the expected fingerprint was initially blank). This was not a successful acceptance run.
- C1 attempt 2: Surefire reports 1 test, 0 failures, 1 error, 72.58 seconds. MySQL refused restricted-user DROP PROCEDURE with SYSTEM_USER privilege required. The test reached fresh-V1 setup before failing in invalid-fixture construction, but did not complete legacy adoption assertions.
- The worker then changed invalid-fixture DDL to a disposable-container root connection; adoption still uses the restricted test account. This latest edit is unexecuted. It contains test-only disposable credentials, not user credentials.
- The worker terminated on a reported account usage limit. No live worker/test process is being awaited. No production data, commit, push or deployment was performed.

Coordinator inspection found additional required corrections before acceptance:

1. Reject unavailable/null routine definitions; hashing `<NULL>` can treat hidden bodies as equivalent. Define and test the read-only metadata privileges required for adoption.
2. Include routine parameter signatures and execution-relevant SQL mode/charset metadata, table engines, and same-schema FK targets. Existing routine-body-only and referenced-table-name-only checks are insufficient to prove compatible behavior.
3. Test each invalid structural mutation independently; the present combined unique-index/generated-column/routine mutation can mask missing checks. Include NOT ENFORCED checks (the ENFORCED metadata belongs to TABLE_CONSTRAINTS).
4. Revalidate the supported fingerprint from the known legacy chain after correcting canonicalization; never baseline an arbitrary observed fingerprint.

The user subsequently approved one more targeted run. The correction result and its limits are recorded below; this earlier checkpoint remains historical evidence.

## C1 bounded correction result — 2026-09-28

Status: **targeted verification PASS; independent correction review PASS**. This closes Task053-C1, not the broader B2 deployment.

- Added table engine/collation, referenced FK schema, routine body/signature/SQL mode/charset, trigger, and event metadata to the pinned fingerprint. Adoption now fails closed when routine definitions are hidden or TRIGGER/EVENT metadata visibility is not established; Flyway baseline version must be 1.
- Regenerated the pinned SHA-256 `1c5ee00817f71420e5b09983bdf9fb1fc572b6c4ace97a0a32d91ba77b2ae23c` from a separate disposable `mysql:8.0` container (resolved MySQL 8.0.46) running the checked-in `DB/` init chain. The manifest covered 17 tables, 128 columns, 66 index rows, 61 constraints, 21 checks, 14 FK rules, 3 routines, 6 routine parameters, and no triggers/events. It was generated from trusted SQL, not from the database under adoption.
- The integration test uses the actual legacy init chain. Fixture root grants `SHOW_ROUTINE` and performs only disposable structural mutations; adoption uses the restricted application account. It checks each unique-index, generated-expression, check enforcement, product-image column, missing-routine, routine-signature, and routine-body mutation independently, with no Flyway history or product-data change after rejection. It also confirms hidden routine definitions fail closed, fresh V1 installs three procedures, repeat migration is a no-op, and supported adoption preserves product data.
- `mvn -DskipTests test-compile -q`: exit 0. Authorized single target run: `mvn '-Dtest=FlywayAdoptionIntegrationTest' '-Djacoco.skip=true' test`: **BUILD SUCCESS**, 1 test, 0 failures/errors/skips, 75.10 seconds for the test class. JaCoCo was explicitly skipped. `git diff --check`: exit 0. No full regression or coverage run was claimed.
- This is a one-time **offline** adoption path. Stop application writers and schema DDL before preflight and keep them stopped through `baseline()`/`migrate()`; the metadata check and baseline are not atomic against concurrent DDL. MySQL versions or grants producing different metadata fail closed and require separate validation rather than auto-learning a new fingerprint.
- Baseline `429346d94b8995d9025fb27ab1d722d9c764cee1`; implementation remains uncommitted. No user database, Compose service data, commit, push, merge, or deployment was touched. Claude independently inspected code and current Surefire evidence and returned PASS, no reproducible material blocker. Its non-blocking note is that the empty migration test calls Flyway directly rather than the Spring strategy bean; no Spring end-to-end/full-suite claim is made.

Measurement: one additional user authorization for a single targeted run; this correction used one acceptance test command and no test repair loop. Elapsed total task time, context, and account-level token/cost evidence remain unknown; prior concrete Terra worker attempt and two earlier failed fixture attempts are recorded above.

Final independent-review telemetry: 183.642 seconds, reported USD 0.53, 24 turns; audit cost only, not account/task total. Reviewer performed no edits or test commands. Its report included additional legacy SQL cross-checks beyond the requested five core files; this was read-only scope expansion, recorded here rather than presented as strict read-budget compliance.
