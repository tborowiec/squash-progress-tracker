# Internationalization (Polish & English) — S-05 Implementation Plan

## Overview

Retrofit Polish/English localization across the already-shipped Squash Progress Tracker UI, persist each player's language choice against their account, and thread that locale into the AI game-plan prompt so a Polish interface yields a Polish plan. This satisfies PRD US-03 and FR-011/FR-012/FR-013. There is **zero existing i18n infrastructure** today — this is a greenfield retrofit decomposed into three loosely-coupled tracks, built backend-first.

## Current State Analysis

- **No i18n anywhere.** Grep finds zero hits for `i18n`/`locale`/`navigator.language` in the frontend and no `LocaleResolver`/`MessageSource`/`Accept-Language` in the backend. `frontend/index.html:2` hard-codes `<html lang="en">`.
- **Frontend strings are 100% inline English literals** — ~90-120 of them across JSX text, button labels, `placeholder` attrs, and error fallbacks. No string table or constants. Densest: `MatchForm.tsx` (~12-15), `HistoryPage.tsx` (~12), `LoginPage.tsx`/`RegisterPage.tsx` (~10 each, largely duplicated), `GamePlanPage.tsx` (~8), `LogMatchPage.tsx` (~10), `HomePage.tsx` (~7).
- **`User` entity has no `locale` field** (`user/User.java`: `id`/`email`/`passwordHash`/`createdAt` only) and `spring.jpa.hibernate.ddl-auto=validate` means adding the field without a matching column **fails startup** — the Flyway migration is a boot-blocker, not a nicety.
- **The game-plan SSE path has a request-thread / virtual-thread split** (from the prior eager-security-headers fix, commit `8e35a39`): `SecurityContext` and the DB transaction live only on the request thread inside `GamePlanService.prepare()`. The streaming virtual thread has neither. Any user-derived locale MUST be resolved in `prepare()` and baked into the carried `LlmRequest`.
- **The AI-advice label is independent of the prompt** — `AiDisclaimer.TEXT` is emitted as a separate Java-built SSE `meta` event (`GamePlanController.java:37-40`), so localizing the prompt won't touch it; localizing the disclaimer is a small separate change on the same path.

## Desired End State

A signed-in player sees the entire interface in Polish or English. On first visit the language auto-detects from the browser (falling back to English for unsupported languages). The player can switch language from the UI — both after sign-in (NavHeader) and before sign-in (auth pages) — and the choice persists to their account, following them across sessions and devices. Any game plan generated while the UI is Polish is written in Polish, and its AI-advice disclaimer is shown in the active language. Opponent names and match notes are never translated.

**Verification of end state:** Switch language → reload → language sticks (persisted). Sign out, sign in on a fresh browser → language follows the account. Generate a game plan with the UI in Polish → plan text and disclaimer are Polish. Walk every screen in both languages → no untranslated string leaks.

### Key Discoveries:

- **Library: `react-i18next` + `i18next-browser-languagedetector`** (`research.md` PART A) — only candidate with turn-key browser detection, pure-runtime (no Vite/Babel/macro codegen to trip the strict per-edit Biome hook), matches the roadmap's provisional pick (`roadmap.md:152`).
- **i18n init/provider mount: `frontend/src/main.tsx:12-18`**, mirroring how `AuthProvider` wraps `<Routes>` (`frontend/src/App.tsx:14`).
- **Persisted-locale carrier: `GET /api/auth/me`** (`frontend/src/api/auth.ts:14`, called once in `AuthContext.tsx:17`) — call `i18n.changeLanguage(user.locale)` once it resolves.
- **Flyway next file: `V3__add_users_locale.sql`** following `V<n>__snake_case.sql`; column needs `NOT NULL DEFAULT 'en'` to satisfy `validate` and backfill existing rows (mirrors `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`).
- **Auth-principal access = `CurrentUser` component** (`security/CurrentUser.java`, `currentUserId()`), not `@AuthenticationPrincipal`. Precedent: `AuthController.me()` (`:81-86`).
- **DTO pattern = `record` + static `from(entity)`** (`user/dto/UserResponse.java`); a test asserts `passwordHash` is absent.
- **Prompt injection point: `GamePlanPromptBuilder.java:24`** — append the language directive to the **system message** (`SYSTEM_MESSAGE` constant `:15-21`); tests already assert against it.
- **The one PL 3-form plural: `GamePlanPage.tsx:193-194`** — `({matchCount} match{...})`; exercises i18next plural rules.
- **The `result` enum (WON/LOST/DRAW)** is surfaced raw (`HistoryPage.tsx:279`, `MatchForm.tsx:374-375`, `frontend/src/api/matches.ts:36`) and needs a localized label per value.

## What We're NOT Doing

- **Not** machine-translating user content — opponent names and match notes pass through verbatim (PRD §Boundaries, `prd.md:130`).
- **Not** adding a server-side `MessageSource`/`LocaleResolver`. API error messages stay English; the frontend maps known error states to localized copy via i18next keys (it already renders fallback strings today).
- **Not** adding languages beyond PL/EN — the MVP language set is fixed (PRD NFR). The `Locale` enum encodes this.
- **Not** building a general profile/settings controller or a `PATCH /api/auth/me` — a dedicated `PUT /api/auth/me/locale` covers the single field.
- **Not** changing the LLM client or LLM DTOs (`LlmRequest`/`LlmMessage`) — language is baked into the message `content` before the request leaves `build()`.
- **Not** threading locale onto `AppUserDetails` — the persisted column read in `prepare()` is the source of truth.

## Implementation Approach

Backend-first, three phases. Phase 1 lands the boot-blocking migration, the `Locale` enum, the persisted column, and the read/write API — so the value everything else consumes exists and is testable before any dependant. Phase 2 retrofits the frontend: library init, JSON bundles, string extraction, the switcher (NavHeader + auth pages), and wiring the switch through `PUT /api/auth/me/locale` and the `/me` read. Phase 3 threads the persisted locale into the game-plan prompt inside `prepare()` (respecting the thread split) and localizes the disclaimer. FR-013 is the integration point that couples Phase 3 to the locale produced by Phases 1-2.

## Critical Implementation Details

- **Timing & lifecycle (frontend locale resolution).** The persisted locale arrives async after the `/me` round-trip. Sequence: synchronous browser/localStorage detection at `main.tsx` init (covers pre-auth screens and first paint) → `i18n.changeLanguage(user.locale)` once `AuthContext` resolves `/me`. Update `<html lang>` (`index.html:2`) on the i18next `languageChanged` event so it tracks both.
- **State sequencing (game-plan thread split).** Locale MUST be read inside `GamePlanService.prepare()` on the request thread (where `SecurityContext` + DB tx exist) and baked into the `LlmRequest` carried by `GamePlanContext`. The streaming virtual thread only replays it — reading the user/locale there will fail (no `SecurityContext`, no tx). This is the existing constraint from commit `8e35a39`.
- **Ordering (migration + entity).** The `V3` migration and the `User.locale` entity field must land together in the same change — `ddl-auto=validate` fails boot if the entity has a field with no column, or (less likely here) a column with no field mapping is fine but the enum mapping must match the column type. Add both before running the app.

---

## Phase 1: Backend locale persistence

### Overview

Add a persisted, type-safe per-user language preference and the API to read and write it. After this phase the backend stores `locale` on every user, returns it on `/api/auth/me`, and accepts updates — with the boot-blocking migration in place.

### Changes Required:

#### 1. Flyway migration

**File**: `src/main/resources/db/migration/V3__add_users_locale.sql`

**Intent**: Add a non-null `locale` column to `users`, backfilling existing rows to `'en'` so the `validate` schema check passes at boot.

**Contract**: New migration following the `V<n>__snake_case.sql` convention (latest is `V2__create_matches.sql`). Column `locale VARCHAR(5) NOT NULL DEFAULT 'en'`, mirroring the `NOT NULL DEFAULT` shape of `created_at`.

#### 2. Locale enum

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/Locale.java` (new)

**Intent**: A fixed two-value enum (`EN`, `PL`) encoding the MVP's fixed language set, with a mapping to/from the stored tag (`en`/`pl`) so it serializes to the BCP-47 string clients expect and persists to the `VARCHAR(5)` column.

**Contract**: `enum Locale { EN, PL }` with a `String tag()` (lowercase) and a static `fromTag(String)` (defaulting/validating to `EN` on unknown). Decide persistence mapping so the DB stores `'en'`/`'pl'` (the lowercase tag), not `EN`/`PL` — keeps the column human-readable and matches the migration default. (Use `@Convert` with an `AttributeConverter`, or store the tag and resolve in code — implementer's call following the entity's existing field style.)

#### 3. User entity

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/User.java`

**Intent**: Add a `locale` field of type `Locale`, defaulting to `EN`, mapped to the new column.

**Contract**: New field + getter/setter (or accessor matching the entity's existing style). Mapping must produce the `'en'`/`'pl'` tag in the `locale` column to match the migration. Default `EN` for newly constructed users.

#### 4. UserResponse DTO

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/dto/UserResponse.java`

**Intent**: Surface the persisted locale on `/api/auth/me` so the frontend can apply it after sign-in.

**Contract**: Add a `String locale` component to the `record`; populate it from `user.getLocale().tag()` in static `from(User)`. Continue to omit `passwordHash` (an existing test asserts its absence).

#### 5. Update-locale request DTO

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/dto/UpdateLocaleRequest.java` (new)

**Intent**: Validated request body for the write endpoint.

**Contract**: `record UpdateLocaleRequest(@NotBlank String locale)`. The controller resolves it via `Locale.fromTag(...)`; an unsupported tag is rejected (400) rather than silently coerced — validate explicitly.

#### 6. Write + read endpoint on AuthController

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/AuthController.java`

**Intent**: Add `PUT /api/auth/me/locale` to persist the authenticated user's language choice; the read side is already covered by the now-extended `me()`.

**Contract**: New handler under the existing `@RequestMapping("/api/auth")`, path `me/locale`, method `PUT`, body `UpdateLocaleRequest`. Resolve the user via `currentUser.currentUserId()` → `userRepository.findById(...).orElseThrow()` (the `me()` precedent at `:81-86`), set the locale, save, return the updated `UserResponse` (or 204). Mutating endpoint → requires CSRF (the test harness uses `.with(csrf())`).

### Success Criteria:

#### Automated Verification:

- App boots (Flyway `V3` applies, `validate` passes): `./mvnw spring-boot:run` starts without schema-validation error (or covered by the `@SpringBootTest` context load).
- Backend tests pass: `./mvnw test`
- New integration test passes: register → login (capture session) → `PUT /api/auth/me/locale` (csrf + session) with `pl` → `GET /api/auth/me` asserts `$.locale == 'pl'`.
- Invalid-locale write is rejected: `PUT` with an unsupported tag returns 4xx.
- Spotless check passes: `./mvnw spotless:check`

#### Manual Verification:

- Existing rows backfilled to `en` after migration (inspect `users` table).
- A second user's locale change does not affect the first user's persisted locale (cross-user isolation, per the `MatchOwnershipBoundaryTests` precedent).

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Frontend i18n foundation, string extraction & switcher

### Overview

Install and initialize react-i18next, extract every user-facing string into PL/EN JSON bundles, add the language switcher (NavHeader + standalone on auth pages), and wire the switch through the Phase 1 endpoint and the `/me` read so the choice persists. After this phase the entire interface is localized and the player's choice survives reloads and sign-in.

### Changes Required:

#### 1. Dependencies

**File**: `frontend/package.json`

**Intent**: Add the runtime i18n libraries.

**Contract**: Add `react-i18next`, `i18next`, and `i18next-browser-languagedetector` as dependencies. Install with `npm install` from `frontend/`. (No Vite/Biome config changes — pure runtime.)

#### 2. i18n initialization module

**File**: `frontend/src/i18n/index.ts` (new) + `frontend/src/main.tsx`

**Intent**: Configure i18next with the PL/EN bundles, the browser language detector (cascade: localStorage → `navigator.language` → fallback `en`), supported-languages allow-list (`en`, `pl`), and interpolation/plural defaults; import it at app entry so detection runs before first paint.

**Contract**: i18next `init({ resources, fallbackLng: 'en', supportedLngs: ['en','pl'], detection: { order: ['localStorage','navigator'], caches: ['localStorage'] } })`. Wrap `<App />` with `I18nextProvider` (or rely on the global instance) at `frontend/src/main.tsx:12-18`, mirroring the `AuthProvider` wrap. Register a `languageChanged` listener that sets `document.documentElement.lang` (replacing the hard-coded `<html lang="en">` at `index.html:2`).

#### 3. Translation bundles

**File**: `frontend/src/i18n/locales/en.json`, `frontend/src/i18n/locales/pl.json` (new)

**Intent**: Hold every user-facing string keyed by feature namespace, including interpolation placeholders, the `result` enum labels, and the one pluralized count.

**Contract**: Mirrored key trees in both files. Namespaces by area (e.g. `nav.*`, `home.*`, `auth.*` shared by Login/Register, `matchForm.*`, `history.*`, `logMatch.*`, `gamePlan.*`). Interpolation keys use i18next `{{var}}` syntax: `home.welcome` (`{{email}}`), `matchForm.setLabel` (`{{n}}`), `matchForm.addSet` (`{{n}}`/`{{max}}`), `history.score` (`{{won}}`/`{{lost}}`/`{{result}}`). The `result` enum maps via `match.result.WON|LOST|DRAW`. The pluralized count at `GamePlanPage.tsx:193-194` uses i18next plural keys (`gamePlan.matchCount_one`/`_few`/`_many`/`_other`) so Polish gets its 3-form plural and English its 2-form. **No keys for opponent names or match notes** — those render verbatim.

#### 4. String extraction across components

**File**: `frontend/src/components/NavHeader.tsx`, `frontend/src/components/MatchForm.tsx`, `frontend/src/pages/HomePage.tsx`, `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/RegisterPage.tsx`, `frontend/src/pages/HistoryPage.tsx`, `frontend/src/pages/LogMatchPage.tsx`, `frontend/src/pages/GamePlanPage.tsx`

**Intent**: Replace every inline English literal (JSX text, button labels, `placeholder` attrs, error fallbacks) with `t('key')` calls, using `useTranslation()`. Interpolated and pluralized strings use the t-function's interpolation/count options.

**Contract**: Each component calls `const { t } = useTranslation()` and references keys from the bundles above. Enforce **no hard-coded user-facing copy remains** — this is correctness trap (1) from the roadmap. Known interpolation/plural sites (must not be string-concatenated): `HomePage.tsx:87`, `MatchForm.tsx:319,367`, `HistoryPage.tsx:279`, `MatchForm.tsx:374-375`, `GamePlanPage.tsx:193-194`. The `result` enum (`HistoryPage.tsx:279`, `MatchForm.tsx:374-375`) maps through `t('match.result.' + result)`. Leave the AI-advice label text on `GamePlanPage.tsx` to be driven by the localized value the backend now sends in Phase 3's `meta` event (frontend renders what it receives) — or key it locally if the page renders its own copy; confirm which during extraction.

#### 5. Language switcher component

**File**: `frontend/src/components/LanguageSwitcher.tsx` (new), used in `frontend/src/components/NavHeader.tsx`, `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/RegisterPage.tsx`

**Intent**: A reusable PL/EN switcher. In NavHeader it persists the choice to the account; on auth pages (no NavHeader, pre-auth) it switches locally via localStorage only.

**Contract**: A small control (toggle or select) calling `i18n.changeLanguage(tag)`. When the user is authenticated, also call the locale API (`updateLocale(tag)` — see #6) to persist. Placed in the `s.right` flex cluster at `NavHeader.tsx:67`, and as a standalone element near `LoginPage.tsx:119` / `RegisterPage.tsx:127`. The pre-auth instance does not call the API (no session); localStorage caching by the detector preserves the choice into the post-login session until `/me` overrides it.

#### 6. Locale API client + AuthContext wiring

**File**: `frontend/src/api/auth.ts`, `frontend/src/contexts/AuthContext.tsx`

**Intent**: Add a typed `updateLocale` call hitting `PUT /api/auth/me/locale`, extend the `me()` response type with `locale`, and apply the persisted locale once `AuthContext` resolves `/me`.

**Contract**: `updateLocale(locale: string)` on the auth API using the shared axios instance (`withCredentials`, CSRF interceptor already handle session+token). The `me()` response type gains `locale: string`. In `AuthContext.tsx:17`, after `/me` resolves, call `i18n.changeLanguage(user.locale)` (this is the authoritative per-account value that overrides the pre-auth detection).

### Success Criteria:

#### Automated Verification:

- Type check passes: `npm run typecheck` (from `frontend/`)
- Biome check passes: `npm run lint` (from `frontend/`)
- Build succeeds: `npm run build` (from `frontend/`)

#### Manual Verification:

- Every screen (Home, Login, Register, Log match, History, Game plan, NavHeader) renders fully in Polish and fully in English — no untranslated string leaks in either language (correctness trap 1).
- The `({matchCount} matches)` count reads correctly in Polish across 1 / 2-4 / 5+ matches (3-form plural) and in English (singular/plural).
- Opponent names and match notes render verbatim, untranslated, in both languages.
- Switch language in NavHeader → reload → choice sticks (persisted to account via `/me`).
- Sign out → sign in on a fresh browser/profile → language follows the account.
- Switch language on the Login page pre-auth → UI updates immediately; after signing in, the account's persisted locale applies.
- First visit with a Polish browser shows Polish; with an unsupported browser language shows English.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding to Phase 3.

---

## Phase 3: Game-plan locale threading & disclaimer localization

### Overview

Thread the persisted locale into the game-plan prompt so a Polish UI yields a Polish plan (FR-013), and localize the AI-advice disclaimer sent on the SSE `meta` event — both respecting the request-thread / virtual-thread split.

### Changes Required:

#### 1. Prompt builder locale parameter

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanPromptBuilder.java`

**Intent**: Accept a locale and append a language directive to the system message so the model responds in the active language; default/English produces the current behavior.

**Contract**: Signature becomes `build(String opponent, List<Match> matches, Locale locale)`. Append a directive to the `SYSTEM_MESSAGE` content (`:15-21`, injected at `:24`) — e.g. a Polish instruction for `PL`, nothing or an English instruction for `EN`. Language is baked into the message `content`; **no `LlmRequest`/`LlmMessage` change**.

#### 2. Resolve locale in prepare()

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanService.java`

**Intent**: Read the authenticated user's persisted locale on the request thread and pass it to `build()`, so the streaming virtual thread only replays a fully-formed `LlmRequest`.

**Contract**: In `prepare(...)` (`:34-45`, already calls `CurrentUser.currentUserId()`), load the user (`userRepository.findById(...)`) and read `locale`, then call `promptBuilder.build(opponent, matches, locale)`. Must happen in `prepare()` — the virtual thread has no `SecurityContext`/DB tx (constraint from commit `8e35a39`). No change to `GamePlanController`'s stream signature is required if locale comes from the user rather than a param.

#### 3. Localize the AI-advice disclaimer

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/AiDisclaimer.java`, `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanController.java`

**Intent**: Send the disclaimer text in the active language on the SSE `meta` event, keeping the AGENTS.md "AI-generated advice" label present in both languages.

**Contract**: `AiDisclaimer` gains a localized lookup (e.g. `text(Locale)` returning the PL or EN string); the `MetaPayload` built in `GamePlanController` (`:37-40,:84`) uses the resolved locale. Locale must be available on the request thread before the `meta` event is emitted — resolve it alongside Phase 3 #2 (carry it on `GamePlanContext` or read it in the controller's request-thread scope). **The label must never be dropped** — only its language changes.

### Success Criteria:

#### Automated Verification:

- Backend tests pass: `./mvnw test`
- New prompt-builder unit test: `build("Kowalski", matches, Locale.PL)` asserts the system message contains the Polish directive; an `EN`/default case asserts the English (or no-directive) behavior — mirroring `build_systemMessageConstrainsToLoggedData` (`GamePlanPromptBuilderTests.java:28-37`).
- SSE integration test (`GamePlanApiIntegrationTests`) still passes and the `meta` event carries a disclaimer string (label present).
- Spotless check passes: `./mvnw spotless:check`

#### Manual Verification:

- With the UI in Polish, generate a game plan → the plan text is in Polish (correctness trap 2: it does not silently fall back to English).
- With the UI in English, the plan is in English.
- The AI-advice disclaimer shown above/around the plan is in the active language and is always present (never dropped).
- Switching language then generating a new plan produces a plan in the newly-active language (persisted locale reaches the prompt).

**Implementation Note**: After completing this phase and all automated verification passes, pause for final manual confirmation.

---

## Testing Strategy

### Unit Tests:

- `GamePlanPromptBuilderTests`: Polish directive present for `Locale.PL`; English/default behavior preserved (pure unit, `new GamePlanPromptBuilder()`, content extracted via the existing SYSTEM extractor `:30-34`).
- Frontend: type-check + build act as the structural guard that all `t()` keys resolve and bundles are well-formed.

### Integration Tests:

- `AuthIntegrationTests`-style: register → login → `PUT /api/auth/me/locale` (csrf+session) → `GET /api/auth/me` asserts `$.locale`. Invalid tag → 4xx. Cross-user isolation (one user's change doesn't affect another), per `MatchOwnershipBoundaryTests`.
- `GamePlanApiIntegrationTests`: SSE stream still completes and emits a disclaimer-bearing `meta` event.

### Manual Testing Steps:

1. Walk every screen in Polish, then English — confirm zero untranslated leaks (correctness trap 1).
2. Verify the match-count plural in Polish (1 / 2-4 / 5+) and English.
3. Confirm opponent names and notes render verbatim in both languages.
4. Switch language, reload, sign out/in on a fresh browser — choice persists and follows the account.
5. Generate a game plan in Polish — plan text + disclaimer are Polish (correctness trap 2); repeat in English.

## Performance Considerations

Negligible. react-i18next is pure-runtime (~8 KB core + ~15-20 KB with plugins, gzipped). The only added backend cost is one `findById` in `prepare()` to read the locale (the path already loads user-scoped data there). No new round-trips on the hot path.

## Migration Notes

`V3__add_users_locale.sql` adds `locale VARCHAR(5) NOT NULL DEFAULT 'en'`, backfilling existing rows to English. Because `ddl-auto=validate`, the migration and the `User.locale` entity field must deploy together — the entity field without the column (or a type mismatch) fails boot. Rollback would require a `V4` dropping the column plus reverting the entity; no data loss for existing match data either way.

## References

- Research: `context/changes/i18n-pl-en/research.md`
- PRD: `context/foundation/prd.md` — US-03 (`:72-82`), FR-011/012/013 (`:117-122`), boundary (`:130`)
- Roadmap: `context/foundation/roadmap.md:143-155` (S-05)
- Prompt injection point: `match/gameplan/GamePlanPromptBuilder.java:24`
- Thread-split constraint: `match/gameplan/GamePlanService.java:34-45`, `GamePlanController.java:35-59` (commit `8e35a39`)
- DTO + auth precedent: `user/dto/UserResponse.java`, `user/AuthController.java:81-86`, `security/CurrentUser.java`
- Test patterns: `user/AuthIntegrationTests.java`, `match/gameplan/GamePlanPromptBuilderTests.java:28-37`, `match/MatchOwnershipBoundaryTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend locale persistence

#### Automated

- [x] 1.1 App boots — Flyway V3 applies and `validate` passes — e56118e
- [x] 1.2 Backend tests pass (`./mvnw test`) — e56118e
- [x] 1.3 Integration test passes — register → login → PUT locale → GET /me asserts `$.locale == 'pl'` — e56118e
- [x] 1.4 Invalid-locale write returns 4xx — e56118e
- [x] 1.5 Spotless check passes (`./mvnw spotless:check`) — e56118e

#### Manual

- [x] 1.6 Existing rows backfilled to `en` after migration — e56118e
- [x] 1.7 Cross-user isolation — one user's locale change doesn't affect another — e56118e

### Phase 2: Frontend i18n foundation, string extraction & switcher

#### Automated

- [x] 2.1 Type check passes (`npm run typecheck`)
- [x] 2.2 Biome check passes (`npm run lint`)
- [x] 2.3 Build succeeds (`npm run build`)

#### Manual

- [x] 2.4 Every screen renders fully in PL and EN — no untranslated leaks
- [x] 2.5 Match-count plural correct in Polish (1 / 2-4 / 5+) and English
- [x] 2.6 Opponent names and notes render verbatim in both languages
- [x] 2.7 Switch in NavHeader → reload → choice sticks (persisted)
- [x] 2.8 Sign out → sign in on fresh browser → language follows the account
- [x] 2.9 Pre-auth switch on Login page works; account locale applies after sign-in
- [x] 2.10 First visit auto-detects Polish browser → Polish; unsupported → English

### Phase 3: Game-plan locale threading & disclaimer localization

#### Automated

- [ ] 3.1 Backend tests pass (`./mvnw test`)
- [ ] 3.2 Prompt-builder unit test — Polish directive for `Locale.PL`, English/default case
- [ ] 3.3 SSE integration test passes — `meta` event carries disclaimer (label present)
- [ ] 3.4 Spotless check passes (`./mvnw spotless:check`)

#### Manual

- [ ] 3.5 Polish UI → game plan text is Polish (no silent English fallback)
- [ ] 3.6 English UI → plan is English
- [ ] 3.7 Disclaimer shown in active language and always present
- [ ] 3.8 Switching language then generating produces a plan in the new language
