# Manual Match Logging & History Implementation Plan

## Overview

Deliver roadmap slice **S-01**: the signed-in player can log a squash match through a structured form and view their match history filtered by opponent (FR-006, FR-007). This is the first user-visible vertical — it introduces the match domain on top of the existing auth foundation **and** the application's first rendered UI, built as a React SPA bundled into the single Spring Boot deployable.

The slice deliberately stops short of AI parsing (S-03), AI game plans (S-02), and edit/delete (S-04). It exists to put real, ownership-scoped match data behind the API so the north star (S-02) has data to reason over, without depending on the undecided LLM provider.

## Current State Analysis

The auth foundation (F-01, `minimal-auth`) is fully implemented and is the baseline this slice builds on:

- **Ownership seam exists.** `security/CurrentUser.java:14` exposes `currentUserId()`, documented as the primitive every owned-data query *must* filter through. The match domain reuses it verbatim — no new ownership mechanism.
- **Persistence pattern is set.** JPA entity with explicit `@Table(name="...")` (`user/User.java:7`), `JpaRepository` (`user/UserRepository.java`), Flyway SQL migrations (`db/migration/V1__create_users.sql`), `spring.jpa.hibernate.ddl-auto=validate` (`application.properties:12`) — so the entity must match the migration exactly or boot fails.
- **API + error contract is set.** `@RestController` under `/api`, `@Valid` request records (`user/dto/RegisterRequest.java`), response records (`user/dto/UserResponse.java`), and a single JSON error shape `ApiError` (`user/dto/ApiError.java`) produced by `@RestControllerAdvice` (`user/ApiExceptionHandler.java`) — validation failures already map to `400` with `fieldErrors`.
- **Security is JSON/session-based.** `security/SecurityConfig.java` permits only `/`, `/actuator/health`, `/api/auth/register`, `/api/auth/login`; everything else is `authenticated()` and anonymous `/api/**` returns a JSON `401`. CSRF uses `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`, readable by JS) with `CsrfCookieFilter` materializing the token; mutating requests must echo it in `X-XSRF-TOKEN`.
- **No UI of any kind.** Only a plaintext `WelcomeController` at `/`. There is no template engine, no static assets, no frontend build. The `minimal-auth` brief explicitly parked the UI-layer choice and browser redirect-to-login for **this** slice.
- **Single-deployable infra.** One multi-stage `Dockerfile` whose build stage runs `./mvnw clean package -DskipTests`; one Render web service (`render.yaml`); local dev via `./run-local.sh` (Postgres in Docker + `spring-boot:run`). Tests use Testcontainers Postgres 17 + MockMvc with `.with(csrf())` (`user/AuthIntegrationTests.java`).

What's missing: the entire match domain (table, entity, repository, service, API) and the entire UI layer.

## Desired End State

A signed-in player, in a browser, can:

1. Log in / register through React pages served by the app.
2. Open a "Log match" form, enter opponent name, date, 1–5 set scores (player vs opponent), and optional notes, see the derived overall score, and save it.
3. See the saved match appear in their history, newest first, with the overall result shown.
4. Filter history to a single opponent.

And the privacy guardrail holds: player B can never see player A's matches through any endpoint. The whole thing ships as one `./mvnw package` artifact (the existing Dockerfile is unchanged), boots with Flyway applying `V2`, and `./mvnw test` is green including a cross-player ownership test.

**Verification:** `./mvnw test` passes (incl. ownership + validation + derivation tests); `./mvnw package` produces a jar with the built SPA inside; `./run-local.sh` boots, serves the SPA at `/`, and the full register → log match → see-in-history → filter flow works in a browser; a second account sees an empty history.

### Key Discoveries:

- Ownership filter primitive: `CurrentUser.currentUserId()` (`security/CurrentUser.java:14`) — every match query filters on it.
- `ddl-auto=validate` (`application.properties:12`) means the `Match`/`MatchSet` entities must match `V2` exactly, or the app won't boot — this is an intended safety net.
- CSRF cookie is `XSRF-TOKEN`, header is `X-XSRF-TOKEN`, cookie is non-HttpOnly (`SecurityConfig.java:33`) — axios's defaults match these names out of the box, but the cookie is only set after a request passes through `CsrfCookieFilter` (a GET to `/api/auth/me` on app load materializes it).
- `anyRequest().authenticated()` (`SecurityConfig.java:30`) will block static assets and SPA deep-links unless explicitly permitted — Phase 3 must open static resources and add a non-API fallback to `index.html`.
- The Dockerfile build stage already runs `./mvnw clean package` (`Dockerfile:7`), so binding the frontend build to the Maven lifecycle (Phase 3) means **no Dockerfile change** is required.
- Test pattern to mirror: `user/AuthIntegrationTests.java` (Testcontainers `@ServiceConnection` Postgres 17, MockMvc, `.with(csrf())`, `MockHttpSession` for authenticated calls).

## What We're NOT Doing

- **No AI** — natural-language entry (S-03) and game-plan generation (S-02) are out. No LLM client, no provider decision touched.
- **No edit or delete** of matches (S-04) — create + read only. No `PUT`/`DELETE`/`GET /api/matches/{id}` in this slice.
- **No first-class Opponent entity** — opponent is a free-text name field; history groups/filters by distinct name (decided in planning; revisit if fragmentation bites).
- **No stored overall score** — the `3-1` result is derived from set scores, never persisted as its own field.
- **No squash-rules legality validation** — only light structural validation (see Phase 2).
- **No pagination / infinite scroll** — flat newest-first list (PRD-flagged follow-up; deferred).
- **No summary stats / win-loss aggregation** — overlaps S-02's analytical territory.
- **No CORS / second deployment** — the SPA is bundled into the Spring service; same-origin only.
- **No password reset, email verification, or auth changes** — Phase 3 only adds React pages over the *existing* `/api/auth/*` endpoints.

## Implementation Approach

Build the backend vertical first (Phases 1–2), so the entire match capability is exercised through tests before any UI exists — this de-risks the data model and the ownership boundary independently of the frontend. Then introduce the SPA in two steps: Phase 3 stands up the React app, its build integration, and the serving/security/CSRF plumbing plus the minimal auth shell (the riskiest *new* surface), and Phase 4 adds the actual match form and history view once the pipe is proven.

Data model: a `matches` parent table scoped by `user_id`, with a `match_sets` child table (one row per set) via JPA `@OneToMany`. This keeps set scores first-class and queryable for S-02, and makes the overall score a pure derivation (count of won sets) rather than stored state.

SPA integration: a `frontend/` Vite React project, built into Spring's `static/` by `frontend-maven-plugin` bound to the Maven `generate-resources`/`prepare-package` lifecycle, so `./mvnw package` (and therefore the existing Dockerfile) produces one self-contained artifact. Dev uses the Vite dev server proxying `/api` to `:8080` for same-origin cookies.

## Critical Implementation Details

- **State sequencing (CSRF first request):** axios won't have an `XSRF-TOKEN` cookie to echo until *some* request has passed through `CsrfCookieFilter`. The SPA must issue a session-bootstrap GET (e.g. `GET /api/auth/me`, tolerating its `401`) on load before any mutating call, or the first `POST` (login/register/create-match) will be rejected with `403`.
- **Security ordering (SPA fallback vs API):** the non-API → `index.html` forwarding rule must not swallow `/api/**` or `/actuator/**`. Keep `/api/**` and `/actuator/**` matched first; forward only paths that are neither an API route nor a real static asset.
- **`ddl-auto=validate` ordering:** the `V2` migration and the `Match`/`MatchSet` entity mappings must be authored together and agree on every column name, nullability, and type, or the context fails to load (this is what catches drift).

## Phase 1: Match data model & migration

### Overview

Create the persistence layer for matches and their set scores, mirroring the established JPA + Flyway pattern. Backend-only and fully test-verifiable; no API or UI yet.

### Changes Required:

#### 1. Flyway migration

**File**: `src/main/resources/db/migration/V2__create_matches.sql`

**Intent**: Add the `matches` and `match_sets` tables that hold a player's logged matches and their per-set scores, scoped to the owning user.

**Contract**:
- `matches`: `id` (identity PK), `user_id BIGINT NOT NULL` REFERENCES `users(id)`, `opponent_name VARCHAR(255) NOT NULL`, `match_date DATE NOT NULL`, `notes TEXT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- `match_sets`: `id` (identity PK), `match_id BIGINT NOT NULL` REFERENCES `matches(id) ON DELETE CASCADE`, `set_number INT NOT NULL`, `player_score INT NOT NULL`, `opponent_score INT NOT NULL`, `UNIQUE (match_id, set_number)`.
- Indexes supporting the history queries: `(user_id, match_date DESC)` and a case-insensitive opponent index, e.g. `CREATE INDEX ... ON matches (user_id, lower(opponent_name))`.
- Follow the `V1` column conventions (identity syntax, `TIMESTAMPTZ`, named constraints).

#### 2. JPA entities

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/Match.java`

**Intent**: Map the `matches` table; own its set scores as a cascaded, ordered child collection so saving a match persists its sets and the overall score can be derived from them.

**Contract**: `@Entity @Table(name = "matches")`. Fields mirror the migration (`id`, `userId`, `opponentName`, `matchDate` as `LocalDate`, `notes`, `createdAt` as `OffsetDateTime` with a `@PrePersist` default like `User`). Set collection: `@OneToMany(mappedBy = "match", cascade = ALL, orphanRemoval = true)` ordered by `setNumber` (`@OrderBy`). Provide a helper to add a set that also back-links it to the match.

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchSet.java`

**Intent**: Map one row of `match_sets` — a single set's player/opponent score.

**Contract**: `@Entity @Table(name = "match_sets")`. Fields: `id`, `@ManyToOne Match match` (`@JoinColumn(name = "match_id")`), `setNumber`, `playerScore`, `opponentScore`. `userId` lives only on `Match` (sets inherit ownership through their parent).

#### 3. Repository with ownership-scoped queries

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchRepository.java`

**Intent**: Provide the read/write access points, every read scoped by `userId`, so cross-player access is impossible by construction.

**Contract**: `interface MatchRepository extends JpaRepository<Match, Long>` with derived queries:
- `List<Match> findByUserIdOrderByMatchDateDescIdDesc(Long userId)`
- `List<Match> findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(Long userId, String opponentName)`
- `Optional<Match> findByIdAndUserId(Long id, Long userId)` (ownership-safe single fetch; seeds S-04)
- Response-producing match reads must fetch `sets` explicitly, either by annotating the read methods with `@EntityGraph(attributePaths = "sets")` or by using equivalent fetch-join queries. This is required because `spring.jpa.open-in-view=false` and `MatchResponse.from` reads the child collection after repository access.
- Distinct opponent names for the filter must collapse case variants. Use a query grouped by `lower(m.opponentName)` with a deterministic display value (for example `min(m.opponentName)`) and order by the lowercase key, returning `List<String>`.

### Success Criteria:

#### Automated Verification:

- Compiles & context loads: `./mvnw test` (existing tests still pass; `ddl-auto=validate` accepts the new entities against `V2`)
- Package builds: `./mvnw -DskipTests package`

#### Manual Verification:

- `./run-local.sh` boots; logs show Flyway applying `V2`; `matches` and `match_sets` tables exist with the FK + cascade.

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Match REST API

### Overview

Expose create + read over the match domain under `/api/matches`, scoped to the signed-in player, with the overall score derived in the response. Mirror the existing DTO/validation/error patterns. This completes the backend vertical — fully testable via MockMvc.

### Changes Required:

#### 1. Request/response DTOs

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/dto/CreateMatchRequest.java`

**Intent**: The validated payload for logging a match, including its set list.

**Contract**: record with `opponentName` (`@NotBlank`, `@Size(max=255)`), `matchDate` (`@NotNull`, `@PastOrPresent`), `notes` (nullable, `@Size(max=...)`), `sets` (`@NotEmpty`, `@Size(max=5)`, `@Valid List<SetScoreRequest>`). Nested `SetScoreRequest` record (own file or nested): `playerScore`/`opponentScore` (`@NotNull`, `@Min(0)`, `@Max(99)`).

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/dto/MatchResponse.java`

**Intent**: The match as returned to clients, with the derived overall result.

**Contract**: record with `id`, `opponentName`, `matchDate`, `notes`, `sets` (`List<SetScoreResponse>` of `setNumber`/`playerScore`/`opponentScore`), `setsWon`, `setsLost`, `result` (e.g. `"WON"`/`"LOST"`/`"DRAW"`). Static `from(Match)` factory derives `setsWon` = count of sets where `playerScore > opponentScore`, `setsLost` = the converse, mirroring `UserResponse.from`.

#### 2. Service

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchService.java`

**Intent**: Apply the ownership boundary and own the create/list logic; the controller stays thin.

**Contract**: `@Service` depending on `MatchRepository` + `CurrentUser`. Methods: `create(CreateMatchRequest)` (builds `Match` + `MatchSet`s, stamps `userId = currentUser.currentUserId()`, assigns `setNumber` by list order, saves, returns `MatchResponse`); `list(String opponentFilterOrNull)` (delegates to the right ownership-scoped repository query); `listOpponents()` (case-collapsed distinct names). Mark read methods `@Transactional(readOnly = true)` so fetched set collections remain available during DTO conversion. All reads pass `currentUserId()` — never trust a client-supplied user id.

#### 3. Controller

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchController.java`

**Intent**: Expose the match API under `/api`, following `AuthController`'s style.

**Contract**: `@RestController @RequestMapping("/api/matches")`:
- `POST /api/matches` → `201`, `@Valid CreateMatchRequest` → `MatchResponse`
- `GET /api/matches?opponent=<name>` → `List<MatchResponse>` (opponent param optional; absent = all, newest first)
- `GET /api/matches/opponents` → `List<String>` distinct opponent names for the filter dropdown

Validation errors flow through the existing `ApiExceptionHandler` (→ `400` + `fieldErrors`); no new exception types needed for create+list.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` passes including new `MatchApiIntegrationTests` (Testcontainers + MockMvc, mirroring `AuthIntegrationTests`)
- Test: authenticated `POST /api/matches` with valid body → `201`; response `setsWon`/`setsLost` match the entered sets (derivation correctness, e.g. 3 won + 1 lost → 3/1)
- Test: validation matrix → `400` with `fieldErrors` for blank opponent, future `matchDate`, empty `sets`, 6 sets, negative score
- Test (ownership, hard rule): player A logs a match; player B's `GET /api/matches` returns `[]` and `GET /api/matches?opponent=<A's opponent>` returns `[]`; anonymous `GET /api/matches` → `401`
- Test: `GET /api/matches/opponents` returns only the caller's distinct opponents and collapses case variants (`Kowalski` + `kowalski` yields one option)

#### Manual Verification:

- `curl` (with session cookie + CSRF header) create → list → filter → opponents flow returns expected JSON; overall result reads correctly.

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: React SPA scaffold, build integration & auth shell

### Overview

Stand up the React SPA, wire its build into Maven so the existing Dockerfile keeps working unchanged, open the security config for static assets + SPA deep-links, and ship the minimal login/register shell + route guard so the app is reachable in a browser. This phase proves the serving/CSRF/auth pipe before any match UI is built on it.

### Changes Required:

#### 1. Vite React project

**File**: `frontend/` (new Vite React project — `package.json`, `vite.config.*`, `index.html`, `src/`)

**Intent**: The SPA source. Includes an API client (axios with `withCredentials: true`, relying on its default `XSRF-TOKEN`→`X-XSRF-TOKEN` handling), React Router, a session-bootstrap call on load, login + register pages over `/api/auth/*`, and a route guard that redirects unauthenticated users to `/login`.

**Contract**:
- Dev: `vite.config` proxies `/api` → `http://localhost:8080` so cookies stay same-origin in dev.
- Build: `npm run build` emits to a path Maven copies into `static/` (Phase 3 item 3).
- Auth client: `login`, `register`, `logout`, `me` calls; on app mount issue `me()` (tolerate `401`) to establish the CSRF cookie before any `POST` (see Critical Implementation Details).
- Route guard: a wrapper that calls/holds `me()` state; `401` → redirect to `/login`. Routes: `/login`, `/register`, and an authenticated area (placeholder home for now; match routes land in Phase 4).

#### 2. Maven build integration

**File**: `pom.xml`

**Intent**: Build the SPA during `./mvnw package` and place its output on the Spring classpath as static resources, so one command (and the unchanged Dockerfile) yields a self-contained jar.

**Contract**: add `frontend-maven-plugin` (eirslett) bound to a build phase (`generate-resources` or `prepare-package`): install Node/npm, `npm ci`, `npm run build`, with the Vite output directory configured to land under `target/classes/static` (or `src/main/resources/static` via `maven-resources-plugin`). Skippable for fast backend-only test runs if needed. The existing `Dockerfile` build stage (`./mvnw clean package`) requires **no change**.

#### 3. Security: serve the SPA

**File**: `src/main/java/org/borowiec/squashprogresstracker/security/SecurityConfig.java` (+ a small SPA-forwarding controller)

**Intent**: Let anonymous users load the SPA shell and its assets while keeping `/api/**` protected, and serve `index.html` for client-side routes so deep links / refreshes work.

**Contract**: extend `authorizeHttpRequests` to `permitAll` static resources (`/`, `/index.html`, `/assets/**`, and Vite's emitted asset paths / `favicon`). Also permit anonymous `GET` requests for SPA client routes/fallback paths (for example `/login`, `/register`, `/history`, `/matches/**`) so Spring Security does not reject the request before MVC can forward it. Add a forwarding controller (or `ErrorViewResolver`-style fallback) mapping non-API, non-asset GET routes to `forward:/index.html`, **without** intercepting `/api/**` or `/actuator/**` (see Critical Implementation Details). Keep `/api/**` protected except the existing public auth endpoints, and keep the JSON `401` entry point for anonymous API calls. `WelcomeController`'s plaintext `/` is replaced by the SPA index (remove or repurpose it).

### Success Criteria:

#### Automated Verification:

- `./mvnw package` builds the frontend and embeds it (jar contains `static/index.html` + assets)
- `./mvnw test` still green; SPA fallback does not break the anonymous-`/api`-→`401` test, and anonymous `GET /login` / `GET /register` serve or forward to the SPA

#### Manual Verification:

- `./run-local.sh` boots; visiting `/` serves the React app (not the old plaintext); a deep link like `/login` refreshed in the browser still loads the SPA
- Register a new account, log in, and land in the authenticated area through the UI; refresh keeps the session; logout returns to `/login`
- Unauthenticated navigation to an authenticated route redirects to `/login`; no CSRF `403` on login/register (bootstrap GET established the token)

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 4.

---

## Phase 4: Match UI — log form & history

### Overview

Build the actual user-facing payload on the proven pipe: a log-match form and a history view with opponent filtering, wired to the Phase 2 API.

### Changes Required:

#### 1. Log-match form

**File**: `frontend/src/` (a "Log match" page/component + its API call)

**Intent**: Let the player enter a match and save it, seeing the derived overall score before submit.

**Contract**: fields for opponent name, match date (default today; not future), notes (optional), and a dynamic list of set rows (add/remove, 1–5 sets) each with player and opponent score. Shows the derived overall (count of won sets) live. Submits `CreateMatchRequest` to `POST /api/matches`; on `400`, renders `ApiError.fieldErrors` against the right fields; on success, routes to history. Mirrors the backend validation bounds client-side for UX (but the server remains authoritative).

#### 2. History view with opponent filter

**File**: `frontend/src/` (a "History" page/component + its API calls)

**Intent**: Show the player's matches newest-first and let them narrow to one opponent.

**Contract**: on load, `GET /api/matches` (all, newest-first) and `GET /api/matches/opponents` (filter options). Each row shows opponent, date, overall result (e.g. `3–1 W`), set scores, and notes. An opponent selector (dropdown of distinct names + "All") re-queries `GET /api/matches?opponent=<name>`. Empty state when the player has no matches (or none for the selected opponent).

### Success Criteria:

#### Automated Verification:

- `./mvnw package` succeeds with the new components (frontend build clean — no type/lint errors if configured)

#### Manual Verification:

- In a browser: log a match (e.g. opponent "Kowalski", 3:1 with four set scores, a note) → it saves and appears at the top of history with the correct derived result
- Filtering by an opponent shows only that opponent's matches; "All" restores the full list
- Invalid input (blank opponent, future date, no sets, negative score) shows field errors and does not save
- A second account, freshly registered, sees an empty history — confirming the ownership boundary end-to-end through the UI

**Implementation Note**: Final phase — after manual confirmation, the slice is complete.

---

## Testing Strategy

### Unit Tests:

- Overall-score derivation in `MatchResponse.from` (won/lost/draw counts) — can be a focused unit test or asserted through the API test.

### Integration Tests (`MatchApiIntegrationTests`, mirroring `AuthIntegrationTests`):

- Create → `201`, response derivation correct.
- Validation matrix → `400` + `fieldErrors` (blank opponent, future date, empty sets, >5 sets, negative score).
- **Cross-player ownership (hard rule):** A's match invisible to B via list and opponent-filter; anonymous → `401`.
- `GET /api/matches/opponents` returns only the caller's distinct opponents.
- List ordering is newest-first.

### Manual Testing Steps:

1. `./run-local.sh`; register account A in the browser; log a match; confirm it appears in history with correct result.
2. Add a second match vs the same opponent and a third vs a different opponent; filter by each opponent.
3. Submit invalid forms; confirm field-level errors and no save.
4. Register account B; confirm B's history is empty (no leakage from A).
5. Refresh on a deep link and after login; confirm SPA routing + session persist.

## Performance Considerations

Data volumes are small (PRD: per-player, low QPS). The `(user_id, match_date)` and `(user_id, lower(opponent_name))` indexes cover the history and filter queries. No pagination yet — acceptable for MVP volumes; flagged as a known follow-up if a player exceeds ~50 matches.

## Migration Notes

`V2` is additive (two new tables, FKs to existing `users`); no data backfill, no changes to `V1` or the `users` table. `ddl-auto=validate` will fail boot if the entities and `V2` disagree — author them together.

## References

- Roadmap slice: `context/foundation/roadmap.md` (S-01)
- PRD: `context/foundation/prd.md` (FR-006, FR-007, Business Logic, Access Control guardrail)
- Auth foundation + patterns: `context/changes/minimal-auth/plan.md`, `context/changes/minimal-auth/plan-brief.md`
- Ownership seam: `src/main/java/org/borowiec/squashprogresstracker/security/CurrentUser.java:14`
- Pattern to mirror (entity/repo/DTO/error/test): `user/User.java`, `user/UserRepository.java`, `user/dto/`, `user/ApiExceptionHandler.java`, `user/AuthIntegrationTests.java`
- CSRF + security: `src/main/java/org/borowiec/squashprogresstracker/security/SecurityConfig.java`
- Build/deploy: `Dockerfile`, `render.yaml`, `run-local.sh`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Match data model & migration

#### Automated

- [x] 1.1 Compiles & context loads: `./mvnw test` (existing tests still pass; `ddl-auto=validate` accepts the new entities against `V2`) — 1ac7183
- [x] 1.2 Package builds: `./mvnw -DskipTests package` — 1ac7183

#### Manual

- [ ] 1.3 `./run-local.sh` boots; logs show Flyway applying `V2`; `matches` and `match_sets` tables exist with the FK + cascade.

### Phase 2: Match REST API

#### Automated

- [x] 2.1 `./mvnw test` passes including new `MatchApiIntegrationTests` (Testcontainers + MockMvc, mirroring `AuthIntegrationTests`) — 0a0d9c1
- [x] 2.2 Test: authenticated `POST /api/matches` with valid body → `201`; response `setsWon`/`setsLost` match the entered sets (derivation correctness, e.g. 3 won + 1 lost → 3/1) — 0a0d9c1
- [x] 2.3 Test: validation matrix → `400` with `fieldErrors` for blank opponent, future `matchDate`, empty `sets`, 6 sets, negative score — 0a0d9c1
- [x] 2.4 Test (ownership, hard rule): player A logs a match; player B's `GET /api/matches` returns `[]` and `GET /api/matches?opponent=<A's opponent>` returns `[]`; anonymous `GET /api/matches` → `401` — 0a0d9c1
- [x] 2.5 Test: `GET /api/matches/opponents` returns only the caller's distinct opponents and collapses case variants (`Kowalski` + `kowalski` yields one option) — 0a0d9c1

#### Manual

- [ ] 2.6 `curl` (with session cookie + CSRF header) create → list → filter → opponents flow returns expected JSON; overall result reads correctly.

### Phase 3: React SPA scaffold, build integration & auth shell

#### Automated

- [x] 3.1 `./mvnw package` builds the frontend and embeds it (jar contains `static/index.html` + assets) — 27f03b8
- [x] 3.2 `./mvnw test` still green; SPA fallback does not break the anonymous-`/api`-→`401` test, and anonymous `GET /login` / `GET /register` serve or forward to the SPA — 27f03b8

#### Manual

- [ ] 3.3 `./run-local.sh` boots; visiting `/` serves the React app (not the old plaintext); a deep link like `/login` refreshed in the browser still loads the SPA
- [ ] 3.4 Register a new account, log in, and land in the authenticated area through the UI; refresh keeps the session; logout returns to `/login`
- [ ] 3.5 Unauthenticated route redirects to `/login`; no CSRF `403` on login/register

### Phase 4: Match UI — log form & history

#### Automated

- [x] 4.1 `./mvnw package` succeeds with the new components (frontend build clean — no type/lint errors if configured)

#### Manual

- [ ] 4.2 In a browser: log a match (e.g. opponent "Kowalski", 3:1 with four set scores, a note) → it saves and appears at the top of history with the correct derived result
- [ ] 4.3 Opponent filter shows only that opponent's matches; "All" restores full list
- [ ] 4.4 Invalid input shows field errors and does not save
- [ ] 4.5 A second account, freshly registered, sees an empty history — confirming the ownership boundary end-to-end through the UI
