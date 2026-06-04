# AI Game Plan for an Opponent (S-02) Implementation Plan

## Overview

Deliver the project's north star (FR-010 / US-02): a signed-in player selects an opponent from their match history and receives an AI-generated tactical game plan for the next match against that opponent. The plan streams in token-by-token as free-text prose, is grounded strictly in the player's logged matches (set-by-set scores + notes), is generated fresh on every request (no persistence), and is clearly labelled as AI-generated advice. When the player has thin history against the opponent, the plan still generates but carries an explicit low-data caveat.

This slice builds entirely on already-implemented foundations: `minimal-auth` (ownership boundary), `manual-match-and-history` (match domain + SPA), and `llm-client` (provider-agnostic `LlmClient` + advice-labelling conventions). The one genuinely new piece of infrastructure is a **streaming path** through the LLM client, which the synchronous F-02 client does not yet have.

## Current State Analysis

What exists now (verified against the codebase, all prerequisites `implemented`/`impl_reviewed`):

- **LLM seam (`llm/` package).** `LlmClient` interface with `String generate(LlmRequest)` and `<T> T generateStructured(LlmRequest, Class<T>)`. `OpenAiCompatLlmClient` (`@Service`) POSTs to `/chat/completions` via the configured `llmRestClient` bean (`RestClient`, `Authorization: Bearer`, connect+read timeout = `llm.timeout`, default 30s). `LlmRequest` (record: `messages`, `temperature`, `maxTokens`, `timeout`) with `ofUser(String)`; `LlmMessage(LlmRole, String)`; `LlmRole {SYSTEM,USER,ASSISTANT}`. `AiContent<T>` + `AiDisclaimer.TEXT` ("AI-generated advice — not factual analysis. Verify before relying on it.") and the `LlmProgress {SUBMITTED,GENERATING,COMPLETED,FAILED}` vocabulary already exist for reuse. **`LlmException` is already mapped to HTTP 503** by `user/ApiExceptionHandler` (`@RestControllerAdvice`). Default provider Gemini (`gemini-2.5-flash`) via the OpenAI-compat endpoint — config-swappable, no code change.
- **Match domain (`match/` package).** `MatchRepository.findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(Long userId, String opponentName)` already returns a user-scoped, case-insensitive, newest-first list of matches with sets eagerly loaded via `@EntityGraph(attributePaths="sets")` — **exactly** the read this feature needs; no new repository method required. `Match` carries `opponentName`, `matchDate`, `notes`, and `List<MatchSet> sets` (each `setNumber`, `playerScore`, `opponentScore`). `MatchResponse.from(match)` derives `setsWon/setsLost/result`. `MatchRepository.findDistinctOpponentNamesByUserId` already powers the opponent picker.
- **Ownership seam.** `CurrentUser.currentUserId()` → `Long`. Every owned query is scoped through it. `SecurityConfig` ends with `.anyRequest().authenticated()`; only specific paths are `permitAll`, so a new `/api/**` route is **authenticated by default with no SecurityConfig edit**. CSRF is cookie-based and enforced on mutating methods only — a `GET` endpoint sidesteps it.
- **Frontend (`frontend/`, Vite + React 18 + react-router v6 + axios).** Shared `client.ts` axios instance (`withCredentials`, CSRF interceptor on mutating methods). `api/matches.ts` exposes `listOpponents() → string[]` (reused by `HistoryPage` as a `<select>`). Routing: protected routes nest under `<ProtectedRoute>` in `App.tsx`; `useAuth()` for auth state; `NavHeader` takes a `links` prop. Styling: **inline-style objects** (`const s: Record<string, React.CSSProperties>`) against CSS design tokens in `index.css` (`--teal #00c9a7`, `--surface`, `--border`, Barlow Condensed display font, DM Mono labels, `borderRadius: '2px'`). Progress today is a binary `busy` button label ("Saving…"); **no streaming/SSE primitive exists** on either side.
- **Stack.** Spring Boot 4.0.6 on `spring-boot-starter-webmvc` (servlet MVC → `SseEmitter` is the streaming primitive, not WebFlux `Flux`). Tests: JUnit 5 + `MockRestServiceServer` for the LLM client, Testcontainers Postgres 17 + MockMvc for web/integration. `SpaRoutes.CLIENT_ROUTES` lists client routes forwarded to `index.html` and `permitAll` for GET; `/api/**` is deliberately never listed.

What's missing: a streaming call path on the LLM client; any game-plan service/endpoint; any game-plan UI; a `/game-plan` route.

## Desired End State

A signed-in player, from either the History page (a "Generate game plan" button on the opponent-filter toolbar) or a nav link, lands on a `/game-plan` page, selects an opponent (pre-selected when arriving via the History button), and clicks generate. A persistent "AI-generated advice — not factual analysis" banner is shown, progress is visible while the model works, and the tactical plan streams in word-by-word as readable prose grounded in that opponent's logged matches. If the player has fewer than 3 matches against the opponent, a "based on limited history" caveat appears. Player B can never obtain a plan built from Player A's matches. Nothing is persisted; each request regenerates. `./mvnw test` is green (mock-transport streaming test, prompt-builder test, ownership test, SSE controller test); the live smoke test still skips without a key.

### Key Discoveries:

- Reuse, don't rebuild, the opponent read: `MatchRepository.findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc` already scopes by user, is case-insensitive, newest-first, and eager-fetches sets (`match/MatchRepository.java`).
- `LlmException → 503` is already wired (`user/ApiExceptionHandler.java`); the synchronous error path is free. SSE errors after the stream opens cannot reuse it (status already flushed) — they must be surfaced as an in-stream `error` event.
- `EventSource` is GET-only and cannot set headers → the streaming endpoint must be a `GET` (authenticated by session cookie, CSRF-exempt). This is consistent with `SecurityConfig`'s authenticated-by-default `/api/**`.
- The existing LLM client mocks transport with `MockRestServiceServer` bound to a `RestClient.builder()` (`OpenAiCompatLlmClientTests.java`); the streaming unit test mirrors this by responding with a `text/event-stream` body and asserting token parse order.
- Servlet MVC: `RestClient.exchange((req, res) -> ...)` exposes the raw response `InputStream` for incremental reads — the streaming-capable API (contrast `.retrieve()` / `.body()` which buffer).

## What We're NOT Doing

- **No persistence of game plans** — ephemeral, regenerated each request. No new table, no migration, no plan-history UI.
- **No structured/sectioned output** — free-text prose only (chosen). No `generateStructured` for this feature, no JSON-schema record for the plan.
- **No changes to `generateStructured`/`generate` semantics**, no retries/circuit-breaker/caching on the LLM client, no provider change, no renaming `LLM_API_KEY`.
- **No minimum-match gate** — generation is allowed at any match count (FR-010); thin data is handled by a caveat, not by blocking.
- **No squash-rules legality validation, no statistical engine** (PRD §Non-Goals — advice is LLM-generated).
- **No multi-opponent or "plan vs everyone" view** — one opponent per request.
- **No change to `/api/auth/*` or `/api/matches` behavior.**
- **No streaming for S-03** — the streaming method is added now for the game plan; S-03's structured parse remains synchronous and is out of scope here.

## Implementation Approach

Backend-first, mirroring the `manual-match-and-history` slice ordering (enabler → API → SPA):

1. Add a blocking streaming method to the LLM client that POSTs with `stream:true` and invokes a per-token callback as `data:` chunks arrive, terminating on `[DONE]`. This is the only new infrastructure.
2. Build a thin `GamePlanService` that loads the opponent's matches (ownership-scoped, reusing the existing repo query), renders them into a data-grounded prompt (system message constrains the model to the player's logged data; user message carries the formatted match history; a low-data caveat is injected when matches < 3), and drives the streaming client. Expose it through a `GamePlanController` SSE endpoint that emits a `meta` event (disclaimer + match count + low-data flag), then token events, then `done` — or an in-stream `error` event on `LlmException`. The no-matches case throws a normal 404 before the stream opens.
3. Build the `GamePlanPage` and its EventSource client, wire the History-toolbar entry point and routing, using the `frontend-design` skill so the page matches the existing token-based aesthetic. A persistent advice banner guarantees the labelling hard-rule cannot be omitted regardless of stream content.

## Critical Implementation Details

- **Request-thread vs SSE-worker boundary.** `CurrentUser.currentUserId()` reads the thread-local `SecurityContextHolder`, which is **not** propagated to the `SseEmitter` async worker thread — calling it there throws `IllegalStateException`. Likewise, a `@Transactional` method that spans the LLM stream would pin a pooled DB connection for the whole (multi-second) generation. Both are avoided by the `prepare()`/stream split: `service.prepare(opponent)` resolves the user, runs the read-only match load, and builds the `LlmRequest` **on the request thread** (transaction closes when it returns); the async worker receives a ready `GamePlanContext` and only calls `generateStreaming` — no `CurrentUser`, no repository, no transaction. Do not move the user/repo/tx work onto the worker.
- **SSE error semantics.** Once the `SseEmitter` has sent its first event the HTTP status is already `200`; a mid-stream `LlmException` therefore cannot become a `503`. The controller must catch it inside the async task and send an `event: error` (with a generic "AI service is temporarily unavailable" message) before `emitter.complete()`. Only the *pre-stream* failure (opponent has no matches) returns a normal status code via the existing exception handler.
- **Streaming read timeout.** The configured `llmRestClient` read timeout (default 30s) applies per read-block, which is the correct semantic for streaming (it bounds inter-token gaps, not total duration). Keep the per-call `LlmRequest.timeout()` null for the game plan so the bean default governs; do not set a short total budget.
- **EventSource lifecycle.** The browser `EventSource` must be closed on component unmount and before starting a new generation (regenerate/opponent-change), otherwise connections leak and tokens from a stale stream can interleave. The page owns exactly one open `EventSource` at a time.

## Phase 1: LLM streaming capability

### Overview

Add a blocking, callback-based streaming method to the LLM client so a caller receives assistant tokens incrementally. Pure backend enabler — no game-plan logic yet.

### Changes Required:

#### 1. Streaming method on the client interface

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/client/LlmClient.java`

**Intent**: Extend the shared LLM seam with a streaming variant the game-plan service will drive. Blocking call that pushes each assistant token to a consumer as it arrives.

**Contract**: New interface method `void generateStreaming(LlmRequest request, java.util.function.Consumer<String> onToken)`. Throws `LlmException` on transport/provider failure. Blocks until the provider sends `[DONE]`. Existing `generate`/`generateStructured` unchanged.

#### 2. Streaming implementation

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/client/OpenAiCompatLlmClient.java`

**Intent**: Implement `generateStreaming` against the OpenAI-compatible `/chat/completions` endpoint with `stream:true`, reading the `text/event-stream` response incrementally and forwarding each `choices[0].delta.content` fragment to `onToken`.

**Contract**: Build the same request body as `generate` (use `properties.model()`) plus `"stream": true`. Issue the request with `restClient.post()...exchange((req, res) -> ...)` to obtain the raw response `InputStream`; read it line-by-line (e.g. `BufferedReader`), and for each line starting with `data: ` parse the JSON payload and emit `delta.content` when present (skip empty deltas/keep-alives); stop on the `data: [DONE]` sentinel. Translate any `IOException`/provider HTTP error/parse failure into `LlmException` (carry provider status where available, mirroring the synchronous path). Reuse the injected Jackson 3 `ObjectMapper`. A small SSE line/`[DONE]` parser is acceptable inline (no new class required), but make it a **package-private static** method taking the line source + `onToken` consumer so the optional parser-unit test (see Success Criteria) can drive it chunk-by-chunk.

### Success Criteria:

#### Automated Verification:

- Build compiles: `./mvnw -q compile`
- Streaming **parse** test passes: `./mvnw test -Dtest=OpenAiCompatLlmClientTests` — a `MockRestServiceServer` responds with a `text/event-stream` body of several `data: {…delta…}` chunks ending in `data: [DONE]`, and the test asserts `onToken` receives the deltas in order and concatenates to the expected text; a chunk with an empty/absent `delta.content` is ignored. **Note:** `MockRestServiceServer` replaces the request factory and serves the body from an in-memory buffer, so this test proves SSE line/`[DONE]` **parsing and `onToken` ordering only — not real incremental delivery** (the increments come from splitting an already-complete buffer). True incremental streaming over the real `SimpleClientHttpRequestFactory` transport is exercised solely by the gated live smoke below.
- Error path test passes: a provider 5xx (or a malformed chunk) during streaming throws `LlmException`.
- *(Optional, recommended)* Parser-unit test: extract the SSE line/`[DONE]` parsing into a package-private static method taking a `Reader` (or `Iterator<String>` of lines) so a test can feed it chunk-by-chunk and assert `onToken` fires **before** end-of-input — the one cheap way to cover incremental behavior without a live key.
- Full suite green: `./mvnw test`

#### Manual Verification:

- With a real `LLM_API_KEY`, the extended gated live smoke test streams a short completion from Gemini and prints tokens incrementally (skips cleanly when no key is present). **This is the only check that exercises the real streaming transport end-to-end.**

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: Game-plan API (service + SSE endpoint)

### Overview

Add the server-side feature: load the opponent's matches (ownership-scoped), build a data-grounded prompt, and stream the generated plan over SSE with an advice-label envelope and a low-data caveat.

### Changes Required:

#### 1. Game-plan service

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanService.java` (new sub-package `match.gameplan`)

**Intent**: Do **all** security-context, DB, and prompt work on the request thread and hand the controller a ready-to-stream context, so the async SSE worker touches neither `CurrentUser` nor the repository nor a transaction.

**Contract**: Constructor injects `MatchRepository` (or `MatchService`), `CurrentUser`, and `LlmClient`. Split into two responsibilities, deliberately keeping load/stream apart:

- `GamePlanContext prepare(String opponentName)` — runs on the **request thread** in a short `@Transactional(readOnly = true)` block. Resolves `currentUser.currentUserId()` (must happen here — `SecurityContextHolder` is thread-local and does **not** propagate to the SSE worker thread; see Critical Implementation Details), loads matches via `findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(userId, opponentName)`, and **if empty throws `GamePlanUnavailableException`** (new, → mapped to 404, see change 4) so the controller fails before opening the stream. Builds the `LlmRequest` via the prompt builder (change 2) and returns a `GamePlanContext` record `{ LlmRequest request, int matchCount, boolean lowData }` where `lowData = matchCount < LOW_DATA_THRESHOLD` and `LOW_DATA_THRESHOLD = 3`. The transaction (and its pooled DB connection) **closes when `prepare` returns** — it is never held across the LLM stream.
- The streaming drive is just `LlmClient.generateStreaming(context.request(), onToken)` — invoked by the controller on the async worker. It takes the already-built `LlmRequest`; it does **not** call `CurrentUser`, the repository, or open a transaction. (A thin `stream(GamePlanContext, Consumer<String>)` helper on the service is acceptable but must carry no DB/security work.)

#### 2. Prompt builder (data-grounded, low-data aware)

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanPromptBuilder.java` (new)

**Intent**: Turn the opponent's match list into a SYSTEM + USER message pair that constrains the model to the player's logged data and asks for tactical prose only.

**Contract**: A pure function `LlmRequest build(String opponentName, List<Match> matches)`. SYSTEM message: instruct the model that it is a squash coach producing a tactical game plan for the *next* match against this opponent, grounded **strictly** in the supplied history (set-by-set scores, overall results, and the player's notes); it must not invent statistics or give generic conditioning/mental-game filler; output is plain prose advice. USER message: the opponent name plus a deterministic, readable rendering of each match — date, overall result (derived: sets won–lost), the individual set scores (`player–opponent`), and notes when present. When `matches.size() < 3`, append an explicit instruction to acknowledge that the plan is based on limited history and to keep confidence appropriately low. No `temperature`/`maxTokens`/`timeout` overrides (bean defaults govern). Kept side-effect-free so it is unit-testable without a DB or LLM.

#### 3. SSE controller

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanController.java` (new)

**Intent**: Expose the streaming game-plan endpoint, emitting a labelling/meta envelope, then tokens, then completion — and an in-stream error event on LLM failure.

**Contract**: `@RestController`, `GET /api/game-plans/stream` with `@RequestParam String opponent`, returning `org.springframework.web.servlet.mvc.method.annotation.SseEmitter` (produces `text/event-stream`). Flow: (a) **on the request thread**, call `service.prepare(opponent)` — this resolves the user, loads matches, throws `GamePlanUnavailableException` (→ 404) when none, and returns the `GamePlanContext`; doing this here (not on the worker) is what keeps the `SecurityContextHolder` lookup and the read-only transaction on the request thread (see F1 / Critical Implementation Details); (b) create the `SseEmitter` (generous/none timeout consistent with the LLM read timeout); (c) hand the **already-built `GamePlanContext`** to an async worker (`SseEmitter` async send) — the worker does **no** DB/security work. First `emitter.send(SseEmitter.event().name("meta")...)` carrying `AiDisclaimer.TEXT`, `context.matchCount()`, and `context.lowData()`; then drive `generateStreaming(context.request(), token -> emitter.send(event().name("token").data(json{ "t": token })))`; on normal completion `emitter.send(event().name("done"))` then `emitter.complete()`; on `LlmException`, `emitter.send(event().name("error").data(json{ "message": "AI service is temporarily unavailable" }))` then `emitter.complete()`. Tokens are JSON-framed (`{"t":"…"}`) so newlines/whitespace survive SSE framing intact. Authenticated by default (no SecurityConfig change); GET ⇒ no CSRF. The endpoint path is NOT added to `SpaRoutes`.

#### 4. Pre-stream not-found mapping

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/ApiExceptionHandler.java`

**Intent**: Map the new `GamePlanUnavailableException` to a 404 so a request for an opponent with no (owned) matches fails cleanly before any stream opens — this also enforces the ownership boundary (another player's opponent yields no rows → 404, never their data).

**Contract**: Add an `@ExceptionHandler(GamePlanUnavailableException.class)` returning `@ResponseStatus(NOT_FOUND)` `ApiError.of(404, "No match history for that opponent")`. Define `GamePlanUnavailableException extends RuntimeException` in `match.gameplan`. Existing `LlmException → 503` handler is unchanged and still covers any synchronous LLM failure.

### Success Criteria:

#### Automated Verification:

- Build compiles: `./mvnw -q compile`
- Prompt-builder test passes: `./mvnw test -Dtest=GamePlanPromptBuilderTests` — asserts the rendered USER message contains each match's set scores and notes, the SYSTEM message constrains to logged data, and the low-data instruction appears only when `matches.size() < 3`.
- Ownership test passes (`GamePlanApiIntegrationTests`, Testcontainers + MockMvc): user A logs matches vs "Kowalski"; user B requesting `/api/game-plans/stream?opponent=Kowalski` gets `404` (no rows for B) — A's data never reaches B.
- SSE happy-path test passes (MockMvc async dispatch with a mocked/stubbed streaming `LlmClient` that emits a few tokens): the response body contains a `meta` event carrying the disclaimer text + `lowData` flag, the expected `token` events in order, and a terminal `done` event; content type is `text/event-stream`. **Test recipe (no SSE/async precedent exists in this repo — follow exactly):** (1) stub `generateStreaming` so it invokes the `Consumer<String>` **synchronously on the calling thread** for each canned token (e.g. via Mockito `doAnswer` — `invocation.getArgument(1)` is the consumer) — if the stub hands off to another thread, MockMvc's async result never completes and the test hangs; (2) `var result = mockMvc.perform(get(...)).andExpect(request().asyncStarted()).andReturn();` then `mockMvc.perform(asyncDispatch(result))`; (3) assert on `getResponse().getContentAsString()` (the accumulated SSE frames) — **not** `getAsyncResult()` (an `SseEmitter` streams events, it has no single async return value) — and on content type `text/event-stream`.
- SSE error-path test passes: a streaming `LlmClient` whose stub throws `LlmException` (again **synchronously** on the calling thread, inside the `doAnswer`) yields an in-stream `error` event (not a 503) and a completed stream; assert via the same `asyncDispatch` + `getContentAsString()` recipe.
- Full suite green: `./mvnw test`

#### Manual Verification:

- With a real key, `curl -N --cookie <session> 'http://localhost:8080/api/game-plans/stream?opponent=<name>'` streams a `meta` event then incremental tokens then `done`, and the prose visibly references the logged scores/notes.
- Requesting an opponent the signed-in user has no matches against returns `404` with the `ApiError` body.

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 3: Frontend game-plan page

### Overview

Build the user-facing flow: an opponent picker, a generate action, a live-streaming prose result with a persistent AI-advice label and low-data caveat, plus the History-toolbar entry point and routing. Use the `frontend-design` skill so the page matches the established token-based aesthetic.

### Changes Required:

#### 1. Game-plan API client (EventSource)

**File**: `frontend/src/api/gameplans.ts` (new)

**Intent**: Provide a typed helper that opens an SSE stream for a given opponent and surfaces meta/token/done/error events to the page, plus a way to close it.

**Contract**: A function that, given `opponentName` and handlers (`onMeta(disclaimer, lowData, matchCount)`, `onToken(text)`, `onDone()`, `onError()`), constructs `new EventSource('/api/game-plans/stream?opponent=' + encodeURIComponent(opponentName))` (same-origin → session cookie sent automatically; no CSRF/header needed), registers `addEventListener` for the `meta`/`token`/`done`/`error` named events (parsing the JSON payloads, `{"t":…}` for tokens), and returns the `EventSource` (or a `close()` handle) so the caller can abort. **Closing discipline (prevents auto-reconnect):** native `EventSource` auto-reconnects on *any* connection close that isn't an explicit `.close()` — including an abnormal drop with no named event (network blip, server crash, `complete()` racing the client). A reopened GET re-runs a brand-new full LLM generation (cost + possible interleaving). Therefore call `es.close()` in **all** terminal paths: the named `done` handler, the named `error` handler, **and** the native `es.onerror` transport-error handler (which fires on drops with no payload) — the latter must also surface the `onError()` page state. Net: exactly one generation per user action, never an implicit reconnect. Reuse `listOpponents` from `api/matches.ts` for the picker (no new endpoint).

#### 2. Game-plan page

**File**: `frontend/src/pages/GamePlanPage.tsx` (new)

**Intent**: The `/game-plan` page: pick an opponent (pre-selected from `?opponent=`), generate, and watch the labelled plan stream in.

**Contract**: Default-export protected page rendering `NavHeader` + the standard page shell. State: selected opponent (initialized from the `opponent` query param via `useSearchParams`), the accumulating plan text, a `status` (`idle`/`streaming`/`done`/`error`), and meta (`disclaimer`, `lowData`, `matchCount`). Opponent picker reuses `listOpponents()` as a `<select>` mirroring `HistoryPage`. A "Generate game plan" primary CTA (teal, disabled while `streaming`, label swaps to a "Generating…" busy state to satisfy the progress NFR before the first token, then continues as text streams). On generate: close any existing `EventSource`, reset text, open a new stream via the change-1 helper, append tokens as they arrive. A **persistent** advice banner (rendered from the `meta` disclaimer, with a static fallback string so the label can never be absent) sits above the streamed prose; when `lowData` is true, also show a "based on limited history (N match(es))" caveat. Close the `EventSource` on unmount and before any re-generate. Style strictly via inline-style objects + design tokens (`var(--teal)`, `--surface`, `--border`, display/mono fonts, `borderRadius:'2px'`), matching `HistoryPage`/`LogMatchPage`. Build the visual design with the `frontend-design` skill.

#### 3. History-page entry point

**File**: `frontend/src/pages/HistoryPage.tsx`

**Intent**: Let the player jump from the opponent they're viewing straight into a pre-filled game plan.

**Contract**: Add a "Generate game plan" button to the opponent-filter toolbar that navigates to `/game-plan?opponent=<currently-selected opponent>` (via `useNavigate`/`Link`). Enabled only when a specific opponent is selected (not the "All opponents" option). Styled consistently with the existing toolbar controls.

#### 4. Routing & nav

**Files**: `frontend/src/App.tsx`, `frontend/src/components/NavHeader.tsx` (nav links as appropriate)

**Intent**: Register the protected `/game-plan` route and make the page reachable.

**Contract**: Add `<Route path="/game-plan" element={<GamePlanPage />} />` inside the existing `<ProtectedRoute>` group in `App.tsx`. Add `/game-plan` to `SpaRoutes.CLIENT_ROUTES` (`src/main/java/.../SpaRoutes.java`) so a hard refresh / deep link to the route is forwarded to `index.html` and permitted for GET (consistent with `/history`, `/matches/**`). Optionally surface a nav link via the `NavHeader` `links` prop on relevant pages.

### Success Criteria:

#### Automated Verification:

- Frontend builds clean (typecheck + bundle): `./mvnw -q package` (runs `frontend-maven-plugin`), or `cd frontend && npm run build`.
- Full backend suite still green: `./mvnw test`

#### Manual Verification:

- From History, selecting an opponent and clicking "Generate game plan" lands on `/game-plan` with that opponent pre-selected; clicking generate streams a labelled plan in token-by-token.
- The "AI-generated advice — not factual analysis" banner is always visible with the result; for an opponent with < 3 matches the low-data caveat shows.
- Navigating away mid-stream (or regenerating) cleanly closes the previous stream (no duplicate/interleaved text; no console connection leak).
- A simulated LLM failure surfaces a friendly error state, not a frozen UI.
- A deep link / hard refresh to `/game-plan` loads the SPA (SpaRoutes forwarding) rather than 404ing.
- Ownership holds end-to-end: signed in as a different player, the opponent picker shows only that player's opponents.

**Implementation Note**: After this phase and all automated verification passes, pause for final manual confirmation. On completion, set `change.md` to `implemented` and move the GitHub issue to "Done" (per `context/foundation/lessons.md`).

---

## Testing Strategy

### Unit Tests:

- **Streaming client** (`OpenAiCompatLlmClientTests`, extended): SSE body of multiple `data:` chunks → ordered `onToken` calls; empty/keep-alive deltas ignored; `[DONE]` terminates; provider 5xx / malformed chunk → `LlmException`.
- **Prompt builder** (`GamePlanPromptBuilderTests`): set scores + notes appear in the USER message; SYSTEM message constrains to logged data; low-data instruction present only when `matches.size() < 3`; deterministic output for a fixed match list.

### Integration Tests:

- **Game-plan API** (`GamePlanApiIntegrationTests`, Testcontainers + MockMvc, mocked streaming `LlmClient`): happy path emits `meta` (disclaimer + lowData) → ordered `token` events → `done`; error path emits in-stream `error`; no-matches → `404`; **cross-player ownership** → user B cannot obtain a plan from user A's matches (`404`).

### Manual Testing Steps:

1. Log ≥3 matches vs an opponent; from History select that opponent → "Generate game plan" → watch prose stream with the advice banner; confirm it references real scores/notes.
2. Log exactly 1 match vs a second opponent; generate → confirm the low-data caveat appears and the plan still streams.
3. Regenerate / switch opponents mid-stream → confirm the prior stream closes cleanly (no interleaving, no leaked connection in dev tools).
4. Force an LLM failure (e.g. invalid key) → confirm a friendly error state, no freeze.
5. As a second player, confirm the picker only lists that player's opponents and a manual request for another player's opponent returns 404.
6. Hard-refresh `/game-plan` → SPA loads (no 404).

## Performance Considerations

Streaming improves *perceived* performance (first tokens appear quickly) and satisfies the NFR's continuous-progress requirement directly. Generation is on-demand and ephemeral; MVP data volumes are small so the full-history prompt stays modest — if a pathological history ever bloats the prompt, capping to recent matches is a localized follow-up in the prompt builder. **Cost note:** each request is a full, uncapped LLM generation with no caching, so cost scales linearly with requests; the client's strict close-on-all-terminal-paths discipline (Phase 3 change 1) is what prevents a dropped `EventSource` from silently re-running a second generation via auto-reconnect. The per-read timeout (bean default 30s) bounds inter-token gaps; no separate total budget is set so a legitimately long plan is not truncated.

## Migration Notes

None — no schema changes, no new tables, nothing persisted.

## References

- Roadmap slice: `context/foundation/roadmap.md` (S-02, north star)
- PRD: `context/foundation/prd.md` (FR-010, US-02, NFR continuous progress, advice-label guardrail)
- LLM foundation: `context/changes/llm-client/plan.md` (`LlmClient`, `AiContent`/`AiDisclaimer`, `LlmProgress`; SSE explicitly deferred to S-02)
- Match domain: `context/changes/manual-match-and-history/plan.md` (repository query, DTOs, SPA patterns)
- Lessons: `context/foundation/lessons.md` (project-board sync on implement; Docker build-context caveat)
- Key files: `match/MatchRepository.java`, `llm/client/OpenAiCompatLlmClient.java`, `security/CurrentUser.java`, `user/ApiExceptionHandler.java`, `frontend/src/pages/HistoryPage.tsx`, `frontend/src/api/client.ts`, `SpaRoutes.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: LLM streaming capability

#### Automated

- [x] 1.1 Build compiles (`./mvnw -q compile`) — 2470ad1
- [x] 1.2 Streaming **parse** test passes (ordered tokens, empty deltas ignored, `[DONE]` terminates — parsing only, not real incremental delivery) — 2470ad1
- [x] 1.3 Streaming error-path test passes (provider 5xx / malformed chunk → `LlmException`) — 2470ad1
- [x] 1.4 Full suite green (`./mvnw test`) — 2470ad1
- [x] 1.6 (optional) Parser-unit test drives the package-private SSE parser chunk-by-chunk; `onToken` fires before end-of-input — 2470ad1

#### Manual

- [x] 1.5 Gated live smoke streams a short Gemini completion incrementally (skips without key)

### Phase 2: Game-plan API (service + SSE endpoint)

#### Automated

- [x] 2.1 Build compiles (`./mvnw -q compile`)
- [x] 2.2 Prompt-builder test passes (scores + notes present; data-grounded SYSTEM; low-data only when < 3)
- [x] 2.3 Ownership test passes (user B → 404 for user A's opponent)
- [x] 2.4 SSE happy-path test passes (`meta` + ordered `token` events + `done`, `text/event-stream`)
- [x] 2.5 SSE error-path test passes (in-stream `error` event, not a 503)
- [x] 2.6 Full suite green (`./mvnw test`)

#### Manual

- [x] 2.7 `curl -N` streams `meta` → tokens → `done`; prose references logged scores/notes
- [x] 2.8 Opponent with no owned matches returns 404 with `ApiError` body

### Phase 3: Frontend game-plan page

#### Automated

- [ ] 3.1 Frontend builds clean (typecheck + bundle via `./mvnw -q package` or `npm run build`)
- [ ] 3.2 Full backend suite still green (`./mvnw test`)

#### Manual

- [ ] 3.3 History → opponent → "Generate game plan" lands on `/game-plan` pre-selected; plan streams token-by-token
- [ ] 3.4 Advice banner always shown; low-data caveat shows for < 3 matches
- [ ] 3.5 Regenerate / navigate-away mid-stream closes the prior stream cleanly (no interleave/leak)
- [ ] 3.6 Simulated LLM failure shows a friendly error state, no freeze
- [ ] 3.7 Deep link / hard refresh to `/game-plan` loads the SPA (SpaRoutes forwarding)
- [ ] 3.8 Ownership holds end-to-end (picker shows only the signed-in player's opponents)
