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

## Codex review-only execution gate

This section overrides the general implementation, verification and automatic-repair rules below whenever `MODE: REVIEW_ONLY` is active.

- Codex must state the active mode before repository inspection. `MODE: REVIEW_ONLY` activates automatically when the user requests a review, when Codex receives a `CLAUDE → CODEX HANDOFF` with `STATUS: READY_FOR_REVIEW`, or when the current phase is independent validation. Only an explicit user instruction containing `MODE: IMPLEMENT` may authorize Codex implementation; phrases such as "finish", "make it pass", "fix everything" or "continue" do not change the mode.
- At the start of a review, record the task ID, baseline commit, allowed files, read limit, one optional target verification command and explicit exclusions. Default review budget: at most 5 core files read, 0 files changed, 0 repair rounds, 1 targeted verification command and 0 coverage commands unless the ticket explicitly requires independent coverage-gate verification.
- Codex may read the task specification and handoff, inspect Git status and the relevant diff, read directly affected production and test code, and run the single targeted verification command only when the supplied evidence is insufficient. Derive expected behavior from requirements and code before relying on the author's explanation.
- Codex must not modify production code, tests, fixtures, executable configuration or acceptance evidence; use `apply_patch` or filesystem-writing shell commands; add tests; fix a discovered defect; investigate or repair coverage; run a second test command after a failure; broaden verification into a module or repository-wide suite; or enter an edit → test → repair → retest loop.
- A failed verification command, missing acceptance evidence, discovered defect or insufficient coverage changes the outcome to `FAIL`, `BLOCKED` or `NEEDS_ARCH_DECISION`; it does not authorize repair. Codex must stop the review and emit one bounded `CODEX → CLAUDE CORRECTION TASK` containing the observed defect, expected behavior, evidence or reproduction, allowed files, acceptance condition, one required test and explicit exclusions.
- A review may end only with `PASS`, `FAIL`, `BLOCKED` or `NEEDS_ARCH_DECISION`. On exit, compare Git status with the recorded baseline. Any new reviewer-created repository change invalidates the review and must be reported as a role violation; preserve all pre-existing user changes.

Use this correction contract when a review does not pass:

```text
# CODEX → CLAUDE CORRECTION TASK

TASK_ID:
PARENT_TASK_ID:

## OBSERVED_DEFECT
## EXPECTED_BEHAVIOR
## EVIDENCE
## ALLOWED_SCOPE
## ACCEPTANCE
## REQUIRED_TEST
## OUT_OF_SCOPE
```

## Scope and context control
- At the start of every task, state one outcome, the affected module, the files that must be read and changed, and the minimum verification. Use progressive discovery: expand reads only when the next step cannot be completed with current evidence. Do not scan the repository to build a complete mental model first.
- Search in this order: known files, exact symbol/class/method, related package, affected module, and repository-wide search only when necessary. Read only the relevant method/class or file sections; do not repeatedly reload unchanged long files.
- Treat scope expansion as a follow-up. Record out-of-scope defects with location, severity and suggested next action, but do not fix them unless they directly block the current acceptance criteria. No opportunistic refactors, style cleanup, unrelated warnings or other-module fixes.
- One session should normally cover one phase: analysis, implementation, independent validation, or commit/push. Do not automatically chain a large analysis → implementation → review → regression → commit/push loop. When the next phase is substantial, produce a handoff and recommend a fresh session.
- Use context as a budget: 0–50K is normal; at 50–80K stop scope growth; at 80–100K finish only; at 100K+ stop expanding and write a concise handoff. If a new session rapidly exceeds 80–100K, report completed work, remaining work and the next-session split instead of continuing exploration.
- Default workflow: locate scope → read the minimum files → state a short plan → make the smallest necessary change → run the target test → expand verification only when risk or acceptance requires it → report changed files, tests and remaining risks. Do not infer that “complete,” “full review,” “full acceptance” or “check everything” authorizes an unrestricted repository sweep; first bound it to the named feature, phase, module, diff or acceptance criteria.
- Use the smallest necessary test tier: affected test, affected module tests, required integration test, then full regression only for broad/high-risk changes or final validation. On failure, isolate the first relevant root cause and run the next narrow test; do not repeat full suites with large logs. Summarize build output to the failure and relevant root cause rather than carrying full logs forward.
- Stop autonomous repair after two attempts on the same fix, after 2–3 non-converging test rounds, when substantial new modules would be needed, when scope changes, or when context reaches the high-risk threshold. Report the suspected root cause, attempts, evidence and the next decision.
- Do not run two heavyweight reviews, architecture investigations or large regressions in parallel. Parallel work is limited to small, clearly bounded, largely independent tasks, with one writer per checkout.
- Commit/push is not implicit. Do it only when implementation, required tests and review/validation are complete; otherwise leave a handoff for a clean session.

### Handoff format

For a large or paused session, create a concise handoff containing:

```text
Goal:      what was completed
Changed:   files modified
Validated: tests/checks run and results
Remaining: unfinished work
Risks:     known risks or blockers
Next:      the single task for the next session
```

Correctness comes before token efficiency, but correctness does not require unbounded exploration. Preserve evidence for executed tests, skipped tests and environment blockers separately.

## Runtime usage guardrails

Apply this protocol to every coding session:

1. **Classify the task before acting.**
   - Small: lookup, documentation, formatting, or an isolated change. Use the lowest sufficient reasoning level, targeted reads, and one focused check.
   - Medium: bounded multi-file change. Analyze and implement in this session; validation is limited to affected tests.
   - Heavy: full review, architecture analysis, concurrency/security work, large regression, or multi-phase delivery. Split into separate analysis, implementation, and validation sessions. Do not run another heavy task concurrently.
2. **Open a session gate.** Record: one goal, affected module, allowed files, change limit, target test, and explicit exclusions. If these cannot be stated, clarify or narrow the task before repository exploration.
3. **Use conservative limits.** Unless risk requires otherwise, start with at most 5 core files read, 3 files changed, 2 targeted test commands, and 1 repair round. Ask before expanding any limit. Keep command output to summaries; for failures retain the exit code, counts, first relevant error, and a narrow root-cause excerpt.
4. **Escalate deliberately.** Start at the lowest sufficient reasoning level. Increase it only when a concrete correctness issue remains. Do not use high reasoning for routine lookup, CRUD, DTO, mapping, formatting, or documentation work.
5. **Close the session at a milestone.** Report context/usage observations when available, changed files, checks and results, unresolved risks, and the next single task. Create the handoff before starting a new large phase. Do not continue merely to make the task feel “complete.”

### Stop and handoff triggers

Stop the current session and hand off when any one applies:

- the task needs a second substantial module or a new acceptance objective;
- the same repair has failed twice, or the same test has failed for 2–3 rounds without convergence;
- the planned file/read/test limit would be exceeded;
- context reaches 80K or the session rapidly approaches 80–100K;
- a second heavyweight review, validation or regression would be started in parallel.

The handoff must state what is proven, what is not proven, and the exact next bounded action. Never present an unexecuted test or an unreviewed change as complete.

### Next-session measurement

For the first three comparable trials, record where available: task class, model/reasoning level, start/end context, approximate tool calls, test rounds, scope expansions, and five-hour usage change. Use the results to adjust these guardrails; unavailable metrics must be recorded as unknown, not zero.

## Progress synchronization
- 更新專案進度時，必須同步更新本地 Markdown、Notion「進度追蹤」、「提示詞整理」中的「階段工作」表格，以及「執行順序與策略」與直接相關 Phase/task 頁面。
- 必須直接修改對應表格列的「狀態」與「驗收結果」及相關 checklist；只在頁尾追加更新 Log 不算完成同步。保留原始需求，驗收結果填寫已執行證據與尚缺項目。
- 區分已實作、已測試、待獨立審查、待驗收與已提交／合併；沒有完整證據不得標記整個 Phase 完成。
- 更新後回讀所有修改頁面，核對表格儲存格與相關狀態一致；同步失敗時明確列出未更新項目。
