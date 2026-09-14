# Codex project instructions

Read CLAUDE.md for project conventions and docs/project-state.md for the latest verified state. Historical phase checklists are not proof that work remains undone.

## Coordination
- Codex owns task scope, acceptance criteria, diff review and verification; Claude Code executes bounded tasks.
- One writer per checkout. Use separate worktrees for parallel implementation.
- Preserve main. Develop from advanced-v2. Preserve pre-existing untracked files.
- Start each task with baseline commit, allowed scope and acceptance criteria in docs/tasks/.
- Do not mark work complete based only on an agent summary: inspect code and relevant test evidence.
- Distinguish static review, executed tests, skipped tests and environment blockers.
- Limit automatic repair to two rounds, then report the unresolved decision.
- Keep teaching explicit; otherwise add a short engineering concept note.
- Use scripts/invoke-claude.ps1 for read-only audits. It deliberately exposes only Read, Glob and Grep; implementation requires a separately scoped workflow.
- No automatic commit, push or merge during this pilot.
