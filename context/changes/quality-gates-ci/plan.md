# CI Quality Gates Wiring Implementation Plan

## Overview

Wire GitHub Actions CI so every pull request (and every push to `main`) runs the full
quality-gate set: format/lint, compile, typecheck, and both test suites — and make a green
run a **required status check** on `main`. Because Render auto-deploys on merge to `main`
independently of Actions, the required check (via branch protection) is what actually blocks
a red build from merging and therefore from deploying.

This is Phase 4 of the test rollout (`context/foundation/test-plan.md` §3, §5).

## Current State Analysis

- **No CI exists.** `.github/` is absent; this is greenfield (research §A, §F).
- **The suite is CI-friendly by construction (research §B–E):**
  - Backend: `./mvnw test` compiles then runs 17 test classes. 8 are `@SpringBootTest` +
    Testcontainers `PostgreSQLContainer("postgres:17")` via `@ServiceConnection` — they need a
    Docker daemon (preinstalled on GitHub-hosted `ubuntu-latest`) but **no secrets**. The LLM
    client boots keyless; LLM-integration tests use `@MockitoBean LlmClient`.
  - Two `*LiveSmokeTest` classes are gated by
    `@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")` — they auto-skip when
    `LLM_API_KEY` is unset. **CI must leave `LLM_API_KEY` unset.**
  - `DB_*` and `LLM_API_KEY` are runtime/deploy-only; **none** are needed to compile or test.
  - Frontend (`frontend/package.json`): `lint` = `biome check src`, `typecheck` =
    `tsc --noEmit -p tsconfig.json`, `test:run` = `vitest run`. `package-lock.json` is in sync →
    `npm ci` viable. De-facto Node = **v22.14.0** (from `pom.xml` `frontend-maven-plugin`); no
    `.nvmrc`/`engines`.
- **Format/lint is single-sourced** — `./mvnw spotless:check` (Java, `pom.xml`) and `biome check`
  (frontend) are the same checks the pre-commit lefthook runs; mirroring them in CI is faithful,
  not a new policy (research §E, §H, Architecture Insights).
- **`./mvnw test` does NOT build the frontend** — the `frontend-maven-plugin` binds to
  `prepare-package`, which runs after `test`. The backend job stays Node-free and fast (§E).
- **Render auto-deploys on commit to `main`** via `render.yaml`, independent of Actions (§G). The
  deploy is gated only by branch protection: a required status check that blocks merge.
- **Repo:** `tborowiec/squash-progress-tracker`. GitHub issue #14 tracks this change (In Progress).

## Desired End State

A single `.github/workflows/ci.yml` runs on every PR and every push to `main`, with five jobs:
`backend-lint`, `backend-test`, `frontend-lint`, `frontend-test`, and an aggregate `ci-success`
that depends on the other four. `ci-success` is configured as the **only** required status check
on `main` via branch protection. Verification: a normal PR shows all checks green and is
mergeable; a PR with a deliberately-broken gate shows `ci-success` red and is blocked from merging.

### Key Discoveries:

- Backend integration tests need Docker only — no secrets (research §B, §D).
- Live-LLM tests self-skip on unset `LLM_API_KEY` — no Surefire exclusion needed (§C).
- `./mvnw test` already compiles; a separate compile job would just repeat work (§E) — so the
  "compile + typecheck" gate folds into the test jobs.
- Node is **v22.14.0** to match the `frontend-maven-plugin` pin (`pom.xml`).
- An aggregate gate job makes branch protection rename-proof — only `ci-success` is "required",
  so adding/renaming underlying jobs never forces a protection update (Architecture Insights, §G).

## What We're NOT Doing

- **No container-smoke or browser-e2e gates** — those are Phase 5 (`change.md`, test-plan §3).
- **No deploy-step changes** — `render.yaml` auto-deploy is untouched; we only gate the merge that
  triggers it.
- **No live-LLM tests in CI** — `LLM_API_KEY` stays unset so `*LiveSmokeTest` self-skip.
- **No reconciliation of the stale `DATABASE_URL` docs** (`infrastructure.md`, `test-plan.md:74`) —
  noted in research §G, out of scope here.
- **No self-hosted runners** — GitHub-hosted `ubuntu-latest` only.
- **No new test code** — Phases 1–3 already provide the suites this gate runs.

## Implementation Approach

Two independent toolchains → parallel jobs. Each gate that the user wants visible as its own
status is its own job:

- `backend-lint` — `./mvnw spotless:check`
- `backend-test` — `./mvnw test` (compiles + unit + Testcontainers integration; Docker present;
  `LLM_API_KEY` unset)
- `frontend-lint` — `npm ci` + `npm run lint` (`biome check src`)
- `frontend-test` — `npm ci` + `npm run typecheck` (`tsc --noEmit`) + `npm run test:run` (`vitest run`)
- `ci-success` — `needs: [backend-lint, backend-test, frontend-lint, frontend-test]`; the single
  required status check.

Caching: `actions/setup-java` with `cache: maven` (`~/.m2`); `actions/setup-node` with
`cache: npm` keyed on `frontend/package-lock.json`. Phase 1 lands and proves the workflow green;
Phase 2 wires branch protection and proves it blocks a red build.

## Critical Implementation Details

- **Workflow runs before any check exists in branch protection.** The required-check name
  (`ci-success`) only becomes selectable in branch protection *after* the workflow has run at
  least once and reported that check. Phase 1 must merge (or at least run on a PR) before Phase 2's
  `gh api` call can reference `ci-success`. Phases are ordered accordingly.
- **`LLM_API_KEY` must remain unset in the workflow env.** Setting it (even via a misfired
  secrets reference) silently enables non-deterministic, cost-incurring `*LiveSmokeTest` runs
  (research §C). Do not add it to any job's `env`.
- **`npm run test:run`, never bare `vitest`** — bare `vitest` watches and would hang CI (§F).

## Phase 1: CI Workflow

### Overview

Author `.github/workflows/ci.yml` with the four gate jobs plus the `ci-success` aggregate, wired
to run on PRs and pushes to `main`, and prove every check goes green on a PR.

### Changes Required:

#### 1. CI workflow file

**File**: `.github/workflows/ci.yml`

**Intent**: Define the PR/main quality gate as parallel jobs so each gate reports its own status,
plus a single aggregate job for branch protection to require. Keep the backend job Node-free and
secret-free; keep the frontend job on the pinned Node version.

**Contract**:
- `name: CI`; `on: { pull_request: {}, push: { branches: [main] } }`.
- `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }` so superseded runs on
  the same ref are cancelled.
- Jobs, all `runs-on: ubuntu-latest`:
  - **`backend-lint`** — `actions/checkout` → `actions/setup-java` (Temurin, JDK 21, `cache: maven`)
    → `./mvnw -B spotless:check`.
  - **`backend-test`** — checkout → setup-java (Temurin 21, `cache: maven`) → `./mvnw -B test`.
    No `env` block referencing `DB_*` or `LLM_API_KEY` (Docker + Testcontainers handle Postgres;
    live tests self-skip).
  - **`frontend-lint`** — checkout → `actions/setup-node` (node-version `22.14.0`, `cache: npm`,
    `cache-dependency-path: frontend/package-lock.json`) → `npm ci` → `npm run lint`, all with
    `working-directory: frontend` (or `cd frontend`).
  - **`frontend-test`** — same node setup/`npm ci` → `npm run typecheck` → `npm run test:run`.
  - **`ci-success`** — `needs: [backend-lint, backend-test, frontend-lint, frontend-test]`;
    `if: always()`; a step that fails unless every needed job succeeded (e.g. checks
    `contains(needs.*.result, 'failure') || contains(needs.*.result, 'cancelled')` → exit 1).
    This is the job branch protection will require.

  The `ci-success` `if: always()` + explicit failure check is the non-obvious part: without it, a
  skipped/failed dependency could leave the gate green. Snippet for the gate step:

  ```yaml
  ci-success:
    needs: [backend-lint, backend-test, frontend-lint, frontend-test]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - name: Verify all gates passed
        run: |
          if [ "${{ contains(needs.*.result, 'failure') || contains(needs.*.result, 'cancelled') }}" = "true" ]; then
            echo "One or more gate jobs failed."; exit 1
          fi
  ```

### Success Criteria:

#### Automated Verification:

- Workflow file is valid YAML / passes `actionlint` if available: `actionlint .github/workflows/ci.yml`
- On a PR, all five checks complete: `backend-lint`, `backend-test`, `frontend-lint`,
  `frontend-test`, `ci-success` — observable via `gh pr checks <pr>`.
- `backend-test` runs green with no `DB_*` / `LLM_API_KEY` exported (Testcontainers Postgres up,
  live-smoke tests skipped) — confirm in the job log.
- `frontend-test` log shows `tsc --noEmit` clean then `vitest run` passing (3 test files).

#### Manual Verification:

- A real PR shows the full check suite green and the PR reports as mergeable.
- Backend job log shows Docker/Testcontainers pulling `postgres:17` and the two `*LiveSmokeTest`
  classes skipped (not run).
- No secret-related warnings; `LLM_API_KEY` is absent from all job environments.

**Implementation Note**: After this phase and all automated verification passes, pause for manual
confirmation that the PR's checks are green before proceeding to Phase 2 (branch protection can
only reference `ci-success` after it has reported at least once).

---

## Phase 2: Branch Protection

### Overview

Make `ci-success` the required status check on `main` so a red build cannot merge (and therefore
cannot trigger the Render auto-deploy), via `gh api`, with the manual repo-settings steps
documented as a fallback. Then prove a red build is blocked.

### Changes Required:

#### 1. Apply branch protection via gh api

**File**: (no repo file — a documented `gh api` command run against `tborowiec/squash-progress-tracker`)

**Intent**: Require the `ci-success` status check on `main` and require branches to be up to date,
so merges are blocked until CI is green.

**Contract**: `gh api` PUT to
`repos/tborowiec/squash-progress-tracker/branches/main/protection` setting
`required_status_checks` with `strict: true` and `contexts: ["ci-success"]` (other protection
fields per repo policy — `enforce_admins`, `required_pull_request_reviews`, `restrictions` as
applicable; this change's responsibility is the required check). Requires a token with repo-admin
scope.

Document the equivalent manual path as fallback: **Settings → Branches → Add branch protection
rule** for `main` → **Require status checks to pass before merging** → select **`ci-success`** →
**Require branches to be up to date**.

#### 2. Record the protection step in the change

**File**: `context/changes/quality-gates-ci/change.md`

**Intent**: Note that branch protection on `main` requires `ci-success`, so the gate's enforcement
is discoverable and not lost as tribal knowledge.

**Contract**: A short line under `## Notes` stating the required check name and that it blocks the
Render auto-deploy.

### Success Criteria:

#### Automated Verification:

- Protection reports the required check:
  `gh api repos/tborowiec/squash-progress-tracker/branches/main/protection/required_status_checks`
  returns `contexts` containing `ci-success` and `strict: true`.

#### Manual Verification:

- A scratch PR that deliberately breaks one gate (e.g. a lint violation or a failing assertion)
  shows `ci-success` red and the **Merge** button blocked by the required check.
- After reverting the breakage, `ci-success` goes green and the PR becomes mergeable.
- Merging is confirmed to be the trigger that gates the Render deploy (no red build can reach
  `main`).

**Implementation Note**: After this phase and automated verification passes, pause for manual
confirmation that a red PR is actually blocked before closing the change.

---

## Testing Strategy

### Unit Tests:

- No new application tests — this change wires existing suites into CI. The "tests" here are the
  workflow's own behavior, verified via PR runs.

### Integration Tests:

- The backend `./mvnw test` Testcontainers suite runs inside `backend-test` as the integration
  surface; passing it on a clean GitHub-hosted runner with no secrets is the integration proof.

### Manual Testing Steps:

1. Open a PR from `feature/quality-gates-ci`; confirm all five checks run and go green.
2. Inspect `backend-test` log: `postgres:17` container started, `*LiveSmokeTest` skipped.
3. After Phase 2, push a commit that breaks one gate; confirm `ci-success` is red and merge is
   blocked.
4. Revert; confirm green and mergeable.

## Performance Considerations

- `~/.m2` (`cache: maven`) and npm (`cache: npm`) caching keep repeat runs fast; first run on a new
  cache key is cold (full dependency download + Testcontainers image pull).
- Four parallel jobs minimize wall-clock; `concurrency: cancel-in-progress` avoids piling up runs
  on rapid pushes.
- Backend job stays Node-free (frontend build is `prepare-package`, not `test`), so it doesn't pay
  the Node install cost.

## Migration Notes

- First-run ordering: `ci-success` must report once (Phase 1) before branch protection (Phase 2)
  can require it. Do not attempt Phase 2 before a Phase-1 run has completed on the repo.
- Branch protection is repo state, not code — re-running the plan won't re-apply it; the `gh api`
  call (or manual step) is one-time, idempotent on re-run.

## References

- Research: `context/changes/quality-gates-ci/research.md`
- Test rollout: `context/foundation/test-plan.md` §3 (rollout), §5 (gates), §7 (exclusions)
- Canonical Testcontainers harness: `src/test/java/.../match/MatchOwnershipBoundaryTests.java:35`
- Live-smoke gate: `src/test/java/.../llm/client/LlmClientLiveSmokeTest.java:24`
- Frontend gates: `frontend/package.json` (`lint`/`typecheck`/`test:run`)
- Deploy coupling: `render.yaml:4-35`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: CI Workflow

#### Automated

- [x] 1.1 Workflow file passes actionlint (if available) — aea8dfb
- [x] 1.2 All five checks complete on a PR (backend-lint, backend-test, frontend-lint, frontend-test, ci-success) — aea8dfb
- [x] 1.3 backend-test green with no DB_*/LLM_API_KEY (Testcontainers up, live-smoke skipped) — aea8dfb
- [x] 1.4 frontend-test shows tsc clean then vitest passing — aea8dfb

#### Manual

- [x] 1.5 Real PR shows full suite green and reports mergeable — aea8dfb
- [x] 1.6 Backend log shows postgres:17 pulled and *LiveSmokeTest skipped — aea8dfb
- [x] 1.7 No secret warnings; LLM_API_KEY absent from all job envs — aea8dfb

### Phase 2: Branch Protection

#### Automated

- [x] 2.1 required_status_checks contains ci-success with strict: true

#### Manual

- [ ] 2.2 Deliberately-red PR shows ci-success red and merge blocked
- [ ] 2.3 Revert makes ci-success green and PR mergeable
- [ ] 2.4 Confirmed no red build can reach main / trigger deploy
