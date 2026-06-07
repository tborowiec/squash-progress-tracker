---
date: 2026-06-06T00:00:00Z
researcher: Claude (Opus 4.8)
git_commit: 95dd7aba4b4be7b092c1545d6759ecf9fee95787
branch: main
repository: squash-progress-tracker
topic: "Phase 4 — Wiring CI quality gates (compile/typecheck + format-lint + backend & frontend test suites) on every PR, as a required check that blocks the Render auto-deploy"
tags: [research, codebase, ci, github-actions, quality-gates, testcontainers, vitest, spotless, biome]
status: complete
last_updated: 2026-06-06
last_updated_by: Claude (Opus 4.8)
last_updated_note: "Branch-divergence blocker resolved by the user — local main rebased onto origin/main (now 95dd7ab, 1 ahead / 0 behind); the frontend runner + Phase-3 context folder are present in the working tree. Adapted §Summary, §A, §F, Historical Context, and Open Questions accordingly."
---

# Research: Phase 4 — CI Quality Gates Wiring

**Date**: 2026-06-06
**Researcher**: Claude (Opus 4.8)
**Git Commit**: 95dd7aba4b4be7b092c1545d6759ecf9fee95787
**Branch**: main
**Repository**: squash-progress-tracker

## Research Question

Phase 4 of the test rollout (`context/foundation/test-plan.md` §3): wire CI quality
gates that run on every PR. Scope locked with the user:

- **Gate breadth**: test-plan §5 set (compile + typecheck, backend unit/integration,
  frontend unit/component) **plus** the format/lint gates (Spotless check + Biome check)
  mirrored from the pre-commit hooks.
- **Deploy coupling**: the green CI run should be a **required status check** that blocks
  merge to `main` (and therefore blocks the Render auto-deploy-on-merge) via branch
  protection.

Ground truth needed: exact commands per gate, what infra/secrets each needs, and whether
the suites can actually run today.

## Summary

**The gates are runnable and need no secrets. The branch-divergence blocker is RESOLVED.**

1. **RESOLVED — branch divergence.** The user rebased local `main` onto `origin/main`; it is now
   `95dd7ab`, **1 ahead / 0 behind** `origin/main` (`8c1dd63`). The Phase-3 frontend runner
   (Vitest 3 + RTL + jsdom, originally PR #20) is now present in the working tree:
   `frontend/package.json` has `"test": "vitest"` / `"test:run": "vitest run"` + the
   Testing-Library/jsdom devDeps, `frontend/vite.config.ts` carries the `test:` block, and the
   test files (`frontend/src/test/smoke.test.ts`, `frontend/src/components/ProtectedRoute.test.tsx`,
   `frontend/src/api/matches.contract.test.tsx`) and `context/changes/frontend-runner-bootstrap-tests/`
   are all on disk. **Phase 4 can now be authored on this branch — the frontend test gate has
   something to run.** (Original finding kept for the record in §A.)

2. **Backend gate needs only JDK 21 + a Docker daemon — no secrets.** 8 of 17 test classes
   are `@SpringBootTest` + Testcontainers `PostgreSQLContainer("postgres:17")` via
   `@ServiceConnection`; the rest are pure unit / MockWebServer. The LLM client boots with an
   empty key (no validation), and LLM-touching integration tests use `@MockitoBean LlmClient`.
   `./mvnw test` runs green on a clean runner with **no `DB_*` and no `LLM_API_KEY`**.

3. **Live-LLM smoke tests self-exclude correctly.** Both `*LiveSmokeTest` classes are gated
   by `@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")`. A plain
   `./mvnw test` with `LLM_API_KEY` unset auto-skips them — no Surefire exclusion needed. CI
   must simply **not** export a real `LLM_API_KEY`.

4. **All three frontend gates are runnable now.** `typecheck` (`tsc --noEmit`), `lint`
   (`biome check src`), and the test gate `npm run test:run` (`vitest run`) all run against the
   current working tree.

5. **No CI exists yet.** `.github/` is absent; everything is greenfield. `render.yaml` exists
   and auto-deploys on commit to `main` via Docker, healthcheck `/actuator/health`.

6. **GitHub issue #14** ("Test Rollout Phase 4: Quality-gates wiring (CI)") tracks this change;
   moved Todo → **In Progress** on the Squash MVP board at research start (per lessons.md rule).

## Detailed Findings

### A. The branch-divergence blocker (RESOLVED — verified directly)

**Resolution (2026-06-06):** the user reconciled local `main` with `origin/main`. Current state,
verified directly:

- `git rev-list --left-right --count main...origin/main` → `1  0` (local 1 ahead, 0 behind).
- Local `main` HEAD is `95dd7ab` ("CLAUDE.md and test-plan.md updated") — the former sibling
  docs commit now sits on top of `origin/main`'s history (a rebase/fast-forward, not a divergence).
- The frontend runner is in the working tree: `frontend/package.json` has `"test": "vitest"` /
  `"test:run": "vitest run"` + `@testing-library/{dom,jest-dom,react,user-event}`, `jsdom ^25`,
  `vitest ^3.2`; `frontend/vite.config.ts:1,7,10` carries `/// <reference types="vitest/config" />`,
  a `test:` block, and `setupFiles: ['./src/test/setup.ts']`.
- Frontend test files present: `frontend/src/test/smoke.test.ts`,
  `frontend/src/components/ProtectedRoute.test.tsx`, `frontend/src/api/matches.contract.test.tsx`
  (+ `frontend/src/test/setup.ts`). `context/changes/frontend-runner-bootstrap-tests/` now exists.

**Original finding (for the record):** at the start of research, local `main` (`a24dc8c`) was
**1 ahead / 6 behind** `origin/main` (`8c1dd63`); the Phase-3 runner had been merged via PR #20
onto `origin/main` only (commits `66c49cb`, `fbc8f79`, `c784c3c`, `2c0a8d7`, `429df5a`, `8c1dd63`)
and was absent locally. That sync gap — not deleted work — is what made the runner look missing.

**Implication for the plan:** no longer a blocker. Phase 4 is authored on the current `main`,
which contains the runner and all four prior-phase test artifacts.

### B. Backend test suite — inventory & infra (`src/test/java/.../`)

17 test classes total:

- **Pure unit (no Spring, no Docker, no net):** `llm/client/JsonSchemaFactoryTests.java:11`,
  `llm/client/OpenAiCompatLlmClientTests.java:25` (mock `RestClient`),
  `llm/LlmClientPropertiesTests.java:11`, `llm/LlmConventionsTests.java:10`,
  `match/gameplan/GamePlanPromptBuilderTests.java:13`, `match/MatchParsePromptBuilderTests.java:11`,
  `match/MatchParseServiceTests.java:25` (Mockito).
- **MockWebServer (loopback, no Docker):** `llm/client/OpenAiCompatLlmClientTimeoutTests.java:16`.
- **Testcontainers `@SpringBootTest` (need Docker):** `SquashProgressTrackerApplicationTests.java:10`,
  `security/SecurityFilterChainTests.java:16`, `user/AuthIntegrationTests.java:20`,
  `match/MatchApiIntegrationTests.java:23`, `match/MatchOwnershipBoundaryTests.java:35`,
  `match/MatchParseApiIntegrationTests.java:28` (+`@MockitoBean LlmClient:40`),
  `match/MatchNoMisSaveTests.java:29` (+`@MockitoBean:41`),
  `match/gameplan/GamePlanApiIntegrationTests.java:32` (+`@MockitoBean:44`).
- **Live-LLM smoke (opt-in):** `llm/client/LlmClientLiveSmokeTest.java:25`,
  `match/MatchParseLiveSmokeTest.java:22`.

All 8 integration classes share: `@Testcontainers` + `@Container @ServiceConnection static
PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")`. CI runner therefore
needs a working Docker daemon (GitHub-hosted `ubuntu-latest` has Docker preinstalled). There is
no H2/in-memory fallback — `spring.jpa.hibernate.ddl-auto=validate` + Flyway require real Postgres.

### C. Live-smoke exclusion mechanism (so CI doesn't trip it)

- `llm/client/LlmClientLiveSmokeTest.java:24` and `match/MatchParseLiveSmokeTest.java:21`:
  `@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")`.
- Not a `@Tag`, not `@Disabled`, not a Surefire `<excludes>`, not a Spring profile. They are
  Surefire-*included* by name (`*Test` matches the default pattern) and skipped purely by the
  annotation when `LLM_API_KEY` is unset/empty.
- **Plan rule:** the CI test job must leave `LLM_API_KEY` unset. Exporting a real key would
  silently flip on non-deterministic, cost-incurring live tests.

### D. Secrets / env matrix (`./mvnw test` needs none)

`src/main/resources/application.properties`:

| Property | Placeholder | Default | Line |
|---|---|---|---|
| `server.port` | `${PORT:8080}` | 8080 | :3 |
| `spring.datasource.url` | `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}` | **none** | :5 |
| `spring.datasource.username` | `${DB_USER}` | **none** | :6 |
| `spring.datasource.password` | `${DB_PASSWORD}` | **none** | :7 |
| `llm.api-key` | `${LLM_API_KEY:}` | empty | :16 |
| `llm.base-url` | `${LLM_BASE_URL:…/v1beta/openai}` | Gemini compat | :17 |
| `llm.model` | `${LLM_MODEL:gemini-2.5-flash}` | set | :18 |
| `llm.timeout` | `${LLM_TIMEOUT:30s}` | 30s | :20 |

- The no-default `DB_*` vars are **not** needed at test time: Testcontainers `@ServiceConnection`
  contributes a `JdbcConnectionDetails` bean that overrides `spring.datasource.*` before the
  datasource initializes.
- `LlmClientProperties` (`llm/client/LlmClientProperties.java:7-8`) is an unvalidated record;
  `LlmClientConfig.java:22-27` folds the (empty) key into a static `Authorization` header — the
  context boots keyless. Only an actual provider HTTP call would fail.
- `src/test/resources/` holds only `static/index.html` — no `application-test.*`, no
  `@DynamicPropertySource`.

**Env × stage:** `DB_*` and `LLM_API_KEY` are **runtime/deploy-only**; **none** are needed to
compile or to run `./mvnw test`. The single CI prerequisite is a Docker daemon.

### E. Maven / build specifics

- Java 21 (`pom.xml:30`); Spring Boot parent 4.0.6 (`pom.xml:8`).
- Maven wrapper 3.9.15, `distributionType=only-script` (`.mvn/wrapper/maven-wrapper.properties`);
  `mvnw`/`mvnw.cmd` present at root. Use `./mvnw` for version pinning.
- No explicit Surefire/Failsafe config — Boot-parent defaults; `*IntegrationTests` run under
  **Surefire during `test`** (no Failsafe execution). No `<groups>`/`<excludes>`.
- `frontend-maven-plugin` (`pom.xml:139`) binds `install-node-and-npm` / `npm ci` / `npm run
  build` to **`prepare-package`** (`pom.xml:149,157,165`), pinned to Node **v22.14.0**
  (`pom.xml:151`). Because `test` precedes `prepare-package`, **`./mvnw test` does NOT build the
  frontend** — the backend test job stays fast and Node-free.
- Spotless: `./mvnw spotless:check`, plugin 2.44.5 (`pom.xml:123`), palantir-java-format 2.68.0
  PALANTIR style over `src/main/java` + `src/test/java` (`pom.xml:126-133`). Frontend formatting
  is Biome, separate.

### F. Frontend gates

On the **current working tree** (`frontend/package.json`, post-reconciliation): scripts are
`dev/build/preview/lint/format/typecheck/test/test:run`. All three CI-relevant gates run today:

- **typecheck** — `npm run typecheck` = `tsc --noEmit -p tsconfig.json` (self-contained, no
  project references).
- **lint** — `npm run lint` = `biome check src` (Biome `2.4.16`, config `frontend/biome.json`).
- **test** — `npm run test:run` = `vitest run` (config in `frontend/vite.config.ts` `test:` block,
  `setupFiles: ['./src/test/setup.ts']`); 3 test files: `smoke.test.ts`, `ProtectedRoute.test.tsx`,
  `matches.contract.test.tsx`.

`frontend/package-lock.json` present and in sync → `npm ci` viable. No `.nvmrc`/`engines`; de-facto
Node = v22.14.0 (from pom). Use `vitest run` (not bare `vitest`, which watches) in CI.

### G. Render / deploy coupling (background for the "block deploy" decision)

- `render.yaml`: `runtime: docker` (`./Dockerfile`), `autoDeploy` on commit to `main`,
  `healthCheckPath: /actuator/health` (`render.yaml:10`). DB vars come from `fromDatabase`
  (`squash-db`, Postgres 17) as discrete `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`
  (`render.yaml:14-33`); `LLM_API_KEY` is `sync: false` (manual secret).
- **Doc divergence (note, do not act on here):** `infrastructure.md` (lines ~72,81,93,108) and
  `test-plan.md:74` describe mapping a `DATABASE_URL` (postgres://) into `SPRING_DATASOURCE_*`.
  The shipped `render.yaml` does **not** use `DATABASE_URL` — it injects the discrete `DB_*`
  vars the app already reads. The docs are stale relative to the blueprint.
- **"Gate + block deploy" path:** Render auto-deploys on *merge commit to main*, independent of
  GitHub Actions. To make the gate actually block the deploy, the green CI run must be a
  **required status check** under **branch protection on `main`** so a red build cannot merge
  (and thus cannot trigger the deploy). The plan must include the branch-protection step — the
  workflow alone does not block anything.

### H. Reusable tooling (`.tools/`, hooks)

- `.tools/install-formatter.sh` (palantir-java-format native binary, ~88 MB, gitignored),
  `.tools/install-lefthook.sh` (lefthook binary + git hook install), `.tools/spotless-staged.sh`
  (staged-file Spotless check used by lefthook). CI does **not** need the native binary — it can
  run the Maven goal `./mvnw spotless:check` directly (same source of truth, `pom.xml:117-118`).
- `.claude/hooks/format-java.sh` and `format-frontend.sh` are per-edit agent hooks — not needed
  in CI; CI re-checks via `spotless:check` / `biome check`.
- `.gitignore` ignores `target/`, `frontend/node_modules/`, `frontend/dist/`; `.dockerignore`
  also excludes `context/`, `.git/`, wrapper jars — good for cache scoping.

## Code References

- `pom.xml:30` — Java 21; `pom.xml:120-136` — Spotless (`spotless:check`, palantir 2.68.0);
  `pom.xml:137-171` — frontend-maven-plugin bound to `prepare-package`, Node v22.14.0.
- `src/main/resources/application.properties:5-7,16-20` — externalized DB (no defaults) + LLM (defaulted).
- `src/test/java/.../match/MatchOwnershipBoundaryTests.java:35-42` — canonical Testcontainers harness.
- `src/test/java/.../match/MatchParseApiIntegrationTests.java:40` — `@MockitoBean LlmClient`.
- `src/test/java/.../llm/client/LlmClientLiveSmokeTest.java:24` — `@EnabledIfEnvironmentVariable` gate.
- `frontend/package.json:6-13` (local) — scripts; no `test`. `frontend/biome.json` — lint/format config.
- `frontend/tsconfig.json` — `noEmit`, single config; `npm run typecheck` standalone.
- `render.yaml:4-35` — Docker runtime, autoDeploy on main, healthcheck, discrete DB vars.
- `Dockerfile:1-16` — multi-stage build (`mvnw clean package -DskipTests`), `EXPOSE 8080`.

## Architecture Insights

- **The suite is CI-friendly by construction:** secrets are mocked or defaulted, Postgres is
  Testcontainers, live tests self-skip. The only environmental dependency is Docker — which
  GitHub-hosted runners already provide. This keeps the gate deterministic and key-free.
- **Two independent toolchains:** backend (Maven, JDK 21, Docker) and frontend (Node 22, npm).
  Natural CI shape is two jobs (parallelizable). Backend `./mvnw test` deliberately avoids the
  Node toolchain (frontend build is `prepare-package`, not `test`).
- **Format/lint is single-sourced:** `spotless:check` (Java) and `biome check` (frontend) are the
  same checks the pre-commit lefthook runs — mirroring them in CI is a faithful belt-and-suspenders,
  not a new policy.
- **The deploy is not gated by anything today.** Making CI "block the deploy" is a
  branch-protection + required-check change, not just a workflow file. That distinction is the
  crux of the "Gate + block deploy" scope choice.

## Historical Context (from prior changes)

- `context/changes/bootstrap-verification/` — scaffold verification; recorded
  `ci_provider: github-actions`, `ci_default_flow: auto-deploy-on-merge`; CI "future action."
- `context/archive/2026-06-05-ownership-boundary-tests/` (Phase 1) — established the
  MockMvc + spring-security-test + Testcontainers harness this gate runs.
- `context/changes/ai-failure-path-tests/` (Phase 2) — added MockWebServer (`5.3.2`) for
  transient-error tests; `30s` LLM timeout vs `<5s` parse NFR.
- Phase 3 (`context/changes/frontend-runner-bootstrap-tests/`) — originally merged on
  `origin/main` (PR #20) and now present in the local tree after the user's reconciliation (§A).
  Provides the Vitest runner + route-guard + api-client contract tests this Phase-4 frontend gate
  runs. The test-plan §3 "complete" status now holds against local `main` too.

## Related Research

- `context/foundation/test-plan.md` §3 (rollout), §4 (stack), §5 (gates), §7 (exclusions).
- Prior phase artifacts under `context/archive/2026-06-05-ownership-boundary-tests/` and
  `context/changes/ai-failure-path-tests/`.

## Open Questions

1. ~~**Branch reconciliation.**~~ **RESOLVED** — local `main` rebased onto `origin/main`
   (`95dd7ab`, 1 ahead / 0 behind); the runner and all prior-phase artifacts are in the tree (§A).
2. **Runner choice:** GitHub-hosted `ubuntu-latest` (Docker preinstalled, simplest) vs anything
   self-hosted — almost certainly hosted; confirm in the plan.
3. **One workflow, two jobs vs two workflows:** likely a single `ci.yml` with parallel
   `backend` and `frontend` jobs; the plan should fix the job graph and caching (`~/.m2`,
   `frontend/node_modules` via `npm ci`).
4. **Branch-protection mechanics:** which check names become "required," and whether to configure
   protection via `gh api` in this change or document it as a manual repo-settings step.
5. **Maven cache key & `dependency:go-offline`:** whether to pre-warm `~/.m2` to keep the gate fast.
