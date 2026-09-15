# Project instructions

## When to use the ADHD skill

Do NOT force the `adhd` skill (`.claude/skills/adhd/SKILL.md`) on every
request. Let its own built-in Pre-flight self-judge (open-ended? /
high-stakes? / open phrasing?) decide, as designed — most work in this
repo is narrow CRUD on a small Product/Order demo and has one obvious
answer, so ADHD's ~10-call, 30-90s divergent loop is overkill for it.

**Good fit for ADHD** — genuinely open design decisions with several
viable approaches:
- Designing a feature that doesn't exist yet (cart, checkout, payment,
  login/auth) — new modules with many possible shapes.
- How to decompose `frontend/src/App.vue` (currently one 286-line file
  holding nearly all UI logic) into components, and whether to add
  routing/state management.
- API versioning / backward-compatibility strategy for breaking changes.
- New table/DTO schema design, or extending the Order–Product model
  (e.g. multi-item orders, schema evolution).
- **Concurrency and race conditions**: stock-decrement under concurrent
  load (pessimistic lock vs optimistic lock vs queue), transaction
  isolation levels, distributed lock tradeoffs — known problem type but
  requires choosing an approach.
- **Security design decisions**: login token strategy (JWT vs session vs
  OAuth), password/API key storage, authorization model, CSRF protection
  — multiple viable approaches with security implications.
- Fuzzy bugs with no known root cause (e.g. intermittent stored-procedure
  transaction failures).
- Performance/caching strategy, pagination design tradeoffs.

> **Note:** This checklist reflects the current scope and decision types
> in this project. If new modules are added or you discover decision
> categories this list is missing, come back and expand it — don't
> assume it's exhaustive.

**Skip ADHD, answer directly** — closed or routine work:
- Fixing a bug with a known cause (typo, null pointer, missing
  `@Transactional`).
- Adding a new CRUD endpoint that follows the existing
  controller/service/repository pattern.
- Explaining existing code, API lookups, syntax questions.
- The user's phrasing is already convergent ("quick", "just do the
  standard thing", "this is fine").

To force it anyway for a specific ask, type `/adhd` explicitly — the
skill's own Pre-flight Step 1 lets an explicit invocation through
without the self-judge gate.
