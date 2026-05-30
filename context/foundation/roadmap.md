---
project: "Squash Progress Tracker"
version: 1
status: draft
created: 2026-05-30
updated: 2026-05-30
prd_version: 1
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
| F-01  | minimal-auth               | (foundation) email+password auth, gated routes, per-player ownership boundary | —              | FR-001, FR-002                 | ready    |
| F-02  | llm-client                 | (foundation) provider-agnostic LLM client + progress/labelling plumbing     | —                | NFR (progress, <5s parse)      | proposed |
| S-01  | manual-match-and-history   | log a match via a structured form and view/filter history by opponent       | F-01             | FR-006, FR-007                 | proposed |
| S-02  | ai-game-plan               | select an opponent and get an AI-generated game plan (north star)           | F-01, S-01, F-02 | FR-010, US-02                  | proposed |
| S-03  | ai-match-entry             | log a match by typing free text, review the AI preview, confirm or reject   | F-01, S-01, F-02 | FR-003, FR-004, FR-005, US-01  | proposed |
| S-04  | edit-delete-match          | edit or delete a saved match record                                         | F-01, S-01       | FR-008, FR-009                 | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme              | Chain                                          | Note                                                                                  |
| ------ | ------------------ | ---------------------------------------------- | ------------------------------------------------------------------------------------- |
| A      | Account & match log | `F-01` → `S-01` → `S-04`                       | The must-have data spine; no AI dependency, so it can't stall on the provider decision. |
| B      | AI value loop      | `F-02` → `S-02` → `S-03`                        | Joins Stream A at `S-01` (both AI slices read saved matches). `F-02` runs parallel to `F-01`/`S-01`. |

(Every `F-NN` and `S-NN` appears in exactly one stream. `S-02` and `S-03` are siblings on Stream B — both depend on `F-02` + `S-01` and neither blocks the other.)

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
- **Status:** ready

### F-02: Provider-agnostic LLM client

- **Outcome:** (foundation) a thin, provider-agnostic LLM client is wired behind an abstraction (placeholder `LLM_API_KEY`), with the visible-progress plumbing and the "AI-generated advice" labelling convention that both AI features will reuse. Minimal — just enough for the first AI slice to call.
- **Change ID:** llm-client
- **PRD refs:** NFR (continuous progress feedback; <5s perceived parse), Success Criteria guardrail (AI output labelled as advice)
- **Unlocks:** S-02 (game plan), S-03 (AI text parsing)
- **Prerequisites:** —
- **Parallel with:** F-01, S-01
- **Blockers:** LLM provider + API key not yet chosen — stakeholder decision pending (see Open Roadmap Question 1; user leaning Gemini). Planning proceeds provider-agnostically; the provider must be locked before this foundation is implemented and verified.
- **Unknowns:** —
- **Risk:** Both AI must-haves (FR-003, FR-010) share one client. Building it once as a thin enabler avoids duplicating provider wiring and the progress/labelling plumbing across two slices. Risk if built too eagerly: over-design ahead of a real caller — keep it minimal and let S-02 shape it.
- **Status:** proposed

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
- **Status:** proposed

### S-02: AI game plan for an opponent (north star)

- **Outcome:** user can select an opponent from their history and receive an AI-generated game plan for the next match, clearly labelled as AI-generated advice.
- **Change ID:** ai-game-plan
- **PRD refs:** FR-010, US-02, NFR (continuous progress feedback), guardrail (advice labelling)
- **Prerequisites:** F-01, S-01, F-02
- **Parallel with:** S-03, S-04
- **Blockers:** LLM provider not yet chosen (see Open Roadmap Question 1) — the abstraction can be planned now, but verifying real game-plan output needs the chosen provider/key.
- **Unknowns:**
  - Thin data (a single match) may yield generic advice — is that acceptable for launch, or does the slice need a minimum-matches threshold? — Owner: user. Block: no. (PRD resolves this toward labelling-not-suppression; flagged so /10x-plan confirms.)
- **Risk:** The north star — the validation milestone for the core hypothesis (AI advice over a player's own history beats a spreadsheet). Placed as early as its prerequisites allow. Thin-data advice is mitigated by the PRD's advice-labelling guardrail, not by suppressing output.
- **Status:** proposed

### S-03: AI text-entry match logging

- **Outcome:** user can log a match by typing a natural-language description; the AI returns a structured preview the user can edit, then confirm to save or reject to discard.
- **Change ID:** ai-match-entry
- **PRD refs:** FR-003, FR-004, FR-005, US-01, NFR (<5s perceived parse; progress feedback), guardrail (confirm before save — no silent mis-save)
- **Prerequisites:** F-01, S-01, F-02
- **Parallel with:** S-02, S-04
- **Blockers:** LLM provider not yet chosen (see Open Roadmap Question 1).
- **Unknowns:** —
- **Risk:** Delivers the frictionless-recording path the secondary success criteria target (≥75% AI entry, ≥90% parse accuracy). Reuses S-01's match entity and history. The confirm/edit step is the guardrail against silent mis-save. Sequenced after the north star because, under the speed goal, the value hypothesis (game plan) is validated first; AI entry then reduces friction on an already-proven loop.
- **Status:** proposed

### S-04: Edit & delete a saved match

- **Outcome:** user can edit a saved match record (e.g. fix a score typo) or delete it.
- **Change ID:** edit-delete-match
- **PRD refs:** FR-008, FR-009
- **Prerequisites:** F-01, S-01
- **Parallel with:** S-02, S-03
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Data hygiene on existing records; lowest validation value, so sequenced last under the speed goal. Independent of the AI slices, so a separate agent run can build it in parallel with S-02/S-03.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                  | Suggested issue title                                        | Ready for `/10x-plan` | Notes |
| ---------- | -------------------------- | ------------------------------------------------------------ | --------------------- | ----- |
| F-01       | minimal-auth               | Wire email+password auth and per-player ownership boundary   | yes                   | Start here. Unblocks every other item. |
| F-02       | llm-client                 | Add provider-agnostic LLM client with progress + advice labelling | no               | Plannable provider-agnostically; resolve Open Roadmap Q1 (provider) before implementing. |
| S-01       | manual-match-and-history   | Manual match logging form + history view with opponent filter | no                   | Needs F-01 done. |
| S-02       | ai-game-plan               | AI-generated game plan for a selected opponent (north star)  | no                    | Needs F-01, S-01, F-02 + provider chosen. |
| S-03       | ai-match-entry             | Natural-language match entry with AI-parsed confirm preview  | no                    | Needs F-01, S-01, F-02 + provider chosen. |
| S-04       | edit-delete-match          | Edit and delete saved match records                          | no                    | Needs F-01, S-01 done. |

This table is the clean handoff to Jira/Linear or any MCP-backed backlog. One row per `F-NN`/`S-NN`.

## Open Roadmap Questions

1. **Which LLM provider (and integration path) backs the AI features — Anthropic, Google Gemini, or OpenAI; Spring AI vs a direct vendor Java SDK?** — Owner: user (leaning Gemini per prior notes). Block: `F-02`, and transitively `S-02` (north star) and `S-03`. The client abstraction can be planned provider-agnostically, but the provider and key must be locked before F-02 is implemented and the AI slices can be verified. This is the single highest-leverage decision on the roadmap: resolving it clears the path to the north star. (`infrastructure.md` keeps everything provider-agnostic under `LLM_API_KEY` until then.)

## Parked

- **Mobile-native and mobile-browser support** — Why parked: PRD §Non-Goals (web-only, desktop browsers for MVP).
- **Match sharing / social / leaderboards / head-to-head between accounts** — Why parked: PRD §Non-Goals (all history private to the owning player).
- **Custom statistical or ML scoring engine** — Why parked: PRD §Non-Goals (tactical advice is LLM-generated, not a proprietary algorithm).
- **Third-party / OAuth sign-in** — Why parked: PRD §Access Control marks it optional and not required for MVP; deferred under the speed goal to keep F-01 minimal.

## Done

(Empty on first generation. `/10x-archive` appends entries here — and flips the matching item's `Status` to `done` — when a change whose `Change ID` matches a roadmap item is archived. Do NOT pre-populate.)
