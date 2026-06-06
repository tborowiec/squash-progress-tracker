---
change_id: quality-gates-ci
title: Wire CI quality gates — run both test suites + compile/typecheck on every PR
status: preparing
created: 2026-06-06
updated: 2026-06-06
archived_at: null
---

## Notes

Phase 4 of the test rollout (see `context/foundation/test-plan.md` §3). Goal:
run both test suites + compile/typecheck in CI on every PR. Risks covered:
cross-cutting (gates over Phases 1–3). Test type: gates.

Wires the required gates from test-plan §5 that depend on Phases 1–3 existing:
- compile + typecheck (backend `javac`, frontend `tsc`)
- backend unit + integration
- frontend unit/component

CI is GitHub Actions (auto-deploy to Render on merge to `main`); per
tech-stack.md it is "planned but not yet wired" — this change wires the
PR-time gate, not the deploy step. Container-smoke + browser e2e gates are
out of scope here (they land in Phase 5).
