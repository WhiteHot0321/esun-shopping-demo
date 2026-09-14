# Task 001: Order concurrency and rollback coverage audit

Baseline: advanced-v2 at 9cd487c
Owner: Codex; auditor: Claude Code
Scope: static read-only audit of backend/src/main, backend/src/test, backend/DB and bench.

Read docs/project-state.md first. Existing phase descriptions can be stale.
Inspect actual source and tests. Do not edit files, run commands, access secrets/.env, invoke other agents or access external services.
Return a concise Traditional Chinese Markdown report with:
1. Coverage table: no negative inventory / no partial failed orders / overlapping baskets and deadlock regression. Cite repository-relative files and exact line numbers.
2. Concrete missing scenarios, ordered by impact. Distinguish proven defects from hypotheses and optional strengthening. Avoid inventing work if coverage already exists.
3. At most two small follow-up tasks with acceptance criteria.
4. State clearly that this is static analysis and no tests were executed by you.
Keep the answer under 1000 Chinese characters where practical.

Acceptance: evidence-backed report, independently spot-checked by Codex; source code unchanged; actual runtime verification recorded separately.
