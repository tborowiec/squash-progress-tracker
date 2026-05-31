# Minimal Auth & Ownership Boundary — Implementation Plan

## Overview

Wire email+password authentication and the per-player ownership seam onto the existing Spring Boot 4.0.6 scaffold. This is roadmap foundation **F-01** — it unblocks every later data slice (S-01..S-04) by establishing a correct per-player boundary *before* any match domain exists, so no slice has to retrofit one (the classic cross-tenant leak).

Scope is deliberately minimal: persistence + migration tooling (the `users` table is the first migration), a Spring Security 7 filter chain, session-based JSON auth endpoints under `/api/auth/*`, a reusable current-user accessor, and a structured JSON error contract. **No match domain. No rendered UI** — the UI framework (React vs Thymeleaf) is an S-01 decision, and the auth layer is built UI-agnostic so either consumes it unchanged.

## Current State Analysis

From codebase research (2026-05-30):

- **Stack**: Spring Boot **4.0.6** / Java 21, Maven wrapper pinned (3.9.15). Spring Boot 4 ships **Spring Security 7 / Spring Framework 7** — component-based `SecurityFilterChain` bean config (no `WebSecurityConfigurerAdapter`).
- **Dependencies present** (`pom.xml`): `spring-boot-starter-webmvc`, `-actuator`, `-jdbc`, `postgresql` (runtime), `-devtools`, `spring-boot-starter-webmvc-test`. **Absent**: security, data-jpa, validation, flyway/liquibase, any template engine.
- **Code surface** (`src/main/java/com/example/squashprogresstracker/`): `SquashProgressTrackerApplication` (`@SpringBootApplication`) and `WelcomeController` (`@RestController` `@GetMapping("/")` returning a plain string). No `/api` routes.
- **Config** (`src/main/resources/application.properties`): datasource from env vars (`DB_HOST/PORT/NAME/USER/PASSWORD`), `server.port=${PORT:8080}`, actuator exposes only `health` with `show-details=never`. No JPA/Flyway config, no `db/migration/`, no `templates/`/`static/`.
- **Tests** (`src/test/java/.../SquashProgressTrackerApplicationTests.java`): a single `@SpringBootTest contextLoads()`. Note: `@SpringBootTest` boots the full context and therefore needs a reachable datasource — this is why integration tests need a real Postgres (see Testing Strategy).
- **Infra**: multi-stage `Dockerfile` (`./mvnw clean package -DskipTests`), `render.yaml` (web + Postgres 17, free tier, Frankfurt, `healthCheckPath: /actuator/health`, auto-deploy on commit, DB creds injected from the managed DB). `run-local.sh` auto-starts a local Postgres 17 container (`squash-postgres-dev`, db `squash`, user `postgres`/`pass`) then `./mvnw spring-boot:run`.

Key constraint: adding `spring-boot-starter-security` **locks down every endpoint by default** (HTTP Basic on `/`, `/actuator/health`, everything). The `SecurityFilterChain` must land in the same phase as the security dependency, or the health check and welcome endpoint break.

### Naming note (`User` over `Account`)

The principal entity is named **`User`** per the maintainer's preference, but two well-known gotchas shape how it's spelled out:

- **`user` is a reserved keyword in PostgreSQL.** The table is therefore named **`users`** (plural, not reserved) — never `user`, which would require quoting in every statement. The entity maps to it via `@Table(name = "users")`.
- **`User` collides by simple-name with Spring Security's `org.springframework.security.core.userdetails.User`.** In the `UserDetailsService` (and anywhere both are in scope), reference Spring's class by its fully-qualified name or use a custom `UserDetails` — do not let an IDE auto-import shadow the domain `User`.

## Desired End State

A signed-in user has an account in a Flyway-managed `users` table; they can register, sign in (session established via `JSESSIONID`), call authenticated endpoints, and sign out — all via JSON under `/api/auth/*`. Unauthenticated calls to protected `/api/**` return **401** (not a redirect). The public surface (`/`, `/actuator/health`, `register`, `login`) stays open. A reusable accessor resolves the current user's `User` id — the seam every future query filters on — and `GET /api/auth/me` proves it end-to-end. Errors follow a consistent JSON shape (duplicate email → 409, validation → 400 with field messages, bad credentials → 401).

Verify: `./mvnw test` green (incl. a Testcontainers integration test exercising register→login→me→logout + the error paths); `./mvnw spring-boot:run` boots, Flyway applies `V1`, `/actuator/health` is 200, and the full auth flow works via `curl`/HTTP client.

### Key Discoveries

- Security dep auto-locks all routes → filter chain and dep must ship together (Phase 2). `WelcomeController`'s `/` must be explicitly permitted.
- `ddl-auto=validate` + Flyway means **Flyway owns the schema**; Hibernate only validates the entity matches. The `User` entity field types must match `V1__create_users.sql` exactly or boot fails (this is the intended safety net).
- `@SpringBootTest` needs a datasource → Testcontainers Postgres for hermetic integration tests; do not assume H2 (Flyway Postgres SQL + Postgres-specific types would diverge).
- Session-based JSON login (not `formLogin` redirect): authenticate credentials, persist the `SecurityContext` to the session, return JSON. CSRF stays on via `CookieCsrfTokenRepository` (XSRF-TOKEN cookie a same-origin client echoes in a header).
- Naming: table is `users` (Postgres reserves `user`); the domain `User` must not be shadowed by Spring Security's `User` in imports.

## What We're NOT Doing

- No match/opponent/game-plan domain — no match table, no match endpoints.
- No rendered UI, no template engine, no React toolchain, no browser redirect-to-login (that lands in S-01 with the first UI).
- No JWT / stateless tokens, no OAuth / third-party sign-in (PRD §Non-Goals; parked in roadmap).
- No password reset / email verification / "remember me" / account deletion.
- No roles/authorities beyond "authenticated user" (flat user model per PRD §Access Control).
- No CI workflow changes (CI is planned, not yet wired — out of scope here).

## Implementation Approach

Three phases, each independently verifiable and committable: **persistence foundation → security chain → auth endpoints**. Persistence first so the `users` table and entity exist before security needs a `UserDetailsService` over them. Security second, landing the filter chain together with the dependency so existing endpoints survive. Endpoints last, building the register/login/logout/me surface and the JSON error contract on top of a working chain.

## Phase 1: Persistence & migration foundation

### Overview

Add JPA, Bean Validation, and Flyway; create the `users` table as the first migration; map it with a `User` entity + repository; configure Hibernate to validate (not generate) the schema.

### Changes Required:

#### 1. Dependencies

**File**: `pom.xml`

**Intent**: Add the persistence and migration starters needed for an entity-mapped, migration-owned `users` table.

**Contract**: Add `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `flyway-core`, and `flyway-database-postgresql` (the latter is required for Flyway + Postgres on Flyway 10+/Spring Boot 4). Keep the existing `-jdbc` dep (JPA pulls its own; leaving it is harmless) — do not remove the Postgres driver.

#### 2. Datasource / JPA / Flyway config

**File**: `src/main/resources/application.properties`

**Intent**: Make Flyway own the schema and Hibernate only validate against it; keep all existing datasource/env-var/actuator lines untouched.

**Contract**: Add `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`, and ensure Flyway is enabled (default-on; migrations resolved from `classpath:db/migration`). Do not add `spring.jpa.show-sql` noise. Optionally set `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`.

#### 3. First migration — users table

**File**: `src/main/resources/db/migration/V1__create_users.sql`

**Intent**: Create the `users` table — the durable identity every user and (later) every owned match row references.

**Contract**: Table name `users` (NOT `user` — reserved in Postgres). Columns: `id` (PK, `bigint generated by default as identity` or `bigserial`), `email` (`varchar`, `not null`, **unique** constraint), `password_hash` (`varchar`, `not null`), `created_at` (`timestamptz`, `not null`, default `now()`). Name the unique constraint explicitly (e.g. `uq_users_email`) so the duplicate-email path can be detected reliably.

#### 4. User entity + repository

**File**: `src/main/java/com/example/squashprogresstracker/user/User.java`, `.../user/UserRepository.java`

**Intent**: Map the `users` row and expose the lookups auth needs. Introduce a `user` sub-package as the home for this slice.

**Contract**: `User` `@Entity` (`@Table(name = "users")`) with fields matching the migration exactly (`id`, `email`, `passwordHash`, `createdAt`). `UserRepository extends JpaRepository<User, Long>` with `Optional<User> findByEmail(String email)` and `boolean existsByEmail(String email)`. Field types must mirror `V1` or `ddl-auto=validate` fails at boot (intended).

### Success Criteria:

#### Automated Verification:

- Compiles & context loads: `./mvnw test` (existing `contextLoads` still passes against a Postgres-backed context)
- Flyway applies cleanly and `ddl-auto=validate` passes on boot (entity matches `V1`)
- Package builds: `./mvnw -DskipTests package`

#### Manual Verification:

- `./mvnw spring-boot:run` (via `./run-local.sh`) boots; logs show Flyway migrating `V1`; `flyway_schema_history` + `users` table exist in the local Postgres
- `/actuator/health` returns 200 (DB UP)

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Security filter chain & password hashing

### Overview

Add Spring Security and configure the filter chain so the existing public endpoints survive, protected `/api/**` returns 401 for anonymous callers, CSRF uses a cookie token, and credentials are checked against the `users` table with BCrypt.

### Changes Required:

#### 1. Security dependency

**File**: `pom.xml`

**Intent**: Add Spring Security. **Must land with the filter chain below** — alone it locks every route behind Basic auth.

**Contract**: Add `spring-boot-starter-security`. (Optionally `spring-security-test` in test scope for `@WithMockUser`/auth test helpers.)

#### 2. Security configuration

**File**: `src/main/java/com/example/squashprogresstracker/security/SecurityConfig.java`

**Intent**: Define the authorization rules, session/CSRF behavior, and the password encoder for the whole app.

**Contract**: A `@Configuration` exposing:
- a `SecurityFilterChain` bean: `permitAll` for `/`, `/actuator/health`, `POST /api/auth/register`, `POST /api/auth/login`; `authenticated()` for everything else (notably `/api/**`).
- CSRF enabled with `CookieCsrfTokenRepository.withHttpOnlyFalse()` (XSRF-TOKEN cookie readable by a same-origin JS client).
- an `AuthenticationEntryPoint` returning **401** (not a redirect) for unauthenticated requests — so anonymous `/api/**` is a clean 401.
- session management left stateful (default `JSESSIONID`); no `formLogin` redirect.
- a `PasswordEncoder` bean = `BCryptPasswordEncoder` (or `PasswordEncoderFactories.createDelegatingPasswordEncoder()`).

Code note (load-bearing — other phases depend on the 401 entry point and the public-path set): the entry point must write `401` + a JSON body rather than the default `302`/Basic challenge, because the API contract is JSON-first.

#### 3. UserDetailsService over the users table

**File**: `src/main/java/com/example/squashprogresstracker/security/AppUserDetailsService.java`

**Intent**: Teach Spring Security how to load a principal from the `users` table by email.

**Contract**: `implements UserDetailsService`; `loadUserByUsername(email)` → `UserRepository.findByEmail`, mapping the domain `User` to a Spring `UserDetails` (username = email, password = `passwordHash`, a single default authority). Throw `UsernameNotFoundException` when absent. **Import gotcha**: reference Spring's `org.springframework.security.core.userdetails.User` by FQN (or build a custom `UserDetails`) so it doesn't shadow the domain `User`. The principal must carry (or allow recovery of) the domain `User` id for the Phase 3 accessor — either a custom `UserDetails` holding the id, or have the accessor re-resolve by email.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` passes (context loads with security on the classpath)
- An integration/slice test asserts: anonymous `GET /api/auth/me` → **401**; `GET /actuator/health` → **200**; `GET /` → **200**

#### Manual Verification:

- `./run-local.sh` boots with security active; `curl /actuator/health` → 200, `curl /` → 200
- `curl /api/auth/me` (no session) → 401 with a JSON body, not an HTML/Basic challenge

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Auth endpoints, current-user accessor & error contract

### Overview

Build the JSON auth surface (register, login, logout, me), the reusable current-user accessor, and a consistent JSON error contract with field validation.

### Changes Required:

#### 1. Request/response DTOs

**File**: `.../user/dto/` (e.g. `RegisterRequest.java`, `LoginRequest.java`, `UserResponse.java`, `ApiError.java`)

**Intent**: Define validated inputs and the stable shapes S-01's UI will consume; never expose `passwordHash`.

**Contract**: `RegisterRequest`/`LoginRequest` with `@Email`-annotated `email` and `@Size(min = 8)` `password` (Bean Validation). `UserResponse` carries `id` + `email` only. `ApiError` is the uniform error body (e.g. `status`, `message`, optional `fieldErrors` map).

#### 2. Auth controller

**File**: `src/main/java/com/example/squashprogresstracker/user/AuthController.java`

**Intent**: Expose the session-based JSON auth flow under `/api/auth`.

**Contract** (all under `/api/auth`, `@Valid` on request bodies):
- `POST /register` — reject if `existsByEmail` (→ 409); else BCrypt-hash and save; return 201 + `UserResponse`.
- `POST /login` — authenticate the credentials, **establish the session** (persist `SecurityContext` to the `HttpSession` via the configured `SecurityContextRepository`, or delegate to a `formLogin`-style filter with JSON success/failure handlers), return 200 + `UserResponse`; bad credentials → 401.
- `POST /logout` — invalidate the session + clear `JSESSIONID`/CSRF cookies (Spring Security `LogoutHandler`), return 200/204.
- `GET /me` — return the current user's `UserResponse` (authenticated-only; proves the boundary).

Code note (load-bearing — establishing the session from a JSON controller is the non-obvious bit): use the injected `AuthenticationManager` + `SecurityContextRepository.saveContext(...)`, *or* configure `formLogin` with `authenticationSuccessHandler`/`failureHandler` that write JSON instead of redirecting. Pick one and keep `/api/auth/login` consistent with it.

#### 3. Current-user accessor

**File**: `src/main/java/com/example/squashprogresstracker/security/CurrentUser.java` (helper/component) or a `@AuthenticationPrincipal`-resolved type

**Intent**: One reusable way to get the signed-in `User` id — the seam **every future slice's queries filter on**. This is the ownership-boundary primitive F-01 exists to establish.

**Contract**: Expose `currentUserId()` (and/or `currentUser()`), resolving from the `SecurityContext`/principal set in Phase 2. Document it as the mandatory filter source for owned data (ties to the AGENTS.md hard rule "enforce the auth boundary on every data-access query"). Register the load-bearing name in `docs/reference/contract-surfaces.md` if/when that registry exists.

#### 4. Global error handling

**File**: `src/main/java/com/example/squashprogresstracker/user/ApiExceptionHandler.java` (`@RestControllerAdvice`)

**Intent**: Map exceptions to the uniform `ApiError` JSON so S-01 has a stable contract.

**Contract**: `MethodArgumentNotValidException` → 400 with `fieldErrors`; duplicate-email (thrown by the controller, or `DataIntegrityViolationException` on the `uq_users_email` constraint) → 409; `BadCredentialsException` → 401. All emit `ApiError`.

### Success Criteria:

#### Automated Verification:

- `./mvnw test` passes, including a **Testcontainers** integration test (`@SpringBootTest` + `@Testcontainers` Postgres, MockMvc or `TestRestTemplate`) covering:
  - register → 201; duplicate email → 409
  - register with bad email / short password → 400 with field errors
  - login (good) → 200 + session cookie; login (bad) → 401
  - authenticated `GET /me` (with session) → 200 + correct identity; anonymous → 401
  - logout → 200/204, then `GET /me` → 401
- `./mvnw -DskipTests package` succeeds (Docker build path stays green)

#### Manual Verification:

- Via `curl`/HTTP client against `./run-local.sh`: full register → login (capture cookie + XSRF token) → me → logout flow works; `passwordHash` never appears in any response
- Duplicate registration returns 409 with the JSON `ApiError` shape; short password returns 400 with a field error

**Implementation Note**: After automated verification passes, pause for final manual confirmation.

---

## Testing Strategy

### Unit Tests:
- Password hashing: a registered user stores a BCrypt hash (not plaintext); `matches` succeeds for the right password.
- DTO validation: `@Email` / `@Size(min=8)` reject bad inputs (bean-validation level).

### Integration Tests (Testcontainers Postgres):
- `ddl-auto=validate` + Flyway require a **real Postgres**, so integration tests spin a `postgres:17` Testcontainer (matches Render + local). Add `org.testcontainers:junit-jupiter` + `:postgresql` (test scope) and either `@ServiceConnection` (Spring Boot 4) or property overrides to point the datasource at the container.
- Full auth flow + error matrix as enumerated in Phase 3 automated criteria.

### Manual Testing Steps:
1. `./run-local.sh` → confirm Flyway migrates `V1`, `/actuator/health` 200.
2. `curl -c jar -b jar` a register → login → me → logout sequence (fetch the XSRF-TOKEN cookie and echo it as the `X-XSRF-TOKEN` header on state-changing calls).
3. Re-register the same email → expect 409; register `password=short` → expect 400; `GET /api/auth/me` with no session → expect 401.

## Performance Considerations

Negligible at MVP scale (PRD: low QPS, small data). BCrypt cost is intentionally CPU-bound on login/register only; default work factor is fine. `open-in-view=false` avoids lazy-loading surprises later.

## Migration Notes

`V1__create_users.sql` is the first Flyway migration — it initializes `flyway_schema_history`. Greenfield DB, so no data backfill. Render's managed Postgres runs the same migration on first deploy after this lands (Flyway runs on app boot). Once `V1` is released, it is immutable — future schema changes are new `V{n}` files.

## References

- Roadmap item: `context/foundation/roadmap.md` → F-01 (lines 64–75)
- PRD: `context/foundation/prd.md` → FR-001, FR-002, §Access Control, §NFR (no cross-player access)
- Tech stack: `context/foundation/tech-stack.md` (`has_auth: true`, Spring Security first-class)
- Lessons: `context/foundation/lessons.md` (Linux Docker networking — relevant if a phase smoke-tests via sibling containers)
- Existing code: `WelcomeController` + `application.properties` (must stay public / untouched datasource lines)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Persistence & migration foundation

#### Automated

- [x] 1.1 Compiles & context loads: `./mvnw test` (existing `contextLoads` passes against Postgres-backed context)
- [x] 1.2 Flyway applies cleanly and `ddl-auto=validate` passes on boot
- [x] 1.3 Package builds: `./mvnw -DskipTests package`

#### Manual

- [x] 1.4 `./run-local.sh` boots; logs show Flyway migrating `V1`; `flyway_schema_history` + `users` table exist
- [x] 1.5 `/actuator/health` returns 200 (DB UP)

### Phase 2: Security filter chain & password hashing

#### Automated

- [ ] 2.1 `./mvnw test` passes with security on the classpath
- [ ] 2.2 Test: anonymous `GET /api/auth/me` → 401; `/actuator/health` → 200; `/` → 200

#### Manual

- [ ] 2.3 `./run-local.sh` boots; `curl /actuator/health` → 200, `curl /` → 200
- [ ] 2.4 `curl /api/auth/me` (no session) → 401 with JSON body (not HTML/Basic challenge)

### Phase 3: Auth endpoints, current-user accessor & error contract

#### Automated

- [ ] 3.1 `./mvnw test` passes incl. Testcontainers integration test (register/login/me/logout + error matrix)
- [ ] 3.2 `./mvnw -DskipTests package` succeeds

#### Manual

- [ ] 3.3 `curl` full register → login → me → logout flow works; `passwordHash` never in any response
- [ ] 3.4 Duplicate registration → 409 `ApiError`; short password → 400 field error
