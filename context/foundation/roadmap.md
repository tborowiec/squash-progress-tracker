---
project: "Squash Progress Tracker"
version: 2
status: draft
created: 2026-05-30
updated: 2026-06-14
prd_version: 2
main_goal: speed
top_blocker: time
---

# Roadmap: Squash Progress Tracker

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline (2026-05-30).
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

Competitive squash players have no tool that turns match history into a pre-match game plan: they either log nothing or keep a spreadsheet that surfaces no tactics. This product removes the recording friction (natural-language entry, AI-parsed into a confirmable preview) so the data pipeline survives — but the data is just fuel. The payoff is an AI-generated tactical game plan for the next match against a specific, recurring opponent, drawn from that player's own history and clearly labelled as AI advice.

## North star

**S-02: User selects an opponent and receives an AI-generated game plan** — this is the validation milestone for the core product hypothesis: that AI advice over a player's own match history is worth more than a spreadsheet. Everything else (recording, history, CRUD) only matters if this output lands.

> "North star" here means the smallest end-to-end slice whose successful delivery would prove the product's core hypothesis — placed as early as its prerequisites allow, because nothing else matters if this doesn't work. It is not the *first* thing built (it needs auth, one saved match, and an LLM client first), but it is the thing the early slices exist to reach.

## At a glance

| ID    | Change ID                  | Outcome (user can …)                                                       | Prerequisites    | PRD refs                       | Status   |
| ----- | -------------------------- | --------------------------------------------------------------------------- | ---------------- | ------------------------------ | -------- |
| F-01  | minimal-auth               | (foundation) email+password auth, gated routes, per-player ownership boundary | —              | FR-001, FR-002                 | done     |
| F-02  | llm-client                 | (foundation) provider-agnostic LLM client + progress/labelling plumbing     | —                | NFR (progress, <5s parse)      | done     |
| S-01  | manual-match-and-history   | log a match via a structured form and view/filter history by opponent       | F-01             | FR-006, FR-007                 | done     |
| S-02  | ai-game-plan               | select an opponent and get an AI-generated game plan (north star)           | F-01, S-01, F-02 | FR-010, US-02                  | done     |
| S-03  | ai-match-entry             | log a match by typing free text, review the AI preview, confirm or reject   | F-01, S-01, F-02 | FR-003, FR-004, FR-005, US-01  | done     |
| S-04  | edit-delete-match          | edit or delete a saved match record                                         | F-01, S-01       | FR-008, FR-009                 | done     |
| S-05  | i18n-pl-en                 | use the app in Polish or English (browser-detected default, per-user persisted) and get game plans in the chosen language | F-01, S-02       | FR-011, FR-012, FR-013, US-03  | planned  |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme              | Chain                                          | Note                                                                                  |
| ------ | ------------------ | ---------------------------------------------- | ------------------------------------------------------------------------------------- |
| A      | Account & match log | `F-01` → `S-01` → `S-04`                       | The must-have data spine; no AI dependency, so it can't stall on the provider decision. |
| B      | AI value loop      | `F-02` → `S-02` → `S-03`                        | Joins Stream A at `S-01` (both AI slices read saved matches). `F-02` runs parallel to `F-01`/`S-01`. |
| C      | Localization       | `F-01` → `S-02` → `S-05`                        | Cross-cutting: retrofits PL/EN across all already-built UI (`S-01`/`S-03`/`S-04`) and routes new game-plan generation through the active locale. Sequenced after the core loop is proven. |

(Every `F-NN` and `S-NN` appears in exactly one stream — `S-05`'s home is Stream C. `S-02` and `S-03` are siblings on Stream B — both depend on `F-02` + `S-01` and neither blocks the other.)

## Baseline

What's already in place in the codebase as of `2026-05-30` (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** absent — no template engine or static assets; only a plain-text `@RestController` at `/` (`WelcomeController`). The first rendered UI lands in S-01.
- **Backend / API:** partial — Spring Boot 4.0.6 scaffold; only the welcome endpoint exists, no `/api` routes, no AI/LLM SDK wired.
- **Data:** partial — Postgres driver + `spring-boot-starter-jdbc` present, DB connection wired via env vars (`render.yaml`). No JPA/Hibernate, no entities/repositories, no Flyway/Liquibase migrations.
- **Auth:** absent — Spring Security not on the classpath; no security filter chain, account entity, login/register, or password hashing.
- **Deploy / infra:** partial — multi-stage Dockerfile + `render.yaml` (web + Postgres, free tier, Frankfurt, auto-deploy on commit, `/actuator/health`); `run-local.sh`/`stop-local.sh` present. No CI workflow yet.
- **Observability:** partial — Actuator on classpath, only `/health` exposed (details hidden). No logging config, no Sentry/Micrometer/metrics.

## Foundations

### F-01: Minimal auth & ownership boundary

- **Outcome:** (foundation) email+password registration, sign-in, and sign-out are wired via Spring Security; unauthenticated requests to gated routes redirect to sign-in; a per-player ownership boundary is established, along with the persistence + migration tooling needed to enforce it (the account table is the first migration). No match features yet.
- **Change ID:** minimal-auth
- **PRD refs:** FR-001, FR-002, Access Control, NFR (no cross-player data access)
- **Unlocks:** S-01, S-02, S-03, S-04 (every data slice must scope its queries to the signed-in player); the privacy guardrail "one player's history is never visible to another".
- **Prerequisites:** — (baseline: auth absent, persistence partial)
- **Parallel with:** F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The auth + ownership boundary is the hard privacy guardrail. Sequenced first so every later slice inherits a correct per-player boundary instead of retrofitting one — a retrofit is the classic cross-tenant leak. Scope is deliberately minimal: it establishes the boundary and migration tooling only, not the match domain.
- **Status:** done

### F-02: Provider-agnostic LLM client

- **Outcome:** (foundation) a thin, provider-agnostic LLM client is wired behind an abstraction (placeholder `LLM_API_KEY`), with the visible-progress plumbing and the "AI-generated advice" labelling convention that both AI features will reuse. Minimal — just enough for the first AI slice to call.
- **Change ID:** llm-client
- **PRD refs:** NFR (continuous progress feedback; <5s perceived parse), Success Criteria guardrail (AI output labelled as advice)
- **Unlocks:** S-02 (game plan), S-03 (AI text parsing)
- **Prerequisites:** —
- **Parallel with:** F-01, S-01
- **Blockers:** resolved — LLM provider chosen (Google Gemini `gemini-2.5-flash` via OpenAI-compat endpoint; research 2026-06-03 / issue #2). F-02 is implemented; the client is provider-agnostic behind `LLM_API_KEY` / `LLM_BASE_URL`.
- **Unknowns:** —
- **Risk:** Both AI must-haves (FR-003, FR-010) share one client. Building it once as a thin enabler avoids duplicating provider wiring and the progress/labelling plumbing across two slices. Risk if built too eagerly: over-design ahead of a real caller — keep it minimal and let S-02 shape it.
- **Status:** done

## Slices

### S-01: Manual match logging & history

- **Outcome:** user can log a match via a structured form and view their match history, filtered by opponent.
- **Change ID:** manual-match-and-history
- **PRD refs:** FR-006, FR-007
- **Prerequisites:** F-01
- **Parallel with:** F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** First user-visible vertical. Introduces the match entity, exercises the data layer through real create + read, and brings up the first rendered UI. Sequenced before the AI slices so the north star has real match data to reason over without depending on the undecided LLM provider. Under the speed goal, the manual form is the cheapest path to saved data.
- **Status:** done

### S-02: AI game plan for an opponent (north star)

- **Outcome:** user can select an opponent from their history and receive an AI-generated game plan for the next match, clearly labelled as AI-generated advice.
- **Change ID:** ai-game-plan
- **PRD refs:** FR-010, US-02, NFR (continuous progress feedback), guardrail (advice labelling)
- **Prerequisites:** F-01, S-01, F-02
- **Parallel with:** S-03, S-04
- **Blockers:** none — LLM provider resolved (Gemini via OpenAI-compat, see F-02); real game-plan output is verifiable against the wired provider.
- **Unknowns:**
  - Thin data (a single match) may yield generic advice — is that acceptable for launch, or does the slice need a minimum-matches threshold? — Owner: user. Block: no. (PRD resolves this toward labelling-not-suppression; flagged so /10x-plan confirms.)
- **Risk:** The north star — the validation milestone for the core hypothesis (AI advice over a player's own history beats a spreadsheet). Placed as early as its prerequisites allow. Thin-data advice is mitigated by the PRD's advice-labelling guardrail, not by suppressing output.
- **Status:** done

### S-03: AI text-entry match logging

- **Outcome:** user can log a match by typing a natural-language description; the AI returns a structured preview the user can edit, then confirm to save or reject to discard.
- **Change ID:** ai-match-entry
- **PRD refs:** FR-003, FR-004, FR-005, US-01, NFR (<5s perceived parse; progress feedback), guardrail (confirm before save — no silent mis-save)
- **Prerequisites:** F-01, S-01, F-02
- **Parallel with:** S-02, S-04
- **Blockers:** none — LLM provider resolved (Gemini via OpenAI-compat, see F-02).
- **Unknowns:** —
- **Risk:** Delivers the frictionless-recording path the secondary success criteria target (≥75% AI entry, ≥90% parse accuracy). Reuses S-01's match entity and history. The confirm/edit step is the guardrail against silent mis-save. Sequenced after the north star because, under the speed goal, the value hypothesis (game plan) is validated first; AI entry then reduces friction on an already-proven loop.
- **Status:** done

### S-04: Edit & delete a saved match

- **Outcome:** user can edit a saved match record (e.g. fix a score typo) or delete it.
- **Change ID:** edit-delete-match
- **PRD refs:** FR-008, FR-009
- **Prerequisites:** F-01, S-01
- **Parallel with:** S-02, S-03
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Data hygiene on existing records; lowest validation value, so sequenced last under the speed goal. Independent of the AI slices, so a separate agent run can build it in parallel with S-02/S-03.
- **Status:** done

### S-05: Internationalization (Polish & English)

- **Outcome:** the entire interface is available in Polish and English. On first visit the language is auto-detected from the browser (`navigator.language` / `Accept-Language`); a signed-in player can switch language from the UI and the choice is persisted **for the user** (a `locale` column on `users`, so it follows them across devices/sessions, not just the current browser). AI-generated game plans are produced in the player's active interface language — the locale is threaded into the prompt so a Polish UI yields a Polish plan.
- **Change ID:** i18n-pl-en
- **PRD refs:** FR-011, FR-012, FR-013, US-03 (added in PRD v2, 2026-06-14)
- **Prerequisites:** F-01 (per-user persistence + auth to store the locale preference against the account), S-02 (game-plan generation, whose prompt must carry the locale)
- **Parallel with:** — (all core slices already `done`; this is a cross-cutting retrofit over the shipped UI)
- **Blockers:** none — all prerequisites are `done`.
- **Unknowns:**
  - Translation source-of-truth and library: react-i18next with JSON resource bundles is the conventional fit (no i18n lib wired yet) — confirm at `/10x-plan`. Block: no.
  - Game-plan output quality in Polish from Gemini `gemini-2.5-flash` — verify the model honors a Polish-output instruction with no quality regression (`GamePlanPromptBuilder` is the injection point). Owner: user/plan. Block: no.
  - Anonymous-default persistence before sign-in (cookie/localStorage) vs. account-only — leaning localStorage for the pre-auth default, DB column as the authoritative per-user store after login. Block: no.
- **Risk:** A cross-cutting localization retrofit over already-built UI — the cost is breadth (every rendered string + the game-plan prompt), not depth. Sequenced after the core value loop is validated so localization never delayed the north star. Two correctness traps for `/10x-plan` to pin: (1) untranslated strings leaking through (enforce keys, no hard-coded copy), and (2) the game plan silently falling back to English when the UI is Polish — the locale must reach the LLM prompt, not just the frontend. Per-user persistence (not browser-only) is the explicit ask in this slice.
- **Status:** planned

## Backlog Handoff

| Roadmap ID | Change ID                  | Suggested issue title                                        | Ready for `/10x-plan` | Notes |
| ---------- | -------------------------- | ------------------------------------------------------------ | --------------------- | ----- |
| F-01       | minimal-auth               | Wire email+password auth and per-player ownership boundary   | yes                   | Start here. Unblocks every other item. |
| F-02       | llm-client                 | Add provider-agnostic LLM client with progress + advice labelling | yes              | Provider resolved (Gemini via OpenAI-compat); implemented. |
| S-01       | manual-match-and-history   | Manual match logging form + history view with opponent filter | yes                  | Needs F-01 done. |
| S-02       | ai-game-plan               | AI-generated game plan for a selected opponent (north star)  | yes                   | Needs F-01, S-01, F-02 done (all resolved). |
| S-03       | ai-match-entry             | Natural-language match entry with AI-parsed confirm preview  | yes                   | Needs F-01, S-01, F-02 done (all resolved). |
| S-04       | edit-delete-match          | Edit and delete saved match records                          | no                    | Needs F-01, S-01 done. |
| S-05       | i18n-pl-en                 | Polish/English i18n with per-user persisted locale + localized game plans | yes      | Needs F-01, S-02 done (both archived). Cross-cutting retrofit; PRD FRs FR-011..FR-013 added in v2. |

This table is the clean handoff to Jira/Linear or any MCP-backed backlog. One row per `F-NN`/`S-NN`.

## Open Roadmap Questions

2. **~~i18n (S-05) is not in PRD v1 — backfill it?~~ RESOLVED 2026-06-14 (PRD v2):** Added FR-011/FR-012/FR-013 (Localization) and US-03 to the PRD; `prd_version` bumped to 2. Acceptance criteria now exist before `/10x-plan`.

1. **~~Which LLM provider (and integration path) backs the AI features?~~ RESOLVED 2026-06-03 (issue #2):** **Google Gemini** (`gemini-2.5-flash`) via its **OpenAI-compatible endpoint**, behind a **thin direct adapter** (not Spring AI — Boot 4 lacked a GA Spring AI at decision time). Wired in `application.properties` (`llm.base-url` / `llm.model`) and `OpenAiCompatLlmClient`. The OpenAI wire format keeps the provider swappable under `LLM_API_KEY` / `LLM_BASE_URL`. Caveat carried forward: Gemini's free tier trains on prompts (no opt-out) — synthetic data for dev, paid/no-training tier for real user data.

## Parked

- **Mobile-native and mobile-browser support** — Why parked: PRD §Non-Goals (web-only, desktop browsers for MVP).
- **Match sharing / social / leaderboards / head-to-head between accounts** — Why parked: PRD §Non-Goals (all history private to the owning player).
- **Custom statistical or ML scoring engine** — Why parked: PRD §Non-Goals (tactical advice is LLM-generated, not a proprietary algorithm).
- **Third-party / OAuth sign-in** — Why parked: PRD §Access Control marks it optional and not required for MVP; deferred under the speed goal to keep F-01 minimal.

## Done

(Empty on first generation. `/10x-archive` appends entries here — and flips the matching item's `Status` to `done` — when a change whose `Change ID` matches a roadmap item is archived. Do NOT pre-populate.)

- **F-01: (foundation) email+password registration, sign-in, and sign-out are wired via Spring Security; unauthenticated requests to gated routes redirect to sign-in; a per-player ownership boundary is established, along with the persistence + migration tooling needed to enforce it (the account table is the first migration). No match features yet.** — Archived 2026-06-06 → `context/archive/2026-05-30-minimal-auth/`. Lesson: —.
- **F-02: (foundation) a thin, provider-agnostic LLM client is wired behind an abstraction (placeholder `LLM_API_KEY`), with the visible-progress plumbing and the "AI-generated advice" labelling convention that both AI features will reuse. Minimal — just enough for the first AI slice to call.** — Archived 2026-06-06 → `context/archive/2026-06-03-llm-client/`. Lesson: —.
- **S-01: user can log a match via a structured form and view their match history, filtered by opponent.** — Archived 2026-06-06 → `context/archive/2026-05-31-manual-match-and-history/`. Lesson: —.
- **S-02: user can select an opponent from their history and receive an AI-generated game plan for the next match, clearly labelled as AI-generated advice.** — Archived 2026-06-06 → `context/archive/2026-06-04-ai-game-plan/`. Lesson: —.
- **S-03: user can log a match by typing a natural-language description; the AI returns a structured preview the user can edit, then confirm to save or reject to discard.** — Archived 2026-06-06 → `context/archive/2026-06-04-ai-match-entry/`. Lesson: —.
- **S-04: user can edit a saved match record (e.g. fix a score typo) or delete it.** — Archived 2026-06-06 → `context/archive/2026-06-04-edit-delete-match/`. Lesson: —.
