---
date: 2026-06-06T00:00:00Z
researcher: Tomasz Borowiec
git_commit: 6052513ee3a78ec7c9f3460ac78d533509e91f21
branch: main
repository: squash-progress-tracker
topic: "AI failure-path tests — transient provider errors surface cleanly (Phase 2)"
tags: [research, codebase, llm, error-handling, failure-path, gemini, sse]
status: complete
last_updated: 2026-06-06
last_updated_by: Tomasz Borowiec
---

# Research: AI failure-path tests — transient provider errors surface cleanly (Phase 2)

**Date**: 2026-06-06T00:00:00Z
**Researcher**: Tomasz Borowiec
**Git Commit**: 6052513ee3a78ec7c9f3460ac78d533509e91f21 (local; not yet pushed)
**Branch**: main
**Repository**: squash-progress-tracker

## Research Question

Phase 2 of `context/foundation/test-plan.md` (§3) covers **Risk #2 — Transient AI
error surfaced as a dead-end**: a Gemini `503` ("overloaded") shown identically to a
permanent failure, so a player abandons an action that would have worked. The test
plan's §2 Risk Response Guidance requires research to ground, in the live code:

1. How the client distinguishes **transient vs permanent** failures today.
2. How the **`30s` client timeout** relates to the **`<5s` parse NFR**.
3. How the **UI/caller consumes** the failure.
4. Where **AI advice-labelling** holds (so the "advice-labelling holds" assertion has a target).

Scope: backend unit + integration only, reusing the existing suite — **no new infra**,
**no retry-with-backoff** (that is the out-of-scope `transient-llm-retry` feature, §7).

## Summary

**The "transient vs permanent" distinction the phase wants to prove does not exist in
the code today.** Every LLM failure — a provider `503`, a provider `400`, a socket
timeout, an empty/malformed body — collapses to **one** generic signal per path:

- **Parse path** (`POST /api/matches/parse`): HTTP **`503`** + body
  `{"status":503,"message":"AI service is temporarily unavailable","fieldErrors":null}`
  for *all* `LlmException` variants. The provider's real status (`LlmException.providerStatus`)
  is captured and **logged but never branched on** — no status differentiation, no
  error code, no `Retry-After`.
- **Game-plan path** (`GET /api/game-plans/stream`): the SSE stream has **already
  returned HTTP `200`** by the time the LLM is called on a virtual thread, so an LLM
  failure surfaces as an **in-stream `event: error`** frame
  (`{"message":"AI service is temporarily unavailable"}`) followed by a clean
  `emitter.complete()` — *not* a `503`.

This is the **oracle problem** flagged in `test-plan.md:70`: we **cannot** assert a
retryable signal because none is emitted. What Phase 2 *can* prove is the weaker,
true-today contract:

- **No fake success** — a `503`/timeout never returns a `200` parse result or a
  successful-looking game plan; it deterministically becomes the error signal above.
- **No infinite spin** — failure is *bounded* (the client wraps the error and returns;
  the SSE stream `complete()`s) rather than hanging. The only bound that actually fires
  is the client's **`30s`** read/connect timeout; **the `<5s` parse NFR is enforced
  nowhere in code**.
- **Advice-labelling holds** — the game-plan disclaimer (`event: meta`) is sent
  **before** the LLM stream starts, so even a mid-stream LLM failure leaves the label intact.

The harness is in place: `OpenAiCompatLlmClientTests` already uses Spring
`MockRestServiceServer` to enqueue `5xx`/`4xx` and `SocketTimeoutException`, and the API
integration tests already mock `@MockitoBean LlmClient` to throw `LlmException`. The
gaps to fill are an **explicit `503`** case, a **synchronous parse failure-path
integration test**, and documenting the absent transient/permanent distinction as the
finding (rather than asserting current behavior is "correct").

## Detailed Findings

### A. The LLM client transport & error mapping

**HTTP client:** Spring `RestClient` backed by `SimpleClientHttpRequestFactory` (JDK
`HttpURLConnection` — *not* OkHttp/WebClient). Bean built in
`src/main/java/org/borowiec/squashprogresstracker/llm/client/LlmClientConfig.java:15-28`.

**Timeout:** single value `llm.timeout` applied to **both** connect and read on the
shared bean (`LlmClientConfig.java:18-20`). Default **`30s`** from
`application.properties:20` (`llm.timeout=${LLM_TIMEOUT:30s}`). A per-request override
path exists — `OpenAiCompatLlmClient.clientWithTimeout(Duration)`
(`OpenAiCompatLlmClient.java:167-172`) sets read = `LlmRequest.timeout()` while keeping
connect = global — but the parse path passes `timeout = null`
(`MatchParsePromptBuilder.java:27`), so parse uses the shared `30s`.

**Error mapping (sync `generate`/`generateStructured`):**
- HTTP `4xx`/`5xx` → caught as `RestClientResponseException`
  (`OpenAiCompatLlmClient.java:100-102`):
  `throw new LlmException("Provider error: " + e.getStatusCode(), e, e.getStatusCode().value());`
  → a `503` yields message `"Provider error: 503 SERVICE_UNAVAILABLE"`, `providerStatus = 503`.
- Timeout / connection / other I/O → the JDK `SocketTimeoutException` is wrapped by
  Spring in `ResourceAccessException`, which does **not** match the
  `RestClientResponseException` catch and falls through to the generic catch
  (`OpenAiCompatLlmClient.java:103-105`): `LlmException("LLM call failed", e)` with
  **`providerStatus = null`**. A timeout is therefore indistinguishable from a DNS
  failure or a deserialize error.
- Empty/malformed body on a `200` → `LlmException("Empty or missing choices in LLM response")`
  (`:95`) or `"Missing content in LLM response choices"` (`:111`); structured deserialize
  failure → `"Failed to deserialize structured LLM response"` (`:58`). All `providerStatus = null`.

**Streaming (`generateStreaming`):** uses `.exchange(...)` and inspects status manually —
`if (res.getStatusCode().isError()) throw new LlmException("Provider error: …", null, status)`
(`OpenAiCompatLlmClient.java:121-127`).

**`LlmException` structure** (`llm/client/LlmException.java`, whole file): unchecked
`RuntimeException`; the **only** extra field is `Integer providerStatus` (nullable,
accessor `providerStatus()` at `:20-22`). **No `retryable` flag, no category enum.**
Three constructors (message / message+cause / message+cause+status).

**Anti-pattern check ("status 200 ⇒ it worked"):** *partially* avoided. The sync path
never reads `getStatusCode()` itself (it relies on `retrieve()` throwing on error
status) but it *does* validate the body shape — a `200` with empty `choices` or missing
`content` is rejected (`:90-97`, `:108-114`). What it does **not** detect is a provider
returning `200` with an OpenAI-style `{"error":{…}}` payload — there is no inspection of
an `error` field, so such a body fails only incidentally as "Empty or missing choices."

**`LlmClient` interface** (`llm/client/LlmClient.java`): `String generate(LlmRequest)`,
`<T> T generateStructured(LlmRequest, Class<T>)`, `void generateStreaming(LlmRequest,
Consumer<String>)`. All exceptions unchecked. `LlmProgress` (`llm/LlmProgress.java`) is a
label-only enum (F-02 ships vocabulary, emits nothing) — **not** a failure-path hook.

### B. Error propagation up to the HTTP response

**Global handler:** `@RestControllerAdvice ApiExceptionHandler`
(`src/main/java/org/borowiec/squashprogresstracker/user/ApiExceptionHandler.java`):
- `handleLlmException` (`:24-29`): `@ResponseStatus(SERVICE_UNAVAILABLE)`, logs
  `providerStatus`, returns `ApiError.of(503, "AI service is temporarily unavailable")`.
- `handleGamePlanUnavailable` (`:31-35`): `404` + `"No match history for that opponent"`.
- `MethodArgumentNotValidException` → `400` (`:43-52`) — the pre-LLM validation path
  (empty/blank `text`), separate from any AI failure.

`ApiError` shape (`user/dto/ApiError.java:5-9`): `{ status, message, fieldErrors }` —
**no** error-code or `retryable` field.

**Parse path:** `MatchController.parse` (`match/MatchController.java:30-33`,
`@PostMapping("/api/matches/parse")`) → `MatchParseService.parse`
(`match/MatchParseService.java:28-32`) → `llmClient.generateStructured(...)`. Neither
the controller nor the service catches — the `LlmException` propagates to
`handleLlmException` → uniform **`503`**. A provider `400` becomes a client-facing `503`.

**Game-plan path:** `GamePlanController.stream`
(`match/gameplan/GamePlanController.java:25-58`, `@GetMapping("/api/game-plans/stream")`,
returns `SseEmitter`). `service.prepare(opponent)` runs **synchronously on the request
thread** (`:28`) and can throw `GamePlanUnavailableException` ("no match history",
`GamePlanService.java:39-41`, *not* an LLM failure) which reaches the advice → **`404`**.
The LLM streaming runs on a **virtual thread** (`:31-55`); by then the `200` + SSE
headers are already sent, so an `LlmException` is caught *inside* the controller
(`:50-51`) and `sendErrorAndComplete(emitter)` (`:60-68`) emits
`event: error` with `{"message":"AI service is temporarily unavailable"}` then
`emitter.complete()`. Non-LLM exceptions → `emitter.completeWithError(e)` (`:52-53`).

**Transient-vs-permanent distinction: ABSENT (key finding).** No `Retry-After` header
anywhere; `grep` for `retry|retryable|transient|backoff` over `src/main/java` returns
nothing. `LlmException.providerStatus` is logged (`ApiExceptionHandler.java:27`) but
never read into a status or body. A transient `503` and a permanent provider `400` are
indistinguishable to the caller.

**`<5s` NFR vs `30s` timeout:** the **only** timeout in the system is the client's `30s`
read/connect. There is **no** service- or controller-layer deadline — `MatchParseService`
and `MatchController.parse` have no `@Transactional(timeout=…)`, no
`CompletableFuture.orTimeout`. The `<5s` parse NFR is **not enforced in code**; a
slow-but-eventually-responding provider can block up to `30s` and still succeed.

### C. AI advice-labelling

Disclaimer text: `AiDisclaimer.TEXT = "AI-generated advice — not factual analysis.
Verify before relying on it."` (`llm/AiDisclaimer.java:5`).

**Single live attachment point:** `GamePlanController.java:33-36`, sent as the **first**
SSE event (`event: meta`, `MetaPayload(disclaimer, matchCount, lowData)` — record at
`:70`) **before** any token streams (line 33 precedes `service.stream` at line 38).
**Test consequence:** the disclaimer survives a mid-stream LLM failure because it was
already sent — a strong "advice-labelling holds even on failure" assertion.

`AiContent<T>` (`llm/AiContent.java:7-11`) is a generic disclaimer-carrying envelope but
is **used nowhere** in `src/main/java`. The parse path's `MatchParseResult` carries **no**
disclaimer — AI-labelling today applies only to the game-plan SSE `meta` event, not to parse.

### D. Existing test harness (reuse targets — no new infra)

**Dependencies available** (`pom.xml:74-98`): `spring-boot-starter-webmvc-test`
(brings Spring `MockRestServiceServer`, MockMvc, JUnit 5, AssertJ, Mockito),
`spring-security-test`, Testcontainers (Postgres). **No MockWebServer, no WireMock.**

**Transport stubbing** — `OpenAiCompatLlmClientTests` binds `MockRestServiceServer` to a
`RestClient.Builder` (`OpenAiCompatLlmClientTests.java:36-42`); no real socket. It already:
- enqueues `500` via `withServerError()` and `401` via `withStatus(UNAUTHORIZED)`,
  asserting `providerStatus()` (`:96-112`). **A `503` reuses the same idiom:
  `withStatus(HttpStatus.SERVICE_UNAVAILABLE)` → `providerStatus()==503`.**
- simulates a timeout's *error-mapping effect* via
  `withException(new SocketTimeoutException("timed out"))` (`:133-141`).

**Limitation:** `MockRestServiceServer` opens no socket and has no `setBodyDelay`, so it
**cannot trip the real `30s` wall-clock read timeout** in `LlmClientConfig.java:17-20`.
It can only assert that a `SocketTimeoutException` *maps to* `LlmException`. Proving the
real timeout *fires within budget* would need MockWebServer (`setBodyDelay`) — **not on
the classpath → that would be new infra (out of scope; see Open Questions).**

**Service/API mocking** — `@MockitoBean LlmClient` in `@SpringBootTest +
@AutoConfigureMockMvc + @Testcontainers` integration tests:
- parse success stub: `when(llmClient.generateStructured(any(), eq(MatchParseResult.class)))
  .thenReturn(stub)` (`MatchParseApiIntegrationTests.java:90`).
- streaming failure stub (void method): `doThrow(new LlmException("provider down"))
  .when(llmClient).generateStreaming(any(), any())` (`GamePlanApiIntegrationTests.java:120`).
- unit-level failure: `when(...).thenThrow(new LlmException("provider down"))` +
  `assertThatThrownBy(...)` (`MatchParseServiceTests.java:92-100`).

**Opt-in live tests:** `*LiveSmokeTest` gate on
`@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")`
(`LlmClientLiveSmokeTest.java:24`, `MatchParseLiveSmokeTest.java:21`) — auto-skip without
a key. New failure-path tests must be **mock-based**, named `*Tests`, and must NOT use
this mechanism.

**Conventions:** AssertJ everywhere (`assertThat`, `assertThatThrownBy`,
`assertThatCode().doesNotThrowAnyException()`); class naming `<ClassName>Tests`; method
naming `methodUnderTest_condition_expectedOutcome`.

**Already-covered (do NOT duplicate):**
- `OpenAiCompatLlmClientTests`: `generate_http4xx_…` (401), `generate_http5xx_…` (500),
  `generate_emptyChoices_…`, `generate_nullContent_…`,
  `generate_ioException_translatedToLlmException` (SocketTimeout→LlmException),
  `generateStreaming_http5xx_…` (500), `generateStreaming_malformedChunk_…`.
- `MatchParseServiceTests.parse_llmException_propagates` (`:92-100`).
- `GamePlanApiIntegrationTests.stream_llmFailure_emitsInStreamErrorEventNot503` (`:118-135`).

## Code References

- `llm/client/OpenAiCompatLlmClient.java:100-105` — the entire transient/permanent
  collapse: `RestClientResponseException` → status-bearing `LlmException`; everything
  else → `LlmException("LLM call failed")` with null status.
- `llm/client/OpenAiCompatLlmClient.java:167-172` — `clientWithTimeout` per-request read-timeout override.
- `llm/client/LlmClientConfig.java:15-28` — `RestClient` + `SimpleClientHttpRequestFactory`, connect=read=`llm.timeout`.
- `llm/client/LlmException.java` — `providerStatus` is the only signal; no `retryable`.
- `application.properties:16-20` — `llm.base-url` (Gemini OpenAI-compat), `llm.model=gemini-2.5-flash`, `llm.timeout=30s`.
- `user/ApiExceptionHandler.java:24-29` — `LlmException` → uniform `503`; `providerStatus` logged, not branched.
- `user/dto/ApiError.java:5-9` — `{status,message,fieldErrors}`, no retry signal.
- `match/MatchController.java:30-33`, `match/MatchParseService.java:28-32`, `match/MatchParsePromptBuilder.java:27` — parse path (no try/catch, `timeout=null`).
- `match/gameplan/GamePlanController.java:25-68` — SSE: `meta`(disclaimer) → tokens → `done`, or `meta` → `error` → `complete` on `LlmException`.
- `match/gameplan/GamePlanService.java:39-41`, `gameplan/GamePlanUnavailableException.java:5-7` — pre-LLM "no history" → `404`.
- `llm/AiDisclaimer.java:5`, `llm/AiContent.java:7-11` — disclaimer text; envelope unused.
- `OpenAiCompatLlmClientTests.java:36-42, 96-141` — `MockRestServiceServer` harness; `503`/timeout reuse points.
- `GamePlanApiIntegrationTests.java:118-135, 120, 160-171` — `@MockitoBean` failure/stream stubbing.
- `MatchParseApiIntegrationTests.java:38-39, 90` — `@MockitoBean LlmClient` success stub (no failure case yet).
- `pom.xml:74-98` — test deps (no MockWebServer/WireMock).

## Architecture Insights

- **Two structurally different failure surfaces.** Parse is request/response → a clean
  HTTP `503`. Game-plan is SSE → HTTP `200` already committed, so failure is in-band
  (`event: error` + `complete()`). Phase 2 needs **two different oracles**; a test that
  asserts `503` for the game-plan stream would be wrong (and is already guarded by
  `stream_llmFailure_emitsInStreamErrorEventNot503`).
- **The seam for failure injection is the `LlmClient` interface.** At the transport
  level use `MockRestServiceServer` (`503`, `SocketTimeoutException`); at the
  service/API level use `@MockitoBean LlmClient` + `doThrow`/`thenThrow`. Both already
  exist — no new infra.
- **The oracle gap is the deliverable.** Per `test-plan.md:70`, do **not** assert that
  "no transient/permanent distinction" is correct. Assert the true-today contract (no
  fake success, bounded failure, label holds) and document the missing retryable signal
  as the gap that motivates the separate `transient-llm-retry` feature (§7).
- **`<5s` NFR is unenforced.** Treat it as a latency observation, not a code-guarded
  bound. The only guard is the `30s` client timeout, and `MockRestServiceServer` cannot
  prove it fires on wall-clock — only that a timeout *maps* to `LlmException`.

## Historical Context (from prior changes)

- Phase 1 — `context/archive/**/ownership-boundary-tests` (closed `9bd09b7`): established
  the backend integration harness this phase reuses — `@SpringBootTest +
  @AutoConfigureMockMvc + @Testcontainers`, `registerAndLogin` sessions, the durable
  `@MethodSource` sweep + `RequestMappingHandlerMapping` guard pattern. See
  `test-plan.md` §6.2/§6.7.
- `context/foundation/test-plan.md` §2 Risk #2 row + Risk Response Guidance (`:44`, `:70`)
  — the source-of-truth oracle and "must challenge" list this research grounds.
- `context/foundation/lessons.md` — "Never use fully qualified class names when an import
  suffices" (relevant to new test code); the Docker/`host.docker.internal` lessons are
  Phase 5 concerns, not this phase.
- The LLM client itself shipped in the archived `llm-client` slice (closed `0b557ee`);
  parse/game-plan in `ai-match-entry` (`8cb6233`) and `ai-game-plan` (`df2433e`).

## Related Research

- None prior under `context/changes/**/research.md` for the AI failure path. This is the
  first research artifact for `ai-failure-path-tests`. Phase 3 (`frontend runner
  bootstrap`) will add the frontend error-state consumption test referenced in
  `test-plan.md:70` ("frontend error-state test after Phase 3 bootstrap").

## Open Questions

1. **Real wall-clock timeout test.** Proving the `30s` read timeout *fires within budget*
   needs a delayed socket response (MockWebServer `setBodyDelay`), which is **not** on the
   classpath. Options for the plan: (a) accept the existing `SocketTimeoutException`-mapping
   test as sufficient for "timeout → clean error" and skip wall-clock; (b) add MockWebServer
   as a test dependency (this is *new infra* — the change.md says "no new infra", so default
   to (a) unless the plan explicitly justifies otherwise).
2. **Should the test assert `providerStatus` is captured even though it is not used?** A
   client-level test (`generate_http503_setsProviderStatus503`) documents that the signal
   *exists* at the client boundary and is *lost* at the advice — useful as the regression
   anchor for the future `transient-llm-retry` feature. The plan should decide whether to
   include this "signal exists but is dropped" assertion.
3. **Parse-path API-layer failure test.** There is a service-unit propagation test but no
   `MatchParseApiIntegrationTests` case that stubs `generateStructured` to throw and asserts
   the end-to-end `503` + `ApiError` body. This is the most valuable gap to fill (proves the
   advice wiring, not just propagation).
