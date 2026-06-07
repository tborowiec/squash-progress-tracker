# CI Quality Gates Wiring — Plan Brief

> Full plan: `context/changes/quality-gates-ci/plan.md`
> Research: `context/changes/quality-gates-ci/research.md`

## What & Why

Wire GitHub Actions CI so every PR (and push to `main`) runs the full quality-gate set —
format/lint, compile, typecheck, and both test suites — and make a green run a **required status
check** on `main`. Render auto-deploys on merge to `main` independently of Actions, so the required
check is what actually blocks a red build from merging, and therefore from deploying. This is
Phase 4 of the test rollout.

## Starting Point

No CI exists (`.github/` is absent). Phases 1–3 already provide the suites: a backend Maven suite
(unit + Testcontainers Postgres integration, runnable with no secrets) and a frontend Vitest runner
(+ biome lint + tsc typecheck). Render's auto-deploy on merge is currently gated by nothing.

## Desired End State

A single `.github/workflows/ci.yml` runs five jobs on every PR and main push — `backend-lint`,
`backend-test`, `frontend-lint`, `frontend-test`, and an aggregate `ci-success`. Branch protection
on `main` requires only `ci-success`. A normal PR is green and mergeable; a PR that breaks any gate
shows `ci-success` red and is blocked from merging.

## Key Decisions Made

| Decision               | Choice                                  | Why (1 sentence)                                                              | Source   |
| ---------------------- | --------------------------------------- | ----------------------------------------------------------------------------- | -------- |
| Gate breadth           | spotless + biome + compile + typecheck + both test suites | Mirror the pre-commit gates plus test-plan §5 set as required PR checks.       | Research |
| Branch protection      | `gh api` in plan + documented fallback  | The gate is actually enforced as part of this change, with a manual fallback.  | Plan     |
| Triggers               | `pull_request` + push to `main`         | Gates PRs and catches direct pushes; main run is what the required check binds to. | Plan |
| Job shape              | Separate lint jobs, parallel            | Each gate reports its own status; matches the two-toolchain split.             | Plan     |
| Required check         | Aggregate `ci-success` gate job         | Branch protection never changes when jobs are added/renamed — rename-proof.    | Plan     |
| Compile/typecheck      | Folded into the test jobs               | `mvnw test` already compiles; a separate compile job just repeats work.        | Plan     |

## Scope

**In scope:** `ci.yml` with 5 jobs + caching; `LLM_API_KEY` left unset (live tests self-skip);
branch protection requiring `ci-success`; verification that a red build is blocked.

**Out of scope:** container-smoke / browser-e2e gates (Phase 5); `render.yaml` deploy-step changes;
live-LLM tests in CI; stale `DATABASE_URL` doc reconciliation; self-hosted runners; new test code.

## Architecture / Approach

Two toolchains → parallel jobs on `ubuntu-latest`. Backend: `setup-java` (Temurin 21, maven cache) →
`spotless:check` / `mvnw test` (Docker-backed Testcontainers Postgres, no secrets). Frontend:
`setup-node` (v22.14.0, npm cache) → `npm ci` → `biome check` / `tsc --noEmit` + `vitest run`. A
final `ci-success` job `needs` all four and is the single required status check.

## Phases at a Glance

| Phase                | What it delivers                                  | Key risk                                                        |
| -------------------- | ------------------------------------------------- | -------------------------------------------------------------- |
| 1. CI workflow       | `ci.yml` with 5 jobs, green on a PR               | Testcontainers/Docker behavior on hosted runner; `if: always()` gate logic |
| 2. Branch protection | `ci-success` required on `main`, red build blocked | `ci-success` must report once before it's selectable; needs admin token |

**Prerequisites:** GitHub repo admin access (for branch protection); Phase 1 must run once before
Phase 2 can reference `ci-success`.
**Estimated effort:** ~1 session across 2 phases.

## Open Risks & Assumptions

- First cold run pulls the `postgres:17` image + full `~/.m2` — slower until caches warm.
- Branch protection requires a token with repo-admin scope; if unavailable, the documented manual
  repo-settings path is the fallback.
- Assumes GitHub-hosted `ubuntu-latest` keeps Docker preinstalled (true today).

## Success Criteria (Summary)

- Every PR runs all five checks; a green `ci-success` means a mergeable PR.
- A PR that breaks any gate shows `ci-success` red and cannot be merged (deploy blocked).
- Backend tests pass with no secrets; live-LLM smoke tests self-skip.
