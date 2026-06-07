---
change_id: quality-gates-ci
title: Wire CI quality gates — run both test suites + compile/typecheck on every PR
status: archived
created: 2026-06-06
updated: 2026-06-07
archived_at: 2026-06-07T20:12:43Z
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

Branch protection on `main` requires the `ci-success` status check (strict,
branches must be up to date) — this blocks any merge that does not pass all
four gate jobs, and therefore blocks the Render auto-deploy for red builds.
Repository visibility was set to public (required for branch protection on
GitHub Free). Applied via `gh api PUT .../branches/main/protection`.
