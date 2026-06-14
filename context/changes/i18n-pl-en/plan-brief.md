# Internationalization (Polish & English) — Plan Brief

> Full plan: `context/changes/i18n-pl-en/plan.md`
> Research: `context/changes/i18n-pl-en/research.md`

## What & Why

Retrofit Polish/English localization across the already-shipped Squash Progress Tracker UI, persist each player's language choice against their account, and thread that locale into the AI game-plan prompt so a Polish interface yields a Polish plan. The primary persona is Polish-club squash players, so Polish is a first-class language — not a nice-to-have (PRD US-03, FR-011/012/013).

## Starting Point

Zero i18n infrastructure exists. The frontend holds ~90-120 inline English string literals across components/pages; `<html lang>` is hard-coded English. The `User` entity has no `locale` field, and `ddl-auto=validate` makes adding one a boot-blocker without a matching Flyway migration. The game-plan SSE path resolves auth/DB only on the request thread (`prepare()`), not the streaming virtual thread.

## Desired End State

A player sees the whole interface in Polish or English, auto-detected on first visit and switchable from the UI (after sign-in via NavHeader, before sign-in on auth pages). The choice persists to their account and follows them across devices. A game plan generated while the UI is Polish is written in Polish, with its AI-advice disclaimer also localized. Opponent names and match notes are never translated.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| i18n library | react-i18next + browser-languagedetector | Only turn-key browser detection, pure-runtime, no Vite/Biome friction | Research |
| Locale representation | Typed enum `Locale{EN,PL}`, col `VARCHAR(5) default 'en'` | Compile-time safety on a fixed 2-language set | Plan |
| Game-plan locale source | Persisted `users.locale`, read in `prepare()` | Single source of truth; satisfies FR-012+FR-013 together | Plan |
| Write API | `PUT /api/auth/me/locale` (+ `locale` on `UserResponse`) | Explicit, single-purpose, matches the `me()` precedent | Plan |
| AI-advice disclaimer | Localize PL + EN | English disclaimer in a Polish UI is a visible leak; keeps the label rule intact | Plan |
| Pre-auth switching | Standalone toggle on Login/Register | Manual control before sign-in; NavHeader absent there | Plan |
| Server-side errors | Stay English; frontend maps to localized copy | No MessageSource/LocaleResolver needed for a handful of strings | Plan |
| Phasing | Backend → frontend → game-plan threading | Boot-blocking migration + persisted value land before dependants | Plan |

## Scope

**In scope:** PL/EN bundles for all UI chrome; per-user persisted `locale` (migration + entity + read/write API); switcher (NavHeader + auth pages); browser auto-detect with EN fallback; game-plan prompt localization; localized AI-advice disclaimer.

**Out of scope:** translating user content (opponent names, match notes); languages beyond PL/EN; server-side `MessageSource`/`LocaleResolver`; a general profile controller or `PATCH /api/auth/me`; changes to the LLM client/DTOs; threading locale onto `AppUserDetails`.

## Architecture / Approach

Three loosely-coupled tracks, built backend-first. **Track 1 (backend):** Flyway `V3` + `Locale` enum + `User.locale` + `UserResponse.locale` + `PUT /api/auth/me/locale`. **Track 2 (frontend):** react-i18next init in `main.tsx`, PL/EN JSON bundles, string extraction, switcher, and wiring the switch through the API and the `/me` read. **Track 3 (game-plan):** read persisted locale in `GamePlanService.prepare()` (request thread — the virtual thread has no SecurityContext/tx), append a language directive in `GamePlanPromptBuilder`, and localize the disclaimer on the SSE `meta` event. FR-013 couples Track 3 to the locale produced by Tracks 1-2.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend persistence | `locale` column + enum + read/write API, boot-safe migration | `validate` boot failure if migration/entity diverge |
| 2. Frontend i18n + switcher | Fully localized UI, persisted switch, auto-detect | Untranslated strings leaking (trap 1); plural correctness |
| 3. Game-plan threading | Polish plan + localized disclaimer | Silent English fallback (trap 2); thread-split violation |

**Prerequisites:** F-01 (auth + per-user persistence) and S-02 (game-plan generation) — both archived/done.
**Estimated effort:** ~3 sessions, one per phase.

## Open Risks & Assumptions

- **Polish output quality from Gemini `gemini-2.5-flash`** — assumed to honor a Polish-output directive with no quality regression; verify during Phase 3 manual testing.
- **Migration/entity must deploy together** — `ddl-auto=validate` fails boot otherwise.
- **Locale must reach `prepare()` on the request thread** — reading it on the virtual thread fails (no SecurityContext/tx); this is a hard constraint from commit `8e35a39`.

## Success Criteria (Summary)

- Every screen reads fully in Polish and fully in English — no untranslated strings leak (trap 1).
- The language choice persists to the account and follows the player across sessions/devices.
- A game plan generated with a Polish UI is written in Polish, with a Polish, always-present AI-advice label (trap 2).
