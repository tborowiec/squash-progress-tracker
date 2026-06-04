# AI Match Entry — Plan Brief

> Full plan: `context/changes/ai-match-entry/plan.md`

## What & Why

Let a signed-in player log a match by typing a natural-language description ("beat Kowalski 3:1 (11:5, 6:11, 11:2, 11:1) on May 5th, struggled in the second set") instead of filling a form. The AI parses it into a structured preview the player reviews, edits if needed, and confirms. This is the last piece of the core loop and the product's central friction-reducer — recording is the fuel for everything else (FR-003/004/005, US-01).

## Starting Point

Manual match entry, history, and AI game plans already work. The LLM client already supports strict structured JSON output (`generateStructured`), the match save path is owner-scoped and complete, and the manual form at `/matches/new` already validates and saves. What's missing is the parse endpoint and an AI entry UI.

## Desired End State

On the match-entry page the player can toggle to "AI" mode, type free text, click Parse, and within ~5s see the form below pre-filled (opponent, ISO date, per-set scores, notes). Every field stays editable; saving uses the existing create path. A warning banner flags fields the AI left empty, and a disclaimer reminds the player to review before saving.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Transport | Synchronous POST + spinner | Matches the existing `generateStructured` call and the ≤5s NFR; streaming JSON into a form adds cost for no gain. | Plan |
| Preview surface | Reuse the existing match form | The form already validates/saves and lets the player edit any field — zero duplicate UI. | Plan |
| Date handling | Inject today; LLM returns ISO string, defaults to today if unstated | Schema can't carry `LocalDate`; injecting today resolves relative dates and matches the form's default. | Plan |
| Partial parse | Best-effort fill + warning banner | The confirm step is the safety net; flag empty key fields rather than build LLM confidence scoring. | Plan |
| Mode UX | Explicit AI / Manual toggle | Cleaner single-purpose view; parsed result transitions into the form for confirmation. | Plan |
| Opponent names | Pass existing names to the LLM as hints | Keeps history/game-plan grouping clean with low effort and no fuzzy-match tuning. | Plan |
| Testing | Mocked unit/slice tests + env-guarded live test | Matches project conventions; live test auto-skips in CI like `LlmClientLiveSmokeTest`. | Plan |

## Scope

**In scope:** parse endpoint (`POST /api/matches/parse`), prompt builder with date + opponent hints, parse-result DTO, AI/Manual toggle + AI text view, result→form mapping, warning banner, disclaimer, tests.

**Out of scope:** streaming parse, separate preview component, server-side fuzzy opponent matching, LLM confidence field, new routes/security changes, schema/migration changes, multi-match parsing.

## Architecture / Approach

Frontend AI view POSTs raw text to `/api/matches/parse`. The endpoint (`MatchController.parse` → `MatchParseService`) looks up the player's known opponents, builds a prompt (today's date + opponent hints + raw text via `MatchParsePromptBuilder`), and calls `llmClient.generateStructured(request, MatchParseResult.class)`. The structured result maps into the existing form state; the player confirms via the existing `POST /api/matches` save path. `LlmException` flows to the existing handler (503).

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend parse endpoint | `POST /api/matches/parse` returning a structured preview, with tests | LLM parse quality / date resolution — mitigated by the live test + the review step |
| 2. Frontend AI entry | AI/Manual toggle, text→form pre-fill, warning banner, disclaimer | Mapping parsed result cleanly into existing form state without disrupting manual mode |

**Prerequisites:** a real `LLM_API_KEY` for manual/live verification (mocked tests need none).
**Estimated effort:** ~1–2 sessions across 2 phases.

## Open Risks & Assumptions

- `JsonSchemaFactory` can't derive `LocalDate` and forces all fields required — the parse DTO uses a `String` date and tolerates empty strings; this is a hard constraint, not a preference.
- LLM relative-date resolution may occasionally be wrong — mitigated because the player reviews the date before saving.
- Opponent snap-to-existing relies on the LLM honoring the hint list; the user can always correct the field.

## Success Criteria (Summary)

- A player parses a natural-language match, reviews the pre-filled form, and saves it to history.
- Empty key fields are flagged; the LLM never silently mis-saves (review-before-confirm enforced).
- LLM failures degrade gracefully to a clear error with manual entry still usable.
