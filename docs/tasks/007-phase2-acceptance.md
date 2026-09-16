# Task 007 — Phase 2 acceptance

- Baseline: advanced-v2 5574f05; isolated codex/phase2-acceptance at a804d42 (includes f64ffbd).
- Scope: service/rollback tests, JWT registration/login/protection, AuthPanel + ShopWorkspace, Router/Pinia, CI tests and Docker build. Phase 2.1/2.5/3 remain separate.
- Risk: authentication and checkout regression.
- Implementer: Codex. Independent reviewer: Claude Code, read-only via scripts/invoke-claude.ps1 after targeted checks.
- Acceptance: login/register success/errors; protected API 401/valid JWT; product/checkout interactions with retry/loading; real MySQL post-write rollback; >=80% OrderService/ProductService coverage; full backend/frontend tests/build; Docker build; inspect available remote CI evidence.
- Preserve uncommitted Phase 2.5 work. No automatic commit/push/merge.
- Started: 2026-09-15 17:03 Asia/Taipei (approximate). User interventions: initial request only. Repair rounds: 0. Token/cost: unknown.
