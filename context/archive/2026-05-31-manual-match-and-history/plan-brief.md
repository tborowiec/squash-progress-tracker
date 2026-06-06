# Manual Match Logging & History — Plan Brief

> Full plan: `context/changes/manual-match-and-history/plan.md`

## What & Why

Roadmap slice **S-01**: let a signed-in player log a squash match through a structured form and view their history filtered by opponent (FR-006, FR-007). It's the first user-visible vertical — it introduces the match domain and the app's first rendered UI, putting real, ownership-scoped match data behind the API so the north star (S-02, AI game plan) has data to reason over, without waiting on the undecided LLM provider.

## Starting Point

The `minimal-auth` foundation is fully implemented: `users` table + Flyway, Spring Security session/JSON auth under `/api/auth/*`, and the ownership seam `CurrentUser.currentUserId()` every owned query must filter on. There is **no UI of any kind** (only a plaintext `WelcomeController` at `/`) and **no match domain**. One multi-stage Dockerfile + one Render web service; tests use Testcontainers Postgres 17 + MockMvc.

## Desired End State

In a browser, a player can register/log in, open a "Log match" form (opponent, date, 1–5 set scores, optional notes), see the derived overall score, save it, and find it at the top of their history — which they can filter by opponent. Player B never sees player A's matches. Ships as one `./mvnw package` artifact (Dockerfile unchanged), boots with Flyway applying `V2`, and `./mvnw test` is green including a cross-player ownership test.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| UI layer | React SPA, bundled into Spring | Richest fit for later AI progress UX; auth was built JSON-first to keep it open | Plan |
| SPA build/serve | `frontend-maven-plugin` → `static/`, single deployable | One `./mvnw package` artifact, same-origin session+CSRF, Dockerfile unchanged | Plan |
| Auth UI scope | Minimal login + register pages + route guard | SPA is unreachable in a browser otherwise; `minimal-auth` parked this for S-01 | Plan |
| Set scores | Normalized `match_sets` child table (`@OneToMany`) | Most JPA-conventional; first-class & queryable for S-02 | Plan |
| Overall score | Derived from set scores (not stored) | Single source of truth; set & overall scores can't disagree | Plan |
| Opponent | Free-text name on the match | Cheapest; matches S-03's AI name extraction; no opponent CRUD | Plan |
| Validation | Light structural (required fields, 1–5 sets, non-negative, not future) | Prevents garbage without modeling PAR-11/win-by-2 rule variants | Plan |
| History view | Full list, newest-first, opponent filter; no pagination | Delivers FR-007 directly; pagination deferred as known follow-up | Plan |

## Scope

**In scope:** `V2` migration (`matches` + `match_sets`); `Match`/`MatchSet` entities + ownership-scoped `MatchRepository` with explicit set fetching for response reads and case-collapsed opponent options; `MatchService` + `MatchController` (`POST /api/matches`, `GET /api/matches?opponent=`, `GET /api/matches/opponents`); derived overall score; light validation; React SPA (Vite) bundled via Maven; SecurityConfig static/SPA-fallback permits including anonymous GET access to SPA routes; login/register pages + route guard + CSRF wiring; log-match form + history view; Testcontainers/MockMvc tests incl. cross-player ownership.

**Out of scope:** AI parsing (S-03) & game plan (S-02); edit/delete (S-04); first-class Opponent entity; stored overall score; squash-rules legality validation; pagination; summary stats; CORS/second deployment; any change to `/api/auth/*` behavior.

## Architecture / Approach

One Spring Boot deployable. Backend first (Phases 1–2): `matches` parent scoped by `user_id` with a `match_sets` child via `@OneToMany`; response-producing match reads explicitly fetch `sets` and run in read-only transactions; overall score derived (count of won sets); API mirrors the existing DTO/validation/`ApiError` patterns; every read filtered through `CurrentUser.currentUserId()`. Then the SPA (Phases 3–4): a `frontend/` Vite React app built into `static/` by `frontend-maven-plugin` (dev proxies `/api` to `:8080`); SecurityConfig opens static assets and anonymous SPA GET routes + forwards non-API routes to `index.html` while keeping `/api/**` behind the JSON `401`; CSRF rides the existing `XSRF-TOKEN`→`X-XSRF-TOKEN` cookie/header pair.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model & migration | `V2` tables, `Match`/`MatchSet` entities, ownership-scoped repository | `ddl-auto=validate` mismatch between entities and `V2` fails boot (intended safety net) |
| 2. Match REST API | DTOs, service, controller, derived score, tests incl. ownership | Getting the ownership filter on every query right — the privacy hard rule |
| 3. SPA scaffold, build & auth shell | Vite app, Maven build integration, security/CSRF wiring, login/register + guard | Build-into-jar + SPA fallback not swallowing `/api`; CSRF cookie before first POST |
| 4. Match UI | Log-match form + opponent-filtered history | Dynamic set rows + client/server validation parity; faithful derived-score display |

**Prerequisites:** F-01 (`minimal-auth`) — done. Local Postgres via `./run-local.sh`; Node toolchain for the frontend build; Testcontainers for tests.
**Estimated effort:** ~3–4 focused sessions across the 4 phases (the SPA introduction is the heaviest new surface).

## Open Risks & Assumptions

- React SPA is a brand-new layer on a Java/Spring stack — the build-into-single-jar integration (`frontend-maven-plugin`) and SPA-fallback security rules are the main novel risk; Phase 3 isolates them before feature work.
- CSRF: the SPA must materialize the `XSRF-TOKEN` cookie (a bootstrap `GET /api/auth/me`) before the first mutating request or it gets a `403`.
- Free-text opponent names can fragment history (`Kowalski` vs `kowalski`) — mitigated by grouping opponent options on `lower(opponent_name)` with a deterministic display value; a first-class Opponent entity is the fallback if it bites in S-02.
- No automated frontend tests planned beyond a clean build — UI correctness is covered by manual verification.

## Success Criteria (Summary)

- A player logs a match via the form and sees it in their opponent-filterable history with the correct derived result.
- A second player's history is empty — one player's matches are never visible to another (verified by an automated ownership test **and** manually).
- `./mvnw test` green incl. ownership + validation + derivation tests; `./mvnw package` yields one jar with the SPA embedded; app boots with `V2` applied.
