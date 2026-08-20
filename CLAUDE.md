# esun-shopping — Advanced Version

Spring Boot + Vue shopping cart, originally an interview take-home (2026/4), now being
extended for **deep technical learning**, not just feature completion. Priorities: fix
known defects (Phase 1) → use Phase 1/2 as a training ground for backend/full-stack
practices → actually understand each decision → keep token usage efficient. In that order —
learning and correctness come before token efficiency, not after.

## Git strategy

- `main` — original interview submission, untouched.
- `advanced-v2` — all new development happens here.
- `.gitignore` is in place; `node_modules` / `backend/target` were previously tracked by
  mistake and have been removed from version control.

## Phase 1 (in progress) — fix known defects

1. Docker Compose / `application.yml` inconsistency (port/password) → env vars. **Done**
   (`9260f11`).
2. DB init script execution order wrong → rename to `01_schema` / `02_data` /
   `03_stored_procedures`.
3. Order ID uses second-level timestamp, collides on same-second orders → UUID or
   timestamp+random suffix.
4. All errors return HTTP 200 → `ResponseEntity` with correct status codes
   (400/404/409/500).
5. DTO validation gaps: `@DecimalMin`/`@Min` don't block null → add `@NotNull`;
   `payStatus` becomes an enum.
6. `sp_get_available_products` defined but never called, README inconsistent with it →
   wire it up or remove the dead code.
7. Frontend: axios installed but unused, `api.js` is dead code, `App.vue` uses raw
   `fetch` → unify on Axios with an env-based base URL.
8. `OrderService` queries each product twice (2n SELECT) → fetch once into a Map.
9. Multi-item stock deduction has no fixed lock order → possible deadlock under
   concurrency → order by `productId`.
10. `printStackTrace()` → SLF4J Logger.
11. README references a nonexistent `reset.sql`; `stand_price` should be renamed
    `unit_price`.

Full write-up and rationale: Notion — "Spring Boot MVC購物車專案(玉山2026/8)" (Phase 1/2
roadmap) and its linked Phase 1 task with 9 sub-tasks.

## Phase 2 (candidates, not yet prioritized)

Service-layer unit tests, inventory rollback tests, Testcontainers integration tests,
member table + JWT/session auth, `payStatus` backend enforcement, `App.vue` component
breakup + Vue Router + state management, GitHub Actions CI/CD, Swagger/OpenAPI, Redis
caching, optimistic vs. pessimistic locking comparison, DDD layering experiment.

**Do not start Phase 2 implementation before Phase 1 is reasonably complete**, unless
explicitly asked to jump ahead.

## Subagents

Defined in `.claude/agents/*.md` (not duplicated here — this file stays short since it
loads into every session).

| Agent | Model | Auto-delegate? | Use for |
|---|---|---|---|
| `project-explorer` | Haiku | Yes | Read-only lookups: locating files, tracing dependencies/impact scope |
| `implementation-engineer` | Sonnet | Yes (default) | Phase 1 fixes, Java/Vue/SQL/Docker/CI work, tests, normal review |
| `learning-mentor` | Sonnet | **No — explicit only** | Socratic walkthrough of a concept, not a direct fix |

Reserve Opus (manual `/model opus` for a single exchange, not a standing agent) for
genuinely deep trade-off analysis: concurrency/deadlock analysis, Redis architecture,
optimistic vs. pessimistic locking, DDD layering, auth architecture. Don't use it for
routine coding.

`test-engineer` and `architecture-reviewer` are deliberately not created yet — add
`test-engineer` (Sonnet) when Phase 2 reaches Testcontainers/regression work; only
formalize an architecture-reviewer agent if "which approach and why" conversations start
recurring, not before.

### Invoking learning-mentor

This agent never auto-triggers, even on requests that sound like they want teaching —
phrasing alone can't reliably distinguish "explain this to me" from "just fix it and tell
me why" (see `implementation-engineer`'s existing habit of a brief 2-3 sentence concept
note on every fix). Invoke it explicitly:

- `@agent-learning-mentor ...`, or
- prefix the message with `教學模式:` or `mentor:`

## Skills

Actually installed on this machine: `code-review-skill`, `generate-test-cases`,
`generate-tests`. Used ad hoc (e.g. during review, or when a fix needs a test written) —
not preloaded into any agent by default. `Codegraph`, `Graphify`, and a dedicated
brainstorming skill are **not installed** — don't reference them as if they exist; revisit
this section if they're added later.

## Context / token efficiency

- Prefer targeted reads over full-repo scans — most Phase 1 items only touch 1-3 files.
- Keep subagent summaries concise: what changed, files touched, build result, test
  result, concept demonstrated, next step.
- `/clear` when moving to an unrelated Phase 1 item, or switching Phase 1 → Phase 2.
- `/compact` when the current task's context is still relevant but has grown large.
- Don't re-scan the repo just to write a Notion update — reuse the current task's summary.

## Documentation template

After each Phase 1 item, produce this block (pasteable into Notion):

```markdown
## Task
## Problem
## Root Cause
## Solution
## Engineering Concept   ← what this demonstrates, for interview-story purposes
## Test Result
## Trade-offs (if any)
## Next Step
```

Pure status/TODO updates can be Haiku-level; the technical-note fields above need Sonnet.
Don't invoke Opus for documentation unless the entry is documenting an actual
architecture decision.
