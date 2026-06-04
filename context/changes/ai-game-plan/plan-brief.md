# AI Game Plan for an Opponent (S-02) — Plan Brief

> Full plan: `context/changes/ai-game-plan/plan.md`

## What & Why

The project's **north star** (FR-010 / US-02): a signed-in player selects an opponent from their match history and gets an AI-generated tactical game plan for the next match against them. It validates the core product hypothesis — that AI advice over a player's own history beats a spreadsheet. The plan streams in as prose, is grounded strictly in the player's logged matches, and is clearly labelled as AI advice.

## Starting Point

All prerequisites are implemented: `minimal-auth` (ownership boundary via `CurrentUser.currentUserId()`), `manual-match-and-history` (match domain + React SPA), and `llm-client` (synchronous, provider-agnostic `LlmClient` + `AiDisclaimer`/`AiContent` labelling conventions; `LlmException → 503` already wired). The repository already has the exact read needed — `findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc` (user-scoped, sets eager-loaded). What's missing: a **streaming** path on the LLM client (F-02 deferred SSE to this slice), any game-plan service/endpoint/UI, and a `/game-plan` route.

## Desired End State

From a "Generate game plan" button on the History toolbar (or a nav link), the player reaches a `/game-plan` page, picks an opponent (pre-selected when arriving from History), and watches a tactical plan stream in token-by-token as readable prose grounded in that opponent's logged scores and notes — under a persistent "AI-generated advice" banner, with a low-data caveat when history is thin. Nothing is persisted; each request regenerates. Player B can never get a plan from Player A's data. `./mvnw test` green; live smoke skips without a key.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Output format | Free-text prose (`generate`-style, streamed) | Simplest, reads naturally; no schema needed | Plan |
| Progress feedback | SSE token streaming (new client method + `SseEmitter`) | Best perceived performance for prose; satisfies the continuous-progress NFR richly | Plan |
| Thin data | Generate anyway + explicit low-data caveat (< 3 matches) | Honors FR-010 "request at any time" while staying honest about confidence | Plan |
| Entry point | History-toolbar button → dedicated `/game-plan?opponent=` page | Contextual entry with a roomy result surface | Plan |
| Persistence | Ephemeral — always generate fresh | No table/migration/staleness; pairs naturally with streaming | Plan |
| Prompt data | Full history: set-by-set scores + notes | Richest tactical signal — exactly what the PRD says the recommendation consumes | Plan |
| Plan emphasis | Tactics grounded strictly in the logged data | Anchors advice to real history → less hallucination, more trust | Plan |
| Endpoint shape | `GET /api/game-plans/stream` (SSE) | `EventSource` is GET-only + header-less → session-cookie auth, CSRF-exempt | Plan |

## Scope

**In scope:** a `generateStreaming(LlmRequest, Consumer<String>)` method on `LlmClient` + `OpenAiCompatLlmClient` (`stream:true`, `data:` chunk parsing); `GamePlanService` + `GamePlanPromptBuilder` (data-grounded prompt, low-data caveat) + `GamePlanController` SSE endpoint (`meta` → `token` → `done`/`error`); `GamePlanUnavailableException → 404`; a `GamePlanPage` with opponent picker, streamed prose, persistent advice banner; History-toolbar entry point; route + `SpaRoutes` + nav wiring; mock-transport, prompt-builder, ownership, and SSE tests.

**Out of scope:** persistence of plans; structured/sectioned output; minimum-match gating; retries/caching/provider change on the LLM client; squash-rules validation; multi-opponent views; S-03's parse path; any change to `/api/auth/*` or `/api/matches`.

## Architecture / Approach

Backend-first (enabler → API → SPA), mirroring `manual-match-and-history`. New streaming method posts `stream:true` and reads the response `InputStream` via `RestClient.exchange`, pushing each `delta.content` to a consumer. `GamePlanService` loads ownership-scoped matches (reusing the existing repo query), builds a SYSTEM+USER prompt constrained to the logged data, and drives the stream. `GamePlanController` returns an `SseEmitter` emitting a `meta` envelope (disclaimer + match count + low-data flag), then JSON-framed token events, then `done` — or an in-stream `error` event on `LlmException` (status is already 200 once streaming opens). The frontend opens a same-origin `EventSource`, appends tokens, and renders a static advice banner that cannot be omitted.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. LLM streaming capability | `generateStreaming` on the client; SSE chunk parsing; mock + live tests | SSE chunk parsing / per-read-timeout semantics |
| 2. Game-plan API | Service + prompt builder + `SseEmitter` endpoint; ownership + 404; tests | In-stream error semantics (can't 503 after 200); async MockMvc SSE test |
| 3. Frontend game-plan page | `/game-plan` page, EventSource client, History entry point, routing | EventSource lifecycle (close on unmount/regenerate); readable streamed prose |

**Prerequisites:** F-01, S-01, F-02 — all implemented. A real `LLM_API_KEY` is needed only for the live smoke + manual verification. Node toolchain for the frontend build.
**Estimated effort:** ~2–3 focused sessions across 3 phases; the streaming path is the main new surface.

## Open Risks & Assumptions

- Streaming is the one piece of genuinely new infrastructure; the LLM client had no streaming method and F-02 deferred SSE here. Phase 1 isolates it behind mock-transport tests + a gated live smoke.
- Mid-stream LLM failures cannot become a 503 (the 200 has flushed) — surfaced as an in-stream `error` event instead; only the pre-stream no-matches case returns a normal 404.
- `EventSource` is GET-only and header-less, so the endpoint is a CSRF-exempt session-cookie GET; the page must close the source on unmount/regenerate to avoid leaks/interleaving.
- Free-text opponent names key the request (no opponent entity); consistent with S-01's case-insensitive grouping.
- Full-history prompts are fine at MVP data volumes; capping to recent matches is a localized follow-up if a history ever bloats the prompt.

## Success Criteria (Summary)

- A player picks an opponent and watches a labelled tactical plan stream in token-by-token, visibly grounded in their logged scores/notes; a thin history still produces a plan with a low-data caveat.
- One player's matches never inform another player's plan (verified by an automated ownership test returning 404 **and** manually).
- `./mvnw test` green (streaming, prompt-builder, ownership, SSE happy/error tests); `./mvnw package` bundles the new page; live smoke skips without a key.
