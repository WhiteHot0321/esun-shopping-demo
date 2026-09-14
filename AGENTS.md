# Codex project instructions

Read CLAUDE.md for project conventions and docs/project-state.md for the latest verified state. Historical phase checklists are not proof that work remains undone.

## Coordination
- Codex normally coordinates scope and acceptance; either Codex or Claude Code may implement according to task difficulty, available context and observed results.
- Prefer coordinator execution for work that can be completed reliably without delegation. Small reversible changes use one agent and proportional verification, without mandatory delegation or a second reviewer.
- Default model routing is Luna at low or the minimum sufficient reasoning level for file lookup, local code/document changes, DTO/entity/mapping/validation, commands, formatting, builds and tests. Use Terra once for bounded cross-file business logic, transactions, concurrency, database consistency, security or moderate refactoring that genuinely needs a worker. Use Sol only after a reasonable Terra attempt still fails on architecture, concurrency/race, difficult debugging, security-sensitive design, or when explicitly requested; announce the bounded escalation reason first.
- Parallel worker policy: default to one delegated worker at a time. Up to two delegated workers may run concurrently only when their tasks are substantially independent and parallelism materially reduces wall-clock time. Good reasons include different modules/files with little overlap, implementation alongside an independent bounded task, or tasks that can be completed and merged/reviewed independently. Do not parallelize shared-context inspection, dependent tasks, duplicate problem solving, overlapping file changes, or tasks limited to tests, git commands, builds, formatting, documentation or log inspection. Prefer the plan with the best ratio of useful engineering progress to token consumption. Normally use one worker; justified parallel work is capped at two; more than two requires explicit user approval. The coordinator may perform lightweight deterministic work while workers run, but should not duplicate a full AI analysis of code under active worker analysis. Workers must not spawn workers.
- Critical order, inventory, payment, authorization, transaction and concurrency changes require independent review by the other agent. The reviewer derives expected behavior from requirements and inspects code/tests before reading the author's explanation.
- Assign implementer and reviewer per critical task; roles may be exchanged. Resolve disagreements with reproducible evidence, not agent seniority.
- One writer per checkout. Use separate worktrees for parallel implementation.
- Preserve main. Develop from advanced-v2. Preserve pre-existing untracked files.
- For delegated or critical tasks, record baseline commit, scope, risk, implementer, reviewer and acceptance criteria in docs/tasks/. Small direct changes only need a concise existing state/result entry.
- Do not mark work complete based only on an agent summary: inspect code and relevant test evidence.
- Distinguish static review, executed tests, skipped tests and environment blockers.
- Limit automatic repair to two rounds, then report the unresolved decision.
- For implementation, run affected tests first and avoid repeated full suites. Run one final full verification only when the bounded task is nearly complete, has broad impact, or the user requests it. Documentation-only changes do not require executable test suites unless they alter executable configuration.
- Use deterministic shell/tools for search, status, diff, tests, builds and formatting; keep command output limited to relevant evidence and exclude generated files, dependencies and large logs from context.
- Read only the task specification and relevant files first; do not scan the whole repository without a dependency-based reason. If context becomes large, finish the current bounded task and create `docs/handoff/<task-name>.md` containing completed work, decisions, changed files, commands/results, unresolved issues, next task and constraints before starting a new session.
- For important features, the normal review flow is implementation → targeted verification → one independent review → blocker repair and narrow re-verification only if needed. Do not repeat broad reviews or full suites without concrete, reproducible evidence.
- Stop when acceptance criteria, required verification, review and requested documentation are complete. Do not autonomously refactor unrelated code, add optional tests or start another feature; record such work as an optional follow-up.
- Keep teaching explicit; otherwise add a short engineering concept note.
- Record elapsed time, user interventions, repair rounds, valid review defects and available token/cost evidence in existing task results (unavailable means unknown, never zero). Reassess roles after three completed comparable trials using reliability, user effort and cost.
- Use scripts/invoke-claude.ps1 for read-only audits. It deliberately exposes only Read, Glob and Grep; implementation requires a separately scoped workflow.
- No automatic commit, push or merge during this pilot.
