---
date: 2026-06-07T18:32:43+02:00
researcher: Tomasz Borowiec
git_commit: 159dc37ce61bf30caa85b68fa468239a5b24b04a
branch: main
repository: squash-progress-tracker
topic: "Phase 5 — container smoke + critical-flow e2e: grounding the Docker build/run seams, the HTTP smoke surface, and the login→log-match→history browser path"
tags: [research, codebase, docker, container-smoke, e2e, playwright, ci, spring-security, flyway, risk-6]
status: complete
last_updated: 2026-06-07
last_updated_by: Tomasz Borowiec
---

# Research: Phase 5 — Container smoke + critical-flow e2e

**Date**: 2026-06-07T18:32:43+02:00
**Researcher**: Tomasz Borowiec
**Git Commit**: 159dc37ce61bf30caa85b68fa468239a5b24b04a
**Branch**: main
**Repository**: squash-progress-tracker

## Research Question

Ground Phase 5 of the test rollout (`context/foundation/test-plan.md` §3, risk #6) before planning: what the Docker build/run does vs. local, what `mvn package` triggers (frontend-maven-plugin), how the DB and `$PORT` are wired, what the HTTP smoke surface actually returns (health, SPA root, "gated redirect"), and what a deterministic browser e2e (login → log match → history) needs to traverse — including the Playwright runner setup.

## Summary

**Headline: most of the e2e half of Phase 5 already exists; the container-smoke half and the CI wiring do not.** The latest commit (`159dc37 test(test-plan): add Phase 5 critical-flow log-match e2e`) already added a fully-wired Playwright runner and the critical-flow spec. What remains for this change is: (1) a **container-smoke harness** that builds + runs the *Docker image* (no such harness exists — `run-local.sh` runs the app via `mvnw spring-boot:run`, not as a container), and (2) **CI jobs** that gate on the container smoke and the existing e2e (neither is in `ci.yml` yet).

Two assumptions in the test plan are **wrong for this codebase** and must be corrected in the plan:

1. **DB env mapping.** The plan says `DATABASE_URL → SPRING_DATASOURCE_*`. The app actually uses **five discrete vars** — `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` — composed into the JDBC URL in `application.properties:5-7`, sourced from Render's `fromDatabase` properties (`render.yaml:14-33`). There is no `DATABASE_URL` anywhere. A smoke harness must inject the five discrete vars.

2. **"Gated route redirect."** The plan's smoke step expects a gated route to *redirect* (implying a 3xx). It does not. Gating is **client-side only**. An unauthenticated `GET /history` returns **`200 index.html`** (permitAll + SPA forward); the "redirect to /login" is a React Router `<Navigate>`, invisible to curl. The **only curl-observable gate** is **`401` on a protected API path** (e.g. `GET /api/auth/me`). The smoke assertion must be rewritten as "401 on a protected `/api/**` path", not "302 on a client route".

A third already-solved concern: the schema. `ddl-auto=validate` does *not* create tables, but **Flyway** does — `V1__create_users.sql` + `V2__create_matches.sql` run on boot and are packaged into the jar, so a fresh throwaway Postgres self-migrates. No schema-seed step is needed in the harness.

## Detailed Findings

### A. Docker build / package seams

- **Dockerfile** (`Dockerfile:1-16`): multi-stage. Build = `maven:3.9-eclipse-temurin-21`; `dependency:go-offline` (line 6), then `COPY src/ src/` **and** `COPY frontend/ frontend/` (lines 7-8), then `./mvnw -q -B clean package -DskipTests` (line 9). Runtime = `eclipse-temurin:21-jre`, copies `target/squash-progress-tracker-0.0.1-SNAPSHOT.jar` → `app.jar` (line 14), `EXPOSE 8080` (line 15), `ENTRYPOINT java -jar app.jar` (line 16). Tests are **skipped** in the image build.
- **`.dockerignore`** excludes `target/`, `.git/`, `context/`, `frontend/node_modules/`, `frontend/dist/`, `cookies.txt`. Keeps `frontend/` source so the in-image build produces the SPA. (This `COPY frontend/` + `.dockerignore` is the fix from commit `85322e9` — see `lessons.md` "Verify the Docker build context covers all build inputs".)
- **frontend-maven-plugin** (`pom.xml:138-171`, v1.15.1): workingDir `frontend`, installs **Node v22.14.0** to `target/`, runs `npm ci` then `npm run build`, all bound to the **`prepare-package`** phase (so the SPA is built before the jar is repackaged).
- **SPA build output**: `frontend/vite.config.ts:12-15` → `outDir: '../target/classes/static'`, `emptyOutDir: true`. So Vite writes `index.html` + `assets/` straight into the Spring Boot classpath `static/` dir → packaged at `BOOT-INF/classes/static/` in the jar. Spring Boot's default static handler serves them; no custom resource handler.
- **Final jar name**: no `<finalName>` override → `squash-progress-tracker-0.0.1-SNAPSHOT.jar` (matches `Dockerfile:14`). `spring-boot-maven-plugin` (`pom.xml:109-112`) repackages.

### B. Runtime / deploy wiring

- **Port**: `application.properties:3` → `server.port=${PORT:8080}` (env `PORT`, default 8080). `Dockerfile` exposes 8080; Render injects `$PORT`.
- **DB (discrete vars, NOT `DATABASE_URL`)**: `application.properties:5-7`
  - `spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`
  - `spring.datasource.username=${DB_USER}`, `password=${DB_PASSWORD}`
  - `render.yaml:14-33` maps these from `fromDatabase: { property: host|port|database|user|password }` of the `squash-db` Postgres (`render.yaml:37-41`, Postgres major 17).
- **Health**: `application.properties:9-10` exposes only `health`, `show-details=never`. `render.yaml:10` `healthCheckPath: /actuator/health`. The DB health indicator still drives the aggregate status (a down DB → overall `DOWN`) even with details hidden.
- **Schema via Flyway**: deps `spring-boot-starter-flyway` + `flyway-database-postgresql` (`pom.xml:56-61`). Migrations `src/main/resources/db/migration/V1__create_users.sql`, `V2__create_matches.sql` (creates `users`, `matches`, `match_sets` + indexes). Packaged into the jar; run on boot before Hibernate `validate`.
- **LLM config**: `application.properties:16-20` — `LLM_API_KEY` (default empty), `LLM_BASE_URL` (default Gemini OpenAI-compat), `LLM_MODEL=gemini-2.5-flash`, `LLM_TIMEOUT=30s`. In a running container the parse path hits **live Gemini** unless the caller bypasses it.

### C. HTTP smoke surface (what curl actually sees)

Authoritative map from `SecurityConfig.java` + `SpaForwardingConfig.java` + `SpaRoutes.java`:

| Request (unauthenticated) | Status | Why |
|---|---|---|
| `GET /actuator/health` | `200 {"status":"UP"}` | permitAll (`SecurityConfig.java:28-29`) |
| `GET /` | `200` index.html | static welcome + permitAll (`:32-33`) |
| `GET /history` (client route) | **`200` index.html** | permitAll + `forward:/index.html` (`SpaForwardingConfig.java:17-21`, routes in `SpaRoutes.java:18`) — **no redirect** |
| `GET /api/auth/me` (protected API) | **`401`** JSON | custom `authenticationEntryPoint` (`SecurityConfig.java:41-45`) — the only curl-observable gate |
| `POST /api/auth/login` w/o CSRF | `403` | CSRF enabled (`SecurityConfig.java:38-40`) |

- **Auth is custom session-based**, not form login / basic (`SecurityConfig.java:46-47` disable both). `POST /api/auth/login` persists a `SecurityContext` via `HttpSessionSecurityContextRepository` → `Set-Cookie: JSESSIONID` (`AuthController.java:53-68`). Endpoints: `POST /api/auth/register` → 201 (no auto-login), `POST /api/auth/login` → 200, `POST /api/auth/logout` → 204, `GET /api/auth/me` → 200/401 (`AuthController.java:41-86`).
- **CSRF**: `CookieCsrfTokenRepository.withHttpOnlyFalse()` + a `CsrfCookieFilter` materializes the `XSRF-TOKEN` cookie. The SPA's axios interceptor (`frontend/src/api/client.ts:24-47`) bootstraps the cookie with a tolerated `GET /api/auth/me` then sends it as header `X-XSRF-TOKEN` on mutations.
- **SPA deep-link forward** is a server-internal forward (HTTP 200), not a 3xx. `/api/**` and `/actuator/**` are never in `SpaRoutes.CLIENT_ROUTES`, so never swallowed.

### D. Critical-flow e2e — login → log match → history (ALREADY IMPLEMENTED)

- **Existing spec**: `frontend/e2e/log-match.spec.ts` (added in `159dc37`). It is correct and matches the live code. Real: auth (replayed `storageState`), routing, `POST /api/matches`, Postgres persistence, history render, the MatchForm confirm gate. **Mocked at the browser edge**: only `POST /api/matches/parse` (`log-match.spec.ts:75-90`) — a deterministic preview, because the real parse calls live Gemini.
- **The LLM-determinism seam**: there is **no server-side bypass** — no `@Profile`, `@ConditionalOnProperty`, `@Primary`, or mock-LLM bean exists in `src/`. The only lever is `LLM_BASE_URL` (point it at a fake OpenAI-compat server — none ships in the repo). Java tests mock in-process via `@MockitoBean LlmClient`; that doesn't reach a deployed container. **The browser-edge `page.route` mock is the established and correct seam.** Any plan that wants to exercise the *real* parse path in CI is blocked by this — flag it.
- **Flow / locators** (verified against the pages):
  - Login (`LoginPage.tsx`): `getByLabel('Email')`, `getByLabel('Password')`, `getByRole('button', {name: 'Sign in'})`, success → navigate `/` (`:105-107`). (The e2e skips UI login — it replays `storageState`.)
  - Log match (`LogMatchPage.tsx` + `MatchForm.tsx`): AI mode heading `Describe your match` (`:189`), the only `textbox` (`:190-195`), `Parse with AI` button (`:197-204`) → `POST /api/matches/parse`. On success the parsed values prefill the editable `MatchForm` (this *is* the confirm gate — `getByLabel('Opponent')`, `Save match` button `MatchForm.tsx:380-382`). Save → `POST /api/matches` → navigate `/history` (`LogMatchPage.tsx:213-216`).
  - History (`HistoryPage.tsx`): cards (not a table); assert the unique opponent via `getByText(opponent).filter({visible:true})` — the opponent also appears as a hidden filter `<option>` (`:236-240`).
- **API client** (`frontend/src/api/matches.ts`): `parseMatch(text)` → `POST /api/matches/parse {text}`; `createMatch(data)` → `POST /api/matches`; `listMatches(opponent?)` → `GET /api/matches`; `listOpponents()` → `GET /api/matches/opponents`; `get/update/delete` → `/api/matches/{id}`.

### E. Test infra / runner / CI to extend

- **Playwright is already wired** (greenfield concern is resolved):
  - `@playwright/test@^1.60.0` (`frontend/package.json:28`); scripts `e2e`, `e2e:ui`, `e2e:report` (`:14-18`).
  - `frontend/playwright.config.ts`: `testDir: './e2e'`, a `setup` project (`auth.setup.ts`) that logs in via API once and writes `storageState`, and a `chromium` project that replays it (`dependencies: ['setup']`). `retries: 2` under CI. **No `webServer`** — it targets an already-running app at `baseURL = BASE_URL`.
  - `BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:8080'` (`frontend/e2e/helpers/auth.ts:12`). `STORAGE_STATE = 'e2e/.auth/user.json'` (gitignored). `csrfHeaders()` mirrors the SPA bootstrap (`:34-39`).
  - Specs present: `auth.setup.ts`, `seed.spec.ts` (API-seeds a match — the four-rules template), `log-match.spec.ts` (the full UI journey).
  - **Vitest isolation is already correct**: `vite.config.ts` test glob covers `src/**` only; `e2e/` is Playwright's separate runner. No cross-contamination.
- **Backend integration harness to mirror**: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` + `@Container @ServiceConnection PostgreSQLContainer("postgres:17")`. Schema comes from **Flyway on boot** — there is **no** test `application.properties` overriding `ddl-auto` (confirmed empty). `registerAndLogin(email)` helper (`MatchApiIntegrationTests.java:35-49`) registers + logs in and returns the `MockHttpSession`; `.with(csrf())` on mutations.
- **CI today** (`.github/workflows/ci.yml`): jobs `backend-lint` (spotless), `backend-test` (`mvnw test`), `frontend-lint`, `frontend-test` (typecheck + `test:run`), plus `ci-success` aggregator with `needs: [backend-lint, backend-test, frontend-lint, frontend-test]` (`:67`). **No container-smoke or e2e job.** Phase 5 adds two jobs and must extend the aggregator's `needs`.
- **Maven**: Surefire (inherited from `spring-boot-starter-parent:4.0.6`), no `*IT`/failsafe split; `*LiveSmokeTest` classes self-skip via `@EnabledIfEnvironmentVariable(LLM_API_KEY)` and are never gated in CI.
- **Local harness (NOT a container-smoke harness)**: `run-local.sh:40-44` builds the frontend then runs the app with `./mvnw spring-boot:run` (Postgres in Docker via `run-local.sh:21-31`, app on the host). It never builds or runs the *app image*. So nothing today exercises the containerized artifact — that is the Phase 5 gap.

## Code References

- `Dockerfile:1-16` — multi-stage build/run; jar name; EXPOSE 8080
- `.dockerignore` — build-context excludes (keeps `frontend/` source)
- `pom.xml:56-61` — Flyway deps; `:109-112` spring-boot-maven-plugin; `:138-171` frontend-maven-plugin (Node 22.14.0, `npm ci` + build at `prepare-package`)
- `frontend/vite.config.ts:12-15` — SPA build output to `../target/classes/static`
- `application.properties:3` — `server.port=${PORT:8080}`; `:5-7` discrete `DB_*` JDBC; `:9-10` health exposure; `:16-20` LLM env
- `render.yaml:10` healthCheckPath; `:14-33` `fromDatabase` → `DB_*`; `:37-41` `squash-db`
- `src/main/resources/db/migration/V1__create_users.sql`, `V2__create_matches.sql` — Flyway schema
- `src/main/java/.../security/SecurityConfig.java:28-47` — permitAll vs authenticated; 401 entry point; CSRF
- `src/main/java/.../SpaForwardingConfig.java:17-21`, `SpaRoutes.java:18` — client routes forwarded to index.html (200, not redirect)
- `src/main/java/.../user/AuthController.java:41-86` — register/login/logout/me
- `frontend/src/api/client.ts:24-47` — CSRF bootstrap + `X-XSRF-TOKEN`
- `frontend/e2e/log-match.spec.ts:75-90` — browser-edge parse mock (the LLM-determinism seam)
- `frontend/e2e/helpers/auth.ts:12-39` — `BASE_URL` / `E2E_BASE_URL`, `STORAGE_STATE`, `csrfHeaders`
- `frontend/playwright.config.ts:12-31` — setup + chromium projects; no webServer
- `.github/workflows/ci.yml:12-77` — current jobs + `ci-success` aggregator (`:67`)
- `MatchApiIntegrationTests.java:35-49` — `registerAndLogin` helper
- `run-local.sh:21-44`, `stop-local.sh` — local Postgres-in-Docker + host app (not a container-smoke harness)

## Architecture Insights

- **One origin serves SPA + API + actuator.** The integrated jar is the unit of deploy and the unit Phase 5 must smoke. This is why "gated redirect" is a category error: the server returns the SPA shell for client routes and only the `/api/**` layer carries auth signal.
- **Schema lifecycle is Flyway, not Hibernate.** `validate` is a guard; Flyway is the source of truth. A throwaway Postgres self-migrates — no seed SQL in the harness.
- **Determinism boundary for AI lives at the HTTP edge.** There is no in-app LLM toggle, so the test stack draws the deterministic line at `page.route('**/api/matches/parse')` (browser) and `@MockitoBean LlmClient` (JVM). The container smoke must avoid any path that calls the real LLM.
- **The Linux `host.docker.internal` trap applies** (`lessons.md`): when the app runs as a container reaching a sibling Postgres container, put both on a shared `docker network` and reference the DB by container name, or pass `--add-host=host.docker.internal:host-gateway`. `run-local.sh` sidesteps it (app on host), but the new container-smoke harness will hit it.

## What Phase 5 still needs to build (scope)

1. **Container-smoke harness** — build the image, run it against a throwaway Postgres (shared docker network; inject `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` + `PORT`), wait for `/actuator/health` UP, then HTTP-assert: `GET /actuator/health` → 200 UP; `GET /` → 200 index.html; **`GET /api/auth/me` → 401** (the corrected gate assertion). No browser.
2. **CI wiring** — add a `container-smoke` job and an `e2e` job to `ci.yml` (e2e `needs: container-smoke`, starts the image, sets `E2E_BASE_URL`, runs `npm ci && npm run e2e`), and extend `ci-success.needs` to include both. The e2e *spec* already exists; it is just not gated.

## Historical Context (from prior changes)

- `lessons.md` — "On Linux, host.docker.internal does not resolve inside containers" (shared network / `--add-host`); "Verify the Docker build context covers all build inputs" (the `COPY frontend/` fix, commit `85322e9`).
- `context/changes/quality-gates-ci/` — Phase 4 stood up `ci.yml` (the four-job gate + aggregator) this change extends.
- `context/changes/frontend-runner-bootstrap-tests/` — Phase 3 stood up Vitest + the api-client contract test + ProtectedRoute test.
- `context/archive/2026-06-05-ownership-boundary-tests/` — Phase 1 ownership-boundary integration harness (`registerAndLogin`, two-session pattern).
- Commit `159dc37` — already added the Playwright runner + `log-match.spec.ts` (the e2e half of this very phase).

## Related Research

- `context/foundation/test-plan.md` §2 (risk #6 row), §3 (Phase 5), §4 (stack: container smoke / Playwright lines), §6.6 (cookbook stub), §7 (no live-LLM gating).
- `context/foundation/infrastructure.md`, `context/foundation/tech-stack.md` — Render/deploy rationale.

## Open Questions

1. **Harness host for the smoke test** — pure bash + `docker run` (mirrors `run-local.sh` style, simplest in GH Actions), or a JVM Testcontainers `GenericContainer` that builds/runs the image (`ImageFromDockerfile`)? Trade-off: bash is closer to what Render actually does; Testcontainers gives richer assertions but adds a heavyweight build inside `mvn test`. Decide in the plan.
2. **Does the smoke job rebuild the image that the e2e job also needs?** Build once and share (artifact/`docker save`) vs. build twice. Affects CI wall-clock.
3. **`render.yaml` env-name drift** — Render injects `DB_*`; confirm there is no place still expecting `DATABASE_URL`/`SPRING_DATASOURCE_*` (none found, but the plan's wording implies someone believed there was — worth a one-line confirmation in the plan).
4. **Should the test plan §3/§6.6 wording be corrected** (gated-redirect → 401; `DATABASE_URL` → discrete `DB_*`) as part of this change, or left to a `/10x-test-plan --refresh`? Recommend correcting in-place since the plan drove this phase.
