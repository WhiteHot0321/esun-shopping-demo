# Verified project state

Updated: 2026-09-14
Baseline: advanced-v2, 9cd487c

- Spring Boot 3.3.5 / Java target 17; Vue 3 / Vite.
- Recent commits include Axios consolidation, stored procedure wiring, Testcontainers tests and the FK shared-lock-upgrade deadlock fix.
- .claude/agents/test-engineer.md exists. Older CLAUDE.md phase and agent descriptions are historical and need reconciliation against code before scheduling work.
- Existing untracked .claude/skills/ belongs to the user and is preserved.
- Claude Code native executable is available at ~/.local/bin/claude.exe and authentication was confirmed. Do not record account identifiers or credentials.
- Pilot: docs/tasks/001-order-concurrency-audit.md. Scope is a static coverage audit; runtime test status must be recorded separately.

## Next
Read-only pilot completed; the next proposed task is docs/tasks/002-deterministic-rollback-test.md (not yet implemented). Do not infer that every Phase 1 item is complete from this summary.

## Pilot verification result
- Codex ran mvn test on 2026-09-14: 24 passed, 0 failed/errors/skipped, real MySQL Testcontainers, Java 21 host runtime.
- Initial Claude invocation failed due to expired OAuth. After reauthentication, retry c5cd2183-c41d-4977-8a6d-e20e7281e909 succeeded. Codex reviewed the report and recorded corrections. Read-only delegation is verified; implementation delegation remains untested.
- See docs/tasks/001-order-concurrency-result.md for Codex's coverage review and proposed rollback-test strengthening task.

## Collaboration documentation
- Notion: [AI 開發協作｜Codex × Claude](https://app.notion.com/p/3db708da9f92819dbd00e7dfee4f5ab6). Contains the proposed model/effort/mode policy; implementation dispatch remains pending.

## Collaboration policy update — 2026-09-14 20:51 Asia/Taipei
- Executor: Codex. Policy/docs updated on advanced-v2 at HEAD ac9a19f; this update is uncommitted.
- Small reversible changes: one agent with proportional checks. Critical changes: independent cross-agent review. Codex and Claude may exchange implementation/review roles.
- AGENTS.md defines routing; CLAUDE.md points to it. Task 002 now records risk, roles, independent review and trial metrics.
- Documentation-only update; application tests not run. No new implementation trial completed; the read-only launcher remains unchanged.
- Next: execute Task 002 through a scoped implementation workflow, then compare reliability, user interventions, repair rounds and available cost across three completed comparable trials.
