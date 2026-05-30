# Minimal Auth & Ownership Boundary — Plan Brief

> Full plan: `context/changes/minimal-auth/plan.md`

## What & Why

Wire email+password authentication and the per-player ownership seam onto the existing Spring Boot 4 scaffold (roadmap **F-01**). Auth is sequenced first so every later data slice (S-01..S-04) inherits a correct per-player boundary instead of retrofitting one — a retrofit is the classic cross-tenant leak the PRD's privacy guardrail forbids ("one player's history is never visible to another").

## Starting Point

Bare Spring Boot 4.0.6 / Java 21 scaffold: only `SquashProgressTrackerApplication` + a `WelcomeController` at `/`. Persistence is JDBC-only (Postgres driver, no JPA, no migrations); auth is entirely absent (no Spring Security on the classpath); no `/api` routes; no UI. DB is wired via env vars to Postgres 17 (local Docker + Render).

## Desired End State

A player can register, sign in (session via `JSESSIONID`), call authenticated endpoints, and sign out — all as JSON under `/api/auth/*`. Anonymous calls to protected `/api/**` return 401; `/`, `/actuator/health`, register, and login stay public. The `users` table is Flyway-managed, a reusable accessor resolves the signed-in user's id (the seam future queries filter on), and `GET /api/auth/me` proves the boundary end-to-end. No match domain, no UI.

> **Naming:** the principal entity is `User` (table `users` — `user` is a reserved word in Postgres). The domain `User` must not be shadowed by Spring Security's `org.springframework.security.core.userdetails.User` in imports.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Persistence layer | Spring Data JPA, `ddl-auto=validate` | Convention-based/agent-friendly; clean per-player scoped queries later | Plan |
| Migration tooling | Flyway (SQL, owns schema) | Simplest, SQL-native, ubiquitous; Postgres-only is fine | Plan |
| Auth surface / UI | UI-agnostic session JSON `/api/auth/*`; defer UI to S-01 | Keeps React **and** Thymeleaf open, nothing thrown away, minimal under time budget | Plan |
| Auth style | Session (`JSESSIONID`), not JWT | Conventional for a server-side web-app MVP; less complexity | Plan |
| Password hashing | BCrypt | Spring Security default `PasswordEncoder` | Plan |
| Ownership proof | Current-user accessor + `GET /api/auth/me` | Makes the boundary end-to-end testable now, seeds S-01's pattern | Plan |
| CSRF | On, `CookieCsrfTokenRepository` | Preserves the security guardrail; React-friendly token pattern | Plan |
| Error contract | Structured JSON: 409 / 400+fields / 401 | Stable, displayable contract for S-01; no silent bad data | Plan |

## Scope

**In scope:** JPA + Flyway + validation deps; `users` table (`V1`); `User` entity/repository; Spring Security 7 filter chain (BCrypt, cookie CSRF, 401 entry point); `register`/`login`/`logout`/`me` JSON endpoints; current-user accessor; `@RestControllerAdvice` error handling; Testcontainers integration test.

**Out of scope:** Match/opponent/game-plan domain; any UI / template engine / React; JWT/OAuth/third-party sign-in; password reset / email verification / remember-me; roles beyond "authenticated player"; CI workflow changes; browser redirect-to-login (lands in S-01).

## Architecture / Approach

One Spring Boot app. Flyway owns the schema; Hibernate validates the `Account` entity against it. Spring Security 7 `SecurityFilterChain` keeps public routes open, authenticates the rest, returns JSON 401 for anonymous `/api/**`, and uses a cookie CSRF token. `AuthController` runs session-based JSON login (authenticate → persist `SecurityContext` to session). A `CurrentUser` accessor exposes the signed-in `User` id as the single ownership-filter seam every future slice reuses.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Persistence & migration | JPA + Flyway deps, `account` table + `V1`, entity/repository | `ddl-auto=validate` mismatch between entity and `V1` fails boot (intended safety net) |
| 2. Security filter chain | Spring Security + `SecurityFilterChain`, BCrypt, UserDetailsService | Security dep auto-locks `/` + `/actuator/health` unless the chain ships in the same phase |
| 3. Auth endpoints + accessor + errors | `register/login/logout/me`, current-user accessor, JSON error contract | Establishing the session from a JSON controller (no `formLogin` redirect) is the fiddly bit |

**Prerequisites:** none (F-01 has no upstream slice). Local Postgres via `./run-local.sh`; Testcontainers for tests.
**Estimated effort:** ~2–3 focused sessions across the 3 phases.

## Open Risks & Assumptions

- Spring Boot 4 / Security 7 are recent — confirm `SecurityFilterChain` + `CookieCsrfTokenRepository` + `@ServiceConnection` APIs against the 4.0.6 docs while implementing.
- The existing `contextLoads` test needs a reachable datasource; Testcontainers is assumed for hermetic runs (adds test-scope deps).
- Session-based JSON login has two valid implementations (`AuthenticationManager` + `SecurityContextRepository`, or `formLogin` with JSON handlers) — pick one and keep `/api/auth/login` consistent.

## Success Criteria (Summary)

- A player can register, log in, hit an authenticated endpoint, and log out; another (anonymous) caller is rejected with 401.
- `passwordHash` never appears in any response; duplicate email → 409, invalid input → 400, bad credentials → 401.
- `./mvnw test` green including the Testcontainers auth-flow integration test; app boots with Flyway applying `V1` and `/actuator/health` 200.
