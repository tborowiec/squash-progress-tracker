---
date: 2026-06-14T16:49:14+02:00
researcher: Tomasz Borowiec
git_commit: 8fe0a0a3efbce23346bded496323333e01d2a5bd
branch: roadmap-i18n-pl-en
repository: squash-progress-tracker
topic: "Internationalization (Polish & English) — S-05 i18n-pl-en"
tags: [research, codebase, i18n, locale, react-i18next, game-plan, users, flyway]
status: complete
last_updated: 2026-06-14
last_updated_by: Tomasz Borowiec
---

# Research: Internationalization (Polish & English) — S-05 `i18n-pl-en`

**Date**: 2026-06-14T16:49:14+02:00
**Researcher**: Tomasz Borowiec
**Git Commit**: 8fe0a0a3efbce23346bded496323333e01d2a5bd
**Branch**: roadmap-i18n-pl-en
**Repository**: squash-progress-tracker

## Research Question

Ground the S-05 i18n slice (PL/EN) in the actual codebase: how to localize the React frontend (library choice + injection points), how to persist a per-user `locale` on the backend, and how to thread that locale into the AI game-plan prompt so a Polish UI yields a Polish plan. Scope per user: representative string sample (not exhaustive inventory) + a 2-3 option library comparison.

## Summary

The feature is a **greenfield retrofit** — there is **zero existing i18n infrastructure** in either the frontend or backend. The work decomposes into three loosely-coupled tracks plus a labelling check:

1. **Frontend localization** — install `react-i18next` + `i18next-browser-languagedetector`, mount init in `main.tsx`, extract ~90-120 inline English string literals into PL/EN JSON bundles, add a switcher to `NavHeader`. **Recommended library: react-i18next** (only candidate with turn-key browser detection, pure-runtime so no Vite/Babel/Biome friction; matches the roadmap's provisional pick).
2. **Backend locale persistence** — add a `locale` column to `users` (Flyway `V3`, **mandatory** because `ddl-auto=validate`), surface it on `GET /api/auth/me` via `UserResponse`, add a write endpoint on `AuthController`. Auth-principal access goes through the `CurrentUser` component, not `@AuthenticationPrincipal`.
3. **Game-plan locale threading** — add a locale param to `GamePlanPromptBuilder.build(...)` and append a language directive to the **system message**; locale must be resolved on the **request thread** inside `GamePlanService.prepare(...)` (the streaming virtual thread has no `SecurityContext`/DB tx). The LLM client and DTOs need **no changes**.
4. **AI-advice label** is emitted as a separate SSE `meta` event in Java (not part of the prompt), so localizing the prompt does **not** break it — but the disclaimer string itself is a candidate for localization too.

Two correctness traps the roadmap flagged are both real and have concrete homes here: untranslated strings leaking (enforce keys, the densest files are `MatchForm.tsx`/`HistoryPage.tsx`/auth pages) and the plan silently falling back to English (the locale must reach `GamePlanPromptBuilder.java:24`, not stop at the frontend).

## Detailed Findings

### Frontend i18n landscape (React 18.3 + Vite 6 + TS + Biome)

**Stack / current state.** React 18.3.1 (`frontend/package.json:21-22`), Vite 6 (`frontend/vite.config.ts`), Biome 2.4.16 (`frontend/biome.json:20-28`: single quotes, 2-space, `semicolons: asNeeded`, lineWidth 100; JSX double quotes), TS strict (`frontend/tsconfig.json:7`). Router is `react-router-dom` ^6.28.0 (`frontend/package.json:24`), wired at `frontend/src/main.tsx:3,14` and `frontend/src/App.tsx:15-26`. HTTP via a shared **axios** instance (`frontend/src/api/client.ts:3-6`, `withCredentials: true`, CSRF interceptor `:38-47`) plus raw `EventSource` for game-plan SSE (`frontend/src/api/gameplans.ts:15`). **No i18n library is installed** (grep: zero hits for `i18n`/`locale`/`navigator.language`). `frontend/index.html:2` hard-codes `<html lang="en">`.

**Provider mount point.** `frontend/src/main.tsx:12-18` — wrap `<App />` mirroring how `AuthProvider` wraps `<Routes>` at `frontend/src/App.tsx:14`. The existing single context `frontend/src/contexts/AuthContext.tsx:10-30` (`createContext` → Provider → `useAuth` hook with null guard) is the pattern template.

**String-handling pattern: 100% inline hard-coded English literals** — JSX text, button labels, `placeholder` attrs, error fallbacks. No string table, no constants. Representative examples:
- `frontend/src/components/NavHeader.tsx:65` `Squash Progress Tracker`; `:74` `Sign out`
- `frontend/src/pages/HomePage.tsx:86-87` `Dashboard`, `Welcome, {user?.email}` (**interpolated**)
- `frontend/src/pages/LoginPage.tsx:120,154,110,159` — `Sign in`, `'Signing in…'`, fallback error, `No account? <Link>Register</Link>` (**element interpolation**)
- `frontend/src/components/MatchForm.tsx:258,262,305,319,367,381` — `Match details`, `Opponent`, placeholder `'Optional notes…'`, `Set {i + 1}` (**interpolated**), `+ Add set {n}/5` (**interpolated + conditional**), `'Saving…'`
- `frontend/src/pages/LogMatchPage.tsx:192,196,159,137` — long NL placeholder, AI-parse banners, parse-error fallback
- `frontend/src/pages/HistoryPage.tsx:235,262,267,288,279` — `All opponents`, `No matches yet.`, `Log your first match →`, `Delete this match?`, `{setsWon}–{setsLost} {result}` (**interpolated; `result` is backend enum WON/LOST/DRAW**, `frontend/src/api/matches.ts:36`)
- `frontend/src/pages/GamePlanPage.tsx:168,181,189,201` — `Select opponent…`, `'Generating…'`, the AI-advice label, AI-unavailable error

**Estimate: ~90-120 user-facing strings**, all inline English. Densest: `MatchForm.tsx` (~12-15), `HistoryPage.tsx` (~12), `LoginPage.tsx`+`RegisterPage.tsx` (~10 each, largely duplicated → shared `auth.*` keys), `GamePlanPage.tsx` (~8), `LogMatchPage.tsx` (~10), `HomePage.tsx` (~7).

**Interpolation / pluralization needs** (the correctness-sensitive cases):
- Interpolation: `Welcome, {email}` (`HomePage.tsx:87`), `Set {i+1}` (`MatchForm.tsx:319`), `Add set ({n}/5)` (`MatchForm.tsx:367`), `{setsWon}–{setsLost} {result}` (`HistoryPage.tsx:279`, `MatchForm.tsx:374-375`).
- The `result` enum (WON/LOST/DRAW) is surfaced raw and needs localized label mapping.
- **One real pluralization**: `GamePlanPage.tsx:193-194` — `({matchCount} match{matchCount === 1 ? '' : 'es'})`. Polish needs the 3-form plural (one/few/many), so this is the spot that exercises i18next's plural rules.

**Switcher placement.** `frontend/src/components/NavHeader.tsx:67` (the `s.right` flex cluster, `gap: 1.25rem`, already holds nav links + Sign out). Caveat: `NavHeader` is **not** rendered on Login/Register (they render standalone cards), so a pre-auth toggle needs separate placement near `LoginPage.tsx:119` / `RegisterPage.tsx:127`, or accept post-login-only switching there.

**Reading the persisted locale.** `GET /api/auth/me` (`frontend/src/api/auth.ts:14`, called once in `AuthContext.tsx:17`) is the carrier — extend it to also call `i18n.changeLanguage(user.locale)`. **Backend has no locale field yet** (see below). Because the persisted locale arrives async after the `/me` round-trip, the clean sequence: synchronous detect (navigator/localStorage) at `main.tsx` for pre-auth screens → `i18n.changeLanguage(user.locale)` once `AuthContext` resolves. Update `<html lang>` (`index.html:2`) on `languageChanged`.

### Backend locale persistence (Java 21 / Spring Boot 4)

**User entity / table.** `src/main/java/org/borowiec/squashprogresstracker/user/User.java` — `@Table(name = "users")`, fields `id`/`email`/`passwordHash`/`createdAt` only (no `locale`). Repo `UserRepository extends JpaRepository<User, Long>` with `findByEmail`/`existsByEmail`.

**Migrations = Flyway, and the migration is MANDATORY.** `pom.xml` has `spring-boot-starter-flyway` + `flyway-database-postgresql`. `application.properties` sets `spring.jpa.hibernate.ddl-auto=validate` — Hibernate validates schema against entities at boot and **will fail startup** if you add `locale` to the entity without a matching column. Migrations live in `src/main/resources/db/migration/` (`V1__create_users.sql`, `V2__create_matches.sql` latest). Next file: **`V3__add_users_locale.sql`** following the `V<n>__snake_case.sql` convention, e.g.:
```sql
ALTER TABLE users ADD COLUMN locale VARCHAR(5) NOT NULL DEFAULT 'en';
```
The `NOT NULL DEFAULT` keeps `validate` happy and backfills existing rows (mirrors `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`). DB connection uses discrete `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` env vars (no `spring.flyway.*` overrides).

**Auth-principal access = the `CurrentUser` component, not `@AuthenticationPrincipal`.** `security/CurrentUser.java` exposes `currentUserId()` / `principal()` (reads `SecurityContextHolder`, casts to `AppUserDetails`). Precedent in `AuthController.me()` (lines 81-86): `var userId = currentUser.currentUserId(); var user = userRepository.findById(userId).orElseThrow();`. A locale GET/PUT follows the same pattern. `AppUserDetails` (`security/AppUserDetails.java`) and `AppUserDetailsService.loadUserByUsername` (`:26-31`) carry no locale today — threading locale onto the principal is **optional** for a DB-persisted preference but useful if the game-plan path reads locale from the principal.

**Controllers / where it fits.** `user/AuthController.java` (`@RequestMapping("/api/auth")`: register/login/logout/me) is the natural home — **no separate profile/settings controller exists**. Recommendation: extend `UserResponse` with `locale` and add `PUT /api/auth/me/locale` (or `PATCH /api/auth/me`). No new controller needed. Other controllers: `match/MatchController.java` (`/api/matches`), `match/gameplan/GamePlanController.java` (`/api/game-plans`), `user/ApiExceptionHandler.java` (`@RestControllerAdvice`).

**DTO pattern = `record` + static `from(entity)`.** `user/dto/UserResponse.java` is `record UserResponse(Long id, String email)` with `static from(User)` (deliberately omits `passwordHash` — a test asserts its absence). Add a `String locale` component + `user.getLocale()` in `from`. A write request would be a validated record `record UpdateLocaleRequest(@NotBlank String locale)`.

**Accept-Language / server i18n = none exists.** Zero matches for `LocaleResolver`/`LocaleChangeInterceptor`/`MessageSource`/`Accept-Language`. There is no `config/` package (config classes sit at package root + `security/`). The persisted `users.locale` is the source of truth; `Accept-Language` would only seed the initial default at registration. The minimal preference feature needs no `MessageSource`/`LocaleResolver`.

**Test pattern.** `src/test/java/.../user/AuthIntegrationTests.java`: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` (`PostgreSQLContainer("postgres:17")` with `@ServiceConnection`, real Flyway run), `MockHttpSession` from a real login, `.with(csrf())` on mutations, `jsonPath` assertions. A locale test: register → login (capture session) → PUT locale (csrf+session) → `GET /me` assert `$.locale`. Cross-user boundary precedent: `match/MatchOwnershipBoundaryTests.java`.

### Game-plan LLM locale threading

**End-to-end flow:**

| Stage | Class : method | file:line |
|---|---|---|
| Controller (SSE) | `GamePlanController.stream(@RequestParam String opponent) → SseEmitter` | `match/gameplan/GamePlanController.java:29-62` |
| Service (prepare, request thread) | `GamePlanService.prepare(String opponentName) → GamePlanContext` | `match/gameplan/GamePlanService.java:34-45` |
| Service (stream, virtual thread) | `GamePlanService.stream(GamePlanContext, Consumer<String>)` | `match/gameplan/GamePlanService.java:47-49` |
| Prompt builder | `GamePlanPromptBuilder.build(String opponent, List<Match>) → LlmRequest` | `match/gameplan/GamePlanPromptBuilder.java:23-27` |
| LLM client | `OpenAiCompatLlmClient.generateStreaming(LlmRequest, Consumer<String>)` | `llm/client/OpenAiCompatLlmClient.java:116-143` |

**THE injection point: `GamePlanPromptBuilder.java:24`** — append the language directive to the **system message** content inside `build()`. Currently `var system = new LlmMessage(LlmRole.SYSTEM, SYSTEM_MESSAGE);` (`SYSTEM_MESSAGE` constant at `:15-21`). To localize, add a locale param and append e.g. `"\nRespond in Polish."`. The system message is the right home (it already carries behavioral directives `:17-21` and tests assert against it). Signature changes: `build(String opponent, List<Match>, Locale locale)`.

**Locale delivery path (must respect the thread split):** `GamePlanController.stream(...)` (`:30`) resolves locale → `GamePlanService.prepare(opponent, locale)` (`:34-45`) → `promptBuilder.build(opponent, matches, locale)`. The streaming **virtual thread** (`Thread.ofVirtual().start(...)`, `GamePlanController.java:35-59`) has **no `SecurityContext` and no DB transaction** — both are confined to `prepare()` on the request thread (this is the existing fix for the SSE eager-security-headers data race). So locale **must be read inside `prepare()`** and baked into the `LlmRequest` carried by `GamePlanContext`; the virtual thread only replays it.

**Three options for where locale enters** (in order of architectural fit):
1. **Derived from authenticated user (recommended, matches S-05)** — `prepare()` already calls `CurrentUser.currentUserId()` (`GamePlanService.java:36`). Requires the new `users.locale` column; read via `userRepository.findById(...)` in `prepare()` (or thread onto `AppUserDetails`).
2. **`Accept-Language` header** — zero new persistence; `@RequestHeader(HttpHeaders.ACCEPT_LANGUAGE)` in the controller. Fastest, but PRD requires persisted-per-user, so this alone is insufficient for FR-012.
3. **Request param** `?lang=` — simplest, pushes choice to frontend caller.
> Recommended: option 1 (persisted user locale) to satisfy FR-012 + FR-013 together; the frontend already knows the active language, so passing it as a param (option 3) is an acceptable fallback if reading the entity in `prepare()` is undesired — but the PRD's "locale must reach the prompt, not stop at the frontend" is satisfied either way as long as the value reaches `build()`.

**LLM client / DTOs need NO changes.** `LlmRequest` (`llm/dto/LlmRequest.java:6`) and `LlmMessage` (`llm/dto/LlmMessage.java:3`) have no language field and don't need one — language is baked into message `content` before the request leaves `build()`. `buildBody()` (`OpenAiCompatLlmClient.java:62-75`) serializes verbatim.

**AI-advice label is independent of the prompt.** `AiDisclaimer.TEXT` (`llm/AiDisclaimer.java:5`) is emitted as a separate SSE `meta` event (`GamePlanController.java:37-40`, `MetaPayload` record `:84`) — built in Java, not part of the prompt or model output. Localizing the prompt will **not** affect it. If the disclaimer should also be localized (likely desired for a Polish UI), that's a small separate change to `AiDisclaimer`/`MetaPayload`, independent of prompt injection. **Hard rule reminder:** every AI game plan must stay labelled as AI-generated advice (AGENTS.md) — don't drop the label while localizing.

**Test pattern.** `match/gameplan/GamePlanPromptBuilderTests.java` is a pure unit test (`new GamePlanPromptBuilder()`, `:15`; content extracted via `userMessage(request)` helper `:64-70` and an inline SYSTEM extractor `:30-34`; `makeMatch(...)` fixture `:72-85`). A localized test calls `build("Kowalski", matches, Locale.forLanguageTag("pl"))` and asserts the system message contains the Polish instruction (mirroring `build_systemMessageConstrainsToLoggedData` `:28-37`), plus an English/default case. End-to-end SSE coverage: `match/gameplan/GamePlanApiIntegrationTests.java`.

### Library comparison (PART A)

Constraints: React 18.3 + Vite 6 + TS 5.7 + Biome 2.4 + Vitest; small MVP, **PL+EN only**; needs browser detection, runtime switch, interpolation, JSON bundles, good TS, minimal bundle.

| Criterion | **react-i18next** (+ browser-languagedetector) | **react-intl** (FormatJS) | **LinguiJS** |
|---|---|---|---|
| Version (2026) | i18next v25 / react-i18next v15 | react-intl v7 | @lingui v5 |
| Popularity | **~7.0M weekly dl** (leader) | ~2.5M | ~265k |
| Bundle (gz) | ~8 KB core, ~15-20 KB w/ plugins | ~13 KB core | **~2-3 KB runtime** (smallest; rest compiled away) |
| Browser detection | **First-class plugin** (navigator/localStorage cascade, drop-in) | Manual (read `navigator.language`) | Manual (`@lingui/detect-locale`) |
| Runtime switch | `i18n.changeLanguage()` trivial | `<IntlProvider locale>` swap | `i18n.activate()` |
| Interpolation | `t('win',{name})`, `{{name}}` default | ICU `{name}` | ICU via macros |
| TS support | Good (typed keys need declaration-merging) | Decent | **Best** (compile-time macros) |
| JSON bundles | **Yes (asked-for)** | JSON/ICU | PO/JSON via JSX macros |
| Vite / Biome friction | **None** (pure runtime) | None | **Vite plugin + macro codegen** can trip Biome |

**Recommendation: `react-i18next` + `i18next-browser-languagedetector`.** It satisfies every literal requirement, is the **only candidate with turn-key browser detection** (the roadmap's explicit `navigator.language` ask), is pure-runtime so it adds **no Vite/Babel/SWC plumbing and no macro codegen to trip the strict per-edit Biome hook**, and matches the roadmap's provisional pick (`roadmap.md:152`). LinguiJS's smaller bundle / better types don't pay off at PL+EN/small-MVP scale and bring extraction-pipeline + Biome friction; react-intl's ICU is overkill. Only minor cost: manual TS declaration-merging for typed keys (well-documented). Detection order: persisted user locale (post-`/me`) → localStorage → `navigator.language` → fallback `en`.

## Code References

- `frontend/package.json:21-24` — React 18.3.1, react-router 6.28, axios; no i18n dep
- `frontend/src/main.tsx:12-18` — i18n init / `I18nextProvider` mount point
- `frontend/src/App.tsx:14` — `AuthProvider` wrap pattern to mirror
- `frontend/src/contexts/AuthContext.tsx:17` — `me()` call; read persisted locale here, call `changeLanguage`
- `frontend/src/api/auth.ts:14` — `GET /api/auth/me` (locale carrier)
- `frontend/src/components/NavHeader.tsx:67` — `s.right` cluster, switcher home (not on Login/Register)
- `frontend/src/components/MatchForm.tsx`, `frontend/src/pages/HistoryPage.tsx` — densest string files
- `frontend/src/pages/GamePlanPage.tsx:193-194` — the one PL 3-form pluralization case
- `frontend/index.html:2` — `<html lang="en">` to sync on language change
- `src/main/java/org/borowiec/squashprogresstracker/user/User.java` — entity; add `locale` field
- `src/main/resources/db/migration/V2__create_matches.sql` — latest migration; next is `V3__add_users_locale.sql`
- `application.properties` — `spring.jpa.hibernate.ddl-auto=validate` (migration mandatory)
- `security/CurrentUser.java` — auth-principal access primitive (`currentUserId()`)
- `user/AuthController.java:81-86` — `me()` precedent; home for locale GET/PUT
- `user/dto/UserResponse.java:5` — `record` + `from(User)`; add `locale` component
- `match/gameplan/GamePlanPromptBuilder.java:24` — **prompt locale injection point** (system message)
- `match/gameplan/GamePlanService.java:34-45` — `prepare()` on request thread; resolve locale here
- `match/gameplan/GamePlanController.java:30,35-59` — `stream()` request param + virtual-thread split
- `llm/AiDisclaimer.java:5` + `GamePlanController.java:37-40` — AI-advice label (separate SSE meta event)
- `src/test/java/.../user/AuthIntegrationTests.java` — controller integration test pattern
- `match/gameplan/GamePlanPromptBuilderTests.java:28-37` — prompt unit-test pattern

## Architecture Insights

- **Three loosely-coupled tracks.** Frontend strings, backend `locale` column, and game-plan prompt threading are independent enough to plan/implement in parallel, but FR-013 (Polish UI → Polish plan) couples track 3 to whatever produces the active locale (track 1's switcher / track 2's persisted value).
- **The request-thread / virtual-thread split is a hard constraint** introduced by prior SSE fixes (`8e35a39`, eager-security-headers data race). Any user- or header-derived locale for the game plan MUST be captured in `prepare()`, not in the streaming thread.
- **`ddl-auto=validate` makes the Flyway migration a boot-blocker**, not a nicety — entity change + `V3` migration must land together.
- **DTOs are `record` + static `from(entity)`; auth is `CurrentUser`, not `@AuthenticationPrincipal`** — follow both conventions exactly.
- **Localize only chrome + game-plan output, never user content** (PRD §Boundaries, `prd.md:130`) — opponent names and match notes are player-owned free text and must pass through verbatim.
- **Two correctness traps with concrete homes**: (1) leaked untranslated strings → enforce keys, no hard-coded copy (densest in `MatchForm`/`History`/auth pages); (2) plan falling back to English → assert the directive lands at `GamePlanPromptBuilder.java:24` and is exercised by a `pl` unit test.

## Historical Context (from prior changes)

- **No prior i18n/locale/translation work** exists anywhere in `context/changes/**` or `context/archive/**` (grep hits for "translat" are all LLM *error* translation; "language" hits are `language_family: java`). This is greenfield.
- `context/foundation/prd.md` (v2, 2026-06-14) — **US-03** (`prd.md:72-82`), **FR-011/012/013** (`prd.md:117-122`): PL+EN, browser auto-detect → fallback EN, switcher persisted **to the account**, AI plans in the active UI language. `prd.md:130`: user content never machine-translated.
- `context/foundation/roadmap.md:143-155` — slice **S-05** (Stream C). `:152` provisional pick "**react-i18next + JSON bundles** — confirm at /10x-plan" (this research confirms it). `:145` persistence design = **`locale` column on `users`**. `:153` injection point = `GamePlanPromptBuilder`. `:155` the two correctness traps. `:174` RESOLVED 2026-06-14: FRs backfilled into PRD v2.
- `context/foundation/tech-stack.md` — backend-focused, **no frontend or i18n section**; the React/Vite stack lives only in `frontend/package.json`.
- `context/foundation/shape-notes.md` (v1, predates i18n) — persona is club squash player "technically comfortable"; Polish context implicit via "beat Kowalski 3:1" (`:89`). PRD later makes Polish a first-class language (`prd.md:118`).

## Related Research

- None yet — this is the first research artifact for `i18n-pl-en`. Prerequisite slices archived: `context/archive/2026-06-04-ai-match-entry/` (S-03), and the F-01 / S-02 (game-plan) archives referenced by `roadmap.md:166-168`.

## Open Questions

1. **Locale source for the game plan** — read the persisted `users.locale` inside `prepare()` (preferred per FR-012/013), or accept a `?lang=` param the frontend already knows? Decide at `/10x-plan`. (Persisted is the explicit S-05 ask; param is a lower-risk fallback.)
2. **Locale representation** — `VARCHAR(5)` storing a BCP-47 tag (`en`/`pl`) vs a typed `@Enumerated` enum (LOCALE.EN/PL). Enum is safer given the fixed 2-language set; column default `'en'`.
3. **Localize the AI-advice disclaimer?** `AiDisclaimer.TEXT` is English-only and emitted as a Java-built SSE `meta` event. A Polish UI showing an English disclaimer is a polish gap — localize `AiDisclaimer`/`MetaPayload` (small, independent change) or accept English. Recommend localizing.
4. **Pre-auth language switching** on Login/Register (no `NavHeader`) — add a standalone toggle there, or accept post-login-only? FR-011 (auto-detect on first visit) is satisfied by detection regardless; only manual switching pre-login is in question.
5. **Map the `result` enum (WON/LOST/DRAW) to localized labels** — currently surfaced raw (`HistoryPage.tsx:279`); needs a key per value.
6. **Server-side `MessageSource`/`LocaleResolver`?** Not needed for the minimal feature (validation/error messages from `ApiExceptionHandler` are the only server-emitted strings the user might see) — confirm whether API error messages must be localized or can stay English.
