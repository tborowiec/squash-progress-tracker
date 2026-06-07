# Phase 5: Container Smoke + Critical-Flow E2E Implementation Plan

## Overview

Close the last gap in the phased test rollout (`context/foundation/test-plan.md` §3, risk #6 — build/deploy parity). We build the half of Phase 5 that does not yet exist: a **container-smoke harness** (a committed bash script that builds and runs the *Docker image* against a throwaway Postgres and HTTP-asserts the running artifact) and the **CI jobs** that gate on it and on the already-written browser e2e. We finish by correcting two factual errors in the test plan that the research uncovered.

The e2e half — the Playwright runner, `auth.setup.ts`, and `log-match.spec.ts` — was already landed in commit `159dc37` and is verified against the live code. This change does not rewrite it; it only *gates* it in CI.

## Current State Analysis

What exists today (from `context/changes/container-smoke-e2e-tests/research.md`):

- **Docker image builds correctly.** `Dockerfile:1-16` is multi-stage: Maven build stage runs `./mvnw -q -B clean package -DskipTests` with both `src/` and `frontend/` copied in; frontend-maven-plugin (`pom.xml:138-171`) builds the SPA into `target/classes/static` at `prepare-package`; runtime stage runs `java -jar app.jar`, `EXPOSE 8080`.
- **Deploy wiring uses five discrete DB vars, not `DATABASE_URL`.** `application.properties:5-7` composes the JDBC URL from `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`; `PORT` (default 8080) drives `server.port` (`:3`). `render.yaml:14-33` sources the `DB_*` from `fromDatabase`. There is no `DATABASE_URL` anywhere.
- **Flyway self-migrates.** `V1__create_users.sql` + `V2__create_matches.sql` are packaged into the jar and run on boot before Hibernate `validate`. A fresh throwaway Postgres needs no seed step.
- **The only curl-observable auth gate is `401` on a protected `/api/**` path.** Client routes (`/history`) return `200 index.html` (permitAll + SPA forward, `SpaForwardingConfig.java:17-21`); the "redirect to login" is a React Router `<Navigate>`, invisible to curl. `GET /api/auth/me` returns `401` via the custom `authenticationEntryPoint` (`SecurityConfig.java:41-45`).
- **No container-smoke harness exists.** `run-local.sh:40-44` builds the frontend then runs the app via `./mvnw spring-boot:run` (Postgres in Docker, app on the *host*). It never builds or runs the app *image* — so nothing today exercises the containerized artifact. That is the Phase 5 gap.
- **CI has four jobs + an aggregator.** `.github/workflows/ci.yml`: `backend-lint`, `backend-test`, `frontend-lint`, `frontend-test`, and `ci-success` with `needs: [backend-lint, backend-test, frontend-lint, frontend-test]` (`:67`). No container-smoke or e2e job.
- **Playwright is fully wired.** `frontend/playwright.config.ts` has a `setup` project (`auth.setup.ts`, API login → `storageState`) and a `chromium` project that replays it; `retries: 2` under CI; **no `webServer`** — it targets an already-running app at `BASE_URL = E2E_BASE_URL ?? 'http://localhost:8080'` (`frontend/e2e/helpers/auth.ts:12`). `log-match.spec.ts` mocks only `POST /api/matches/parse` at the browser edge (`:75-90`).

## Desired End State

After this change:

1. A developer can run `./smoke-container.sh` locally and watch it build the image, boot it against a throwaway Postgres, assert the three HTTP facts, print a clear pass/fail, and tear everything down — and `./stop-smoke-container.sh` cleans up any leftovers.
2. Every PR runs a `container-smoke` CI job (image built once, smoke run against it) and an `e2e` CI job (same image, browser happy-path). `ci-success` fails if either fails. The `e2e` job consumes the image the smoke job produced (build once, share via artifact).
3. `context/foundation/test-plan.md` no longer claims "gated redirect" or `DATABASE_URL` — it states the corrected facts.

**How to verify:** `./smoke-container.sh` exits `0` locally with all three assertions green; a PR shows `container-smoke` and `e2e` as required checks under `ci-success`; `grep -n "DATABASE_URL\|gated redirect" context/foundation/test-plan.md` returns nothing.

### Key Discoveries:

- DB mapping is discrete `DB_*`, not `DATABASE_URL` (`application.properties:5-7`) — the harness must inject five vars.
- The curl-observable gate is `401` on `GET /api/auth/me`, not a `3xx` (`SecurityConfig.java:41-45`; smoke surface table in research §C).
- Flyway self-migrates a fresh Postgres on boot (`pom.xml:56-61`, `db/migration/`) — no seed step.
- The Linux `host.docker.internal` trap applies once the app runs as a container reaching a sibling Postgres container (`lessons.md`); put both on a shared `docker network` and reference Postgres by container name.
- The e2e spec already exists and is correct (`frontend/e2e/log-match.spec.ts`); CI only needs to *gate* it, targeting an already-running image via `E2E_BASE_URL` (no `webServer` in `playwright.config.ts`).
- The image build is the slow part (Node install + `npm ci` + Vite build + jar); building it once and sharing via `docker save`/artifact halves CI wall-clock vs building in both jobs.

## What We're NOT Doing

- **Not** rewriting or extending the existing Playwright spec / runner — it is already landed and verified.
- **Not** adding a live-LLM e2e variant. The browser-edge `page.route` parse mock stays; the real Gemini parse path is covered by JVM `@MockitoBean LlmClient` tests and opt-in `*LiveSmokeTest`. §7 of the test plan forbids gating CI on live LLM.
- **Not** adding a server-side LLM bypass/mock bean — none exists and we don't introduce one; the determinism boundary stays at the HTTP edge.
- **Not** broadening e2e beyond the single login → log match → history path. Breadth belongs in the cheaper component/integration layers.
- **Not** touching `render.yaml` or the Dockerfile — they are already correct for the discrete `DB_*` wiring.
- **Not** changing Surefire/failsafe config or introducing a Testcontainers-based image smoke (we chose plain `docker run`, mirroring prod).

## Implementation Approach

**Container smoke = a committed bash script, run identically locally and in CI.** Plain `docker build` + `docker run` mirrors what Render actually does, runs the same on a laptop and in GitHub Actions, and reuses the `run-local.sh` idiom the repo already has. The script creates a dedicated docker network, starts a throwaway `postgres:17` on it, builds and runs the app image on the same network (referencing Postgres by container name to sidestep the Linux `host.docker.internal` trap), polls `/actuator/health` until UP (bounded retries — never `sleep`-and-pray), runs three `curl` assertions, then tears everything down. A sibling `stop-smoke-container.sh` force-cleans containers/network so reruns and crashed runs don't collide.

**CI builds the image once and shares it.** The `container-smoke` job builds the image, runs the harness, then `docker save | gzip` → `upload-artifact`. The `e2e` job `needs: container-smoke`, downloads + `docker load`s that exact image, boots it with a Postgres service, points Playwright at it via `E2E_BASE_URL`, and runs `npm run e2e`. This guarantees the e2e exercises the same artifact the smoke approved, and pays the multi-minute image build only once.

**Doc correction is a final, isolated edit** to keep the foundation truthful for the next reader.

## Critical Implementation Details

- **Linux container networking** — the app container must reach Postgres by **container name on a shared user-defined docker network**, not `localhost`/`host.docker.internal` (which does not resolve inside Linux containers — see `lessons.md`). Create the network first; attach both containers; set `DB_HOST=<postgres-container-name>`.
- **Health-poll, never sleep** — Flyway migration + Spring Boot startup take a variable few seconds. Poll `GET /actuator/health` for `{"status":"UP"}` with a bounded retry/backoff loop and a hard timeout that fails loudly (dump `docker logs` on timeout). The DB health indicator drives the aggregate status, so a green health implicitly confirms the DB connection.
- **The smoke must not hit the live LLM** — assert only `health`, `/`, and `GET /api/auth/me` (401). None of these touch `/api/matches/parse`, so no Gemini call and no `LLM_API_KEY` is needed. Pass an empty/dummy key so the app boots without one.
- **Image/jar name** — no `<finalName>` override, so the jar is `squash-progress-tracker-0.0.1-SNAPSHOT.jar` and `Dockerfile:14` already matches; tag the built image with a stable local name (e.g. `squash-app:smoke`) so both the smoke run and the `docker save` reference it.

## Phase 1: Container-Smoke Harness (bash, local + CI)

### Overview

A committed `smoke-container.sh` (plus `stop-smoke-container.sh`) that builds the Docker image, runs it against a throwaway Postgres on a shared network, polls health, asserts the three HTTP facts, and cleans up — runnable on a dev box and later invoked by CI.

### Changes Required:

#### 1. Container-smoke script

**File**: `smoke-container.sh` (repo root, sibling of `run-local.sh`)

**Intent**: Build + boot the app *image* against a throwaway Postgres and prove the running artifact serves the app, with deterministic setup/teardown so it's safe to re-run.

**Contract**: A `set -euo pipefail` bash script that:
- Creates a dedicated user-defined docker network (unique-ish name; idempotent create).
- Starts `postgres:17` on that network with `POSTGRES_USER/PASSWORD/DB` matching the `DB_*` it will inject; waits for Postgres readiness (`pg_isready` loop).
- `docker build -t squash-app:smoke .`
- Runs the app image on the same network with env: `DB_HOST=<pg container name> DB_PORT=5432 DB_NAME DB_USER DB_PASSWORD`, `PORT=8080`, a dummy/empty `LLM_API_KEY`; publishes `8080`.
- Polls `http://localhost:8080/actuator/health` for `"status":"UP"` (bounded retries + hard timeout; on timeout print `docker logs` and exit non-zero).
- Asserts: `GET /actuator/health` → `200` + `UP`; `GET /` → `200` + body contains the SPA shell (e.g. `<div id="root">` / `index.html` marker); `GET /api/auth/me` → `401`.
- Prints a clear per-assertion pass/fail summary; exits `0` only if all pass.
- Always tears down (trap on EXIT → call the same cleanup as the stop script).

No snippet — the structure follows `run-local.sh`; the only non-obvious rule (container-name DB host on a shared network) is captured in Critical Implementation Details.

#### 2. Cleanup sibling

**File**: `stop-smoke-container.sh` (repo root, sibling of `stop-local.sh`)

**Intent**: Force-remove the smoke containers and network so a crashed run never blocks the next one.

**Contract**: `docker rm -f` the app + Postgres containers (ignore-missing) and `docker network rm` the smoke network (ignore-missing); idempotent, always exit `0`.

#### 3. Make the scripts executable

**File**: `smoke-container.sh`, `stop-smoke-container.sh`

**Intent**: Ensure both carry the executable bit like the existing `*-local.sh` scripts.

**Contract**: `chmod +x` both; mirrors `run-local.sh`/`stop-local.sh` file mode.

### Success Criteria:

#### Automated Verification:

- Script is executable and lints clean: `bash -n smoke-container.sh && bash -n stop-smoke-container.sh`
- Harness passes end-to-end locally: `./smoke-container.sh` exits `0` with all three assertions green
- Cleanup is idempotent: `./stop-smoke-container.sh && ./stop-smoke-container.sh` both exit `0`
- No leftover state after a run: `docker ps -a` and `docker network ls` show no smoke containers/network

#### Manual Verification:

- Re-running `./smoke-container.sh` immediately after a prior run succeeds (no port/name/network collision)
- Killing the script mid-run (Ctrl-C) leaves no orphaned containers/network (trap fires)
- On an intentionally broken image (e.g. wrong `DB_HOST`), the harness fails loudly with `docker logs` output, not a silent hang

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: CI Wiring (build once, share via artifact)

### Overview

Add a `container-smoke` job that builds the image and runs the harness, then shares the image as an artifact; add an `e2e` job that consumes that image and runs the existing Playwright spec; extend `ci-success` to gate on both.

### Changes Required:

#### 1. `container-smoke` CI job

**File**: `.github/workflows/ci.yml`

**Intent**: Build the Docker image once, prove the running artifact with the harness, and publish the image for the e2e job to reuse.

**Contract**: A new job `container-smoke` (`runs-on: ubuntu-latest`) that checks out, builds the image (or invokes `smoke-container.sh` which builds it), runs the smoke assertions, then `docker save squash-app:smoke | gzip > image.tar.gz` and `actions/upload-artifact` it. Must surface harness failure as job failure (script's non-zero exit). Reuses the same `DB_*`/`PORT` env contract as the local harness.

#### 2. `e2e` CI job

**File**: `.github/workflows/ci.yml`

**Intent**: Run the already-landed browser happy-path against the exact image the smoke job approved, deterministically (parse mocked at the browser edge).

**Contract**: A new job `e2e` with `needs: container-smoke` that: downloads the image artifact + `docker load`s it; starts a Postgres (service container or `docker run` on a shared network, same `DB_*`); runs the loaded image publishing `8080`; waits for health UP; `setup-node@v4` (Node `22.14.0`, npm cache) in `frontend/`; `npm ci`; installs Playwright browsers; sets `E2E_BASE_URL=http://localhost:8080` (or the reachable host) and `CI=true` (enables `retries: 2`); runs `npm run e2e`. The spec keeps its browser-edge `page.route` parse mock — no `LLM_API_KEY` needed. Upload the Playwright report on failure for debugging.

#### 3. Extend the aggregator

**File**: `.github/workflows/ci.yml`

**Intent**: Make both new gates required for a green CI.

**Contract**: Add `container-smoke` and `e2e` to `ci-success.needs` (currently `[backend-lint, backend-test, frontend-lint, frontend-test]`, `:67`). The existing `contains(needs.*.result, 'failure'|'cancelled')` check then covers them automatically.

### Success Criteria:

#### Automated Verification:

- Workflow is valid YAML and parses: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"` (or `actionlint` if available)
- `ci-success.needs` includes both new jobs: `grep -A1 "needs:" .github/workflows/ci.yml` shows `container-smoke` and `e2e`
- On a PR, the `container-smoke` job builds the image, runs the harness, and uploads the image artifact (green check)
- On a PR, the `e2e` job loads the shared image, boots it, and the Playwright spec passes (green check)
- The image is built exactly once across the two jobs (e2e job log shows `docker load`, not a fresh `docker build`)

#### Manual Verification:

- A deliberately broken Dockerfile / missing frontend makes `container-smoke` fail and **blocks** `ci-success` (gate works)
- A deliberately failing e2e assertion makes `e2e` fail and blocks `ci-success`, and the uploaded Playwright report shows the failure
- CI wall-clock is acceptable (image build paid once; e2e starts only after smoke passes)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human (a real PR showing both jobs green and the aggregator gating on them) before proceeding to the next phase.

---

## Phase 3: Correct test-plan.md

### Overview

Fix the two factual errors the research uncovered in the foundation doc that drove this phase, so the next reader isn't misled.

### Changes Required:

#### 1. Correct the smoke-surface and DB-mapping wording

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the "gated redirect" category error with the real curl-observable gate, and replace the `DATABASE_URL`/`SPRING_DATASOURCE_*` mapping with the actual discrete `DB_*` vars, in every place the doc states them (§3 Phase 5 description, §4/§5 stack & gates rows mentioning gated redirect / `DATABASE_URL`, §6.6 cookbook stub).

**Contract**: Prose/table edits only. After the edit, `grep -n "DATABASE_URL\|SPRING_DATASOURCE\|gated redirect" context/foundation/test-plan.md` returns nothing; the corrected text reads "401 on a protected `/api/**` path (e.g. `GET /api/auth/me`)" and "discrete `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`". Do not edit `context/archive/`.

### Success Criteria:

#### Automated Verification:

- Stale terms are gone: `grep -n "DATABASE_URL\|SPRING_DATASOURCE\|gated redirect" context/foundation/test-plan.md` returns no matches
- Corrected terms are present: `grep -n "401\|/api/auth/me\|DB_HOST" context/foundation/test-plan.md` shows the new wording in the Phase 5 / gates rows

#### Manual Verification:

- The §3 Phase 5 paragraph and §5 gates row read coherently with the corrected facts (no dangling references to redirects or `DATABASE_URL`)

**Implementation Note**: This phase is a documentation edit; automated grep verification is sufficient. Confirm the prose reads cleanly before closing the change.

---

## Testing Strategy

### Unit Tests:

- None — this change adds a shell harness and CI config, not application code. The "tests" here are the harness's own HTTP assertions and the CI gates.

### Integration Tests:

- The container-smoke harness *is* the integration test for deploy parity: build → boot → assert against the running image.
- The existing Playwright `log-match.spec.ts` is the e2e; this change only gates it.

### Manual Testing Steps:

1. Run `./smoke-container.sh` locally; confirm all three assertions pass and teardown is clean.
2. Re-run it immediately; confirm no collision.
3. Open a PR; confirm `container-smoke` and `e2e` run, are green, and that `ci-success` lists both in `needs`.
4. Temporarily break the Dockerfile in a scratch PR; confirm `container-smoke` fails and blocks `ci-success`; revert.
5. `grep` the test plan to confirm the stale terms are gone.

## Performance Considerations

- The Docker image build (Node install + `npm ci` + Vite build + jar) is the dominant cost. Building once in `container-smoke` and sharing via `docker save`/artifact avoids paying it twice. The artifact is a gzipped image tar — acceptable for CI transfer.
- Health-polling uses bounded retries with a hard timeout; no fixed sleeps, so the harness is as fast as boot allows and fails fast on a broken image.

## Migration Notes

- No data migration. Throwaway Postgres self-migrates via Flyway on each run.
- CI change is additive — existing four jobs are unchanged; only `ci-success.needs` grows.

## References

- Research: `context/changes/container-smoke-e2e-tests/research.md`
- Change identity: `context/changes/container-smoke-e2e-tests/change.md`
- Test plan: `context/foundation/test-plan.md` §3 (Phase 5), §4/§5 (stack/gates), §6.6, §7 (no live-LLM gating)
- Current CI: `.github/workflows/ci.yml:12-77`
- Local harness to mirror: `run-local.sh:21-44`, `stop-local.sh`
- Smoke surface map: research §C; `SecurityConfig.java:28-47`, `SpaForwardingConfig.java:17-21`
- Playwright runner: `frontend/playwright.config.ts`, `frontend/e2e/helpers/auth.ts:12`, `frontend/e2e/log-match.spec.ts:75-90`
- Networking trap & build-context lessons: `context/foundation/lessons.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Container-Smoke Harness (bash, local + CI)

#### Automated

- [ ] 1.1 Script is executable and lints clean (`bash -n` on both)
- [ ] 1.2 Harness passes end-to-end locally — `./smoke-container.sh` exits 0, all three assertions green
- [ ] 1.3 Cleanup is idempotent — `./stop-smoke-container.sh` twice both exit 0
- [ ] 1.4 No leftover containers/network after a run

#### Manual

- [ ] 1.5 Immediate re-run succeeds (no port/name/network collision)
- [ ] 1.6 Ctrl-C mid-run leaves no orphans (trap fires)
- [ ] 1.7 Broken image fails loudly with `docker logs`, not a silent hang

### Phase 2: CI Wiring (build once, share via artifact)

#### Automated

- [ ] 2.1 Workflow is valid YAML / parses (or `actionlint` clean)
- [ ] 2.2 `ci-success.needs` includes `container-smoke` and `e2e`
- [ ] 2.3 PR: `container-smoke` builds image, runs harness, uploads image artifact (green)
- [ ] 2.4 PR: `e2e` loads shared image, boots it, Playwright spec passes (green)
- [ ] 2.5 Image built exactly once (e2e log shows `docker load`, not `docker build`)

#### Manual

- [ ] 2.6 Broken Dockerfile / missing frontend makes `container-smoke` fail and blocks `ci-success`
- [ ] 2.7 Failing e2e blocks `ci-success`; uploaded Playwright report shows the failure
- [ ] 2.8 CI wall-clock acceptable (build paid once; e2e gated behind smoke)

### Phase 3: Correct test-plan.md

#### Automated

- [ ] 3.1 Stale terms gone — grep for `DATABASE_URL\|SPRING_DATASOURCE\|gated redirect` returns nothing
- [ ] 3.2 Corrected terms present — grep shows `401`/`/api/auth/me`/`DB_HOST` in Phase 5 / gates rows

#### Manual

- [ ] 3.3 §3 Phase 5 and §5 gates row read coherently with corrected facts
