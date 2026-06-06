# AI Failure-Path Tests (Phase 2) Implementation Plan

## Overview

Phase 2 of the test rollout (`context/foundation/test-plan.md` §3) covers **Risk #2 — a
transient AI error surfaced as a dead-end**. We add backend tests proving that a
transient/erroring Gemini provider (`503`, slow/timeout) surfaces a **clean, bounded**
error — never a fake success, never an infinite spinner — across both failure surfaces
(synchronous parse and streaming game-plan), and that AI advice-labelling holds even when
the LLM call fails mid-stream.

This change writes **tests only**. It adds **no production code** and **no retry logic**
(retry-with-backoff is the separate, out-of-scope `transient-llm-retry` feature, §7). It
asserts **today's** behavior and records the missing retryable signal as a documented gap.

## Current State Analysis

(Grounded entirely in `context/changes/ai-failure-path-tests/research.md`.)

- **No transient-vs-permanent distinction exists.** Every `LlmException` — provider `503`,
  provider `400`, socket timeout, empty/malformed body — collapses to one generic signal
  per path. `LlmException` carries only a nullable `Integer providerStatus`
  (`llm/client/LlmException.java`); there is no `retryable` flag, no `Retry-After`, no error
  code.
- **Parse path** (`POST /api/matches/parse`): `MatchController.parse` →
  `MatchParseService.parse` → `LlmClient.generateStructured`; no try/catch. Any
  `LlmException` propagates to `@RestControllerAdvice ApiExceptionHandler.handleLlmException`
  (`user/ApiExceptionHandler.java:24-29`) → uniform HTTP **`503`** + body
  `{"status":503,"message":"AI service is temporarily unavailable","fieldErrors":null}`
  (`user/dto/ApiError.java:5-9`). `providerStatus` is **logged but never read** into the
  status or body.
- **Game-plan path** (`GET /api/game-plans/stream`): returns an `SseEmitter`. The disclaimer
  (`event: meta`, `AiDisclaimer.TEXT`) is sent **first** (`GamePlanController.java:33-36`),
  *before* the LLM streams on a virtual thread. By the time the LLM is called, HTTP `200` +
  SSE headers are already committed, so an `LlmException` is caught **inside** the controller
  (`GamePlanController.java:50-51`) → `sendErrorAndComplete` emits `event: error`
  (`{"message":"AI service is temporarily unavailable"}`) then `emitter.complete()`
  (`:60-68`). NOT a `503`.
- **Timeout:** the only timeout is the client's `30s` connect+read
  (`LlmClientConfig.java:15-28`, `application.properties:20`). There is no service/controller
  deadline; the `<5s` parse NFR is **unenforced in code** — treat it as a latency observation,
  not a guarded bound.
- **Harness available:** `OpenAiCompatLlmClientTests` binds `MockRestServiceServer` to a
  `RestClient.Builder` (`:36-42`); it already enqueues `500` (`withServerError()`), `401`
  (`withStatus`), and a `SocketTimeoutException` (`withException`, `:133-141`). API tests use
  `@MockitoBean LlmClient` + `doThrow`/`thenReturn`
  (`MatchParseApiIntegrationTests.java:38-39,90`; `GamePlanApiIntegrationTests.java:42-43,120`).
- **`MockRestServiceServer` cannot trip a real wall-clock timeout** (no socket, no body delay).
  Proving the configured timeout *fires* requires a real delayed-response server — MockWebServer.
- **Already covered (do NOT duplicate):** `generate_http4xx_…` (401), `generate_http5xx_…`
  (500), `generate_emptyChoices_…`, `generate_nullContent_…`,
  `generate_ioException_translatedToLlmException` (SocketTimeout→LlmException),
  `generateStreaming_http5xx_…`, `MatchParseServiceTests.parse_llmException_propagates`,
  `GamePlanApiIntegrationTests.stream_llmFailure_emitsInStreamErrorEventNot503`.

## Desired End State

A green backend test suite that, for both AI failure surfaces, proves:

1. A provider `503` → a clean error (parse: HTTP `503` + `ApiError`; game-plan: in-stream
   `event: error` at HTTP `200`), never a `200` success / fake parse result.
2. The configured client read timeout **fires within budget** on a genuinely slow provider
   and surfaces `LlmException` — the request does not hang.
3. The transient signal (`providerStatus == 503`) **is captured at the client boundary** and
   **is absent from the API response** — pinning the oracle gap as a regression anchor for the
   future `transient-llm-retry` feature.
4. The AI-advice disclaimer is still delivered (the `meta` event with `AiDisclaimer.TEXT`)
   even when the game-plan LLM call fails mid-stream.

Verification: `./mvnw test` is green; the new tests fail if any of the four contracts above
regress; CI never depends on a live API key.

### Key Discoveries:

- The two failure surfaces need **two different oracles** — a `503` assertion on the
  game-plan stream would be wrong (HTTP `200` is already sent). `GamePlanApiIntegrationTests`
  already guards this via `stream_llmFailure_emitsInStreamErrorEventNot503`.
- The disclaimer survives a mid-stream failure **by construction** —
  `GamePlanController.java:33` (meta) precedes `service.stream` at `:38`. This is the testable
  hook for "advice-labelling holds."
- `mockwebserver` is **not** managed by the Spring Boot 4.0.6 BOM — it needs an explicit
  pinned version. The client's `SimpleClientHttpRequestFactory` connects over real HTTP, so a
  localhost MockWebServer with `setBodyDelay` exercises the genuine read timeout.
- The oracle trap (`test-plan.md:70`): do **not** assert "no distinction" is correct. Assert
  today's true contract and document the gap.

## What We're NOT Doing

- **No retry-with-backoff** — that is the separate `transient-llm-retry` feature (§7). We do
  not add, and do not test, any retry behavior.
- **No production code changes** — no new `retryable` field on `LlmException`/`ApiError`, no
  `Retry-After` header, no service-layer `<5s` deadline. The plan asserts current behavior; it
  does not change it.
- **No live-LLM tests** — new tests are mock/stub-based, named `*Tests`, and never gate on
  `LLM_API_KEY`. The `*LiveSmokeTest` classes are untouched.
- **No frontend / error-state UI tests** — that is Phase 3 (`test-plan.md` §3).
- **No assertion that the `<5s` parse NFR is enforced** — it is not enforced in code; we only
  observe that failures are bounded by the `30s` client timeout.
- **No game-plan output-quality (LLM-as-judge) evaluation** — deferred (§4/§7).

## Implementation Approach

Three phases ordered cheapest-signal-first: (1) extend the existing client unit suite for the
explicit `503` + captured-signal assertions using the in-place `MockRestServiceServer` harness
(no new dependency); (2) add the one piece of new infra (MockWebServer) for the single test
that needs a real wall-clock timeout, and honestly amend the `change.md` scope note; (3) add
the end-to-end API-layer oracles for both failure surfaces, reusing the `@MockitoBean LlmClient`
pattern. All new tests follow the existing AssertJ style and `<ClassName>Tests` naming, and the
`methodUnderTest_condition_expectedOutcome` method convention.

## Critical Implementation Details

- **`mockwebserver` version is unmanaged.** Spring Boot 4.0.6's BOM does not manage
  `com.squareup.okhttp3:mockwebserver`; declare an explicit version (`4.12.0`, the stable 4.x
  line whose `MockResponse().setBodyDelay(...)` / `enqueue(...)` API the timeout test uses).
  4.x pulls in `kotlin-stdlib` at test scope — acceptable.
- **The timeout test must use a real RestClient with a *short* configured timeout**, not the
  per-request `LlmRequest.timeout()` override path, so it proves the configured-bean timeout
  fires. Build the client via the production `LlmClientConfig.llmRestClient(props)` with
  `props.timeout()` set well below the enqueued `setBodyDelay`, point `baseUrl` at the
  MockWebServer URL, and assert both that an `LlmException` is thrown and that it returns far
  inside the body-delay window (bounded, no hang).
- **Game-plan SSE ordering is the oracle.** Assert event *sequence*: `meta` (carrying
  `AiDisclaimer.TEXT`) arrives before `error`, and the stream `complete()s` — do not assert an
  HTTP error status on this path.

## Phase 1: Client-layer transient-error tests (no new infra)

### Overview

Pin the client-boundary behavior for a transient `503`: it maps to `LlmException` with
`providerStatus == 503`, and the existing timeout-mapping coverage is retained. Uses the
in-place `MockRestServiceServer` harness — no new dependency.

### Changes Required:

#### 1. Explicit 503 + captured-signal assertion

**File**: `src/test/java/org/borowiec/squashprogresstracker/llm/client/OpenAiCompatLlmClientTests.java`

**Intent**: Add a synchronous-path test that a provider `503` (the exact transient status from
the lived incident) is translated to `LlmException` with `providerStatus() == 503` — proving
the transient signal is captured at the client boundary. Confirm the existing
`generate_ioException_translatedToLlmException` (SocketTimeout→`LlmException`) test stays as the
fast unit-level timeout-mapping check (no change to it).

**Contract**: New test method `generate_http503_throwsLlmExceptionWithProviderStatus503`,
following the existing `generate_http5xx_…` pattern — `mockServer.expect(requestTo(...))
.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))`, then
`assertThatThrownBy(() -> client.generate(...)).isInstanceOf(LlmException.class)
.satisfies(e -> assertThat(((LlmException) e).providerStatus()).isEqualTo(503))`. No snippet
needed beyond this idiom; it mirrors `OpenAiCompatLlmClientTests.java:105-112`.

### Success Criteria:

#### Automated Verification:

- New test passes: `./mvnw test -Dtest=OpenAiCompatLlmClientTests`
- Existing timeout-mapping test still present and green (`generate_ioException_translatedToLlmException`)
- Formatting/lint clean: `./mvnw spotless:check`

#### Manual Verification:

- The 503 test fails (red) if `OpenAiCompatLlmClient`'s `RestClientResponseException` catch is
  removed or stops capturing `providerStatus` — confirming it guards the captured signal.

---

## Phase 2: Real wall-clock timeout test (new infra)

### Overview

Add MockWebServer (test scope, pinned) and one dedicated test proving the **configured** client
read timeout fires within budget on a genuinely slow provider, surfacing `LlmException` without
hanging. Amend the `change.md` "no new infra" note to record the deliberate exception.

### Changes Required:

#### 1. Add the pinned test dependency

**File**: `pom.xml`

**Intent**: Add `com.squareup.okhttp3:mockwebserver` at `test` scope so a localhost server can
return a delayed response that trips the real read timeout. The Spring Boot BOM does not manage
this artifact, so pin the version explicitly.

**Contract**: New `<dependency>` in the test-scope block (alongside `pom.xml:74-98`):
`groupId com.squareup.okhttp3`, `artifactId mockwebserver`, `version 4.12.0`, `scope test`.

#### 2. Wall-clock timeout test

**File**: `src/test/java/org/borowiec/squashprogresstracker/llm/client/OpenAiCompatLlmClientTimeoutTests.java` (new)

**Intent**: Stand up a MockWebServer, build the production client via
`LlmClientConfig.llmRestClient(props)` with a short `props.timeout()`, enqueue a response whose
`setBodyDelay` exceeds that timeout, and assert that `generate(...)` throws `LlmException`
(timeout → `ResourceAccessException` → wrapped) and returns **well inside** the body-delay
window (proving the timeout fired and the call did not hang). Shut the server down in teardown.

**Contract**: New class `OpenAiCompatLlmClientTimeoutTests` (JUnit 5, AssertJ). Key method
`generate_providerSlowerThanTimeout_throwsLlmExceptionWithinBudget`. The non-obvious part is the
timing/bounded assertion, so a sketch of the contract:

```java
var server = new MockWebServer(); server.start();
server.enqueue(new MockResponse().setBodyDelay(5, TimeUnit.SECONDS).setBody("{}"));
var props = new LlmClientProperties("k", server.url("/").toString(), "m", "", Duration.ofMillis(300));
var client = new OpenAiCompatLlmClient(new LlmClientConfig().llmRestClient(props), props, objectMapper);
// assert: throws LlmException AND elapsed < bodyDelay (e.g. < 3s) → fired within budget, no hang
```

(Exact API surface — `setBodyDelay` vs `MockResponse.Builder` — is the implementer's call per
the resolved mockwebserver 4.12 API; the contract is "delayed response trips the configured
read timeout, bounded.")

#### 3. Amend the scope note

**File**: `context/changes/ai-failure-path-tests/change.md`

**Intent**: Update the "Layer: backend, reusing the existing suite — no new infra" line to
record the deliberate exception: MockWebServer (test scope) is added solely to exercise the
real read timeout; no other new infra.

**Contract**: Edit the `## Notes` bullet; keep it one line, factual.

### Success Criteria:

#### Automated Verification:

- New timeout test passes: `./mvnw test -Dtest=OpenAiCompatLlmClientTimeoutTests`
- Full suite still green: `./mvnw test`
- `mvnw` resolves `mockwebserver:4.12.0` (no version-management error)
- Formatting clean: `./mvnw spotless:check`

#### Manual Verification:

- The test fails (red) / hangs if `props.timeout()` is raised above the body delay — confirming
  it is the configured timeout being exercised, not an unrelated failure.
- `change.md` note accurately reflects the added dependency.

---

## Phase 3: API-layer failure oracles (parse 503 + game-plan SSE + advice-labelling)

### Overview

End-to-end oracles for both failure surfaces via the `@MockitoBean LlmClient` pattern: the parse
path returns a clean `503` whose body carries **no** retryable signal (the dropped-signal
assertion), and the game-plan stream emits an in-stream `error` while the AI-advice disclaimer
**survives** the mid-stream failure.

### Changes Required:

#### 1. Parse-path failure oracle + dropped-signal assertion

**File**: `src/test/java/org/borowiec/squashprogresstracker/match/MatchParseApiIntegrationTests.java`

**Intent**: Add a test that stubs `llmClient.generateStructured(...)` to throw
`new LlmException("Provider error: 503 SERVICE_UNAVAILABLE", null, 503)` and asserts the endpoint
returns HTTP `503` with the `ApiError` body `{"status":503,"message":"AI service is temporarily
unavailable"}`, and that the response body contains **no** `retryable`/`retryAfter`/`providerStatus`
field and **no** `Retry-After` header — pinning that the transient signal is dropped at the API.
Proves end-to-end wiring (advice + handler), which the existing service-unit propagation test does
not cover.

**Contract**: New test method `parse_llmException_returns503CleanErrorWithoutRetrySignal`, using
the in-place `@MockitoBean LlmClient` (`:38-39`) + MockMvc. Assert status `503`, JSON `status`/
`message` fields, JSON has no retry/status field, and absent `Retry-After` header. Reuse the
existing success-stub idiom at `:90` inverted to a throw.

#### 2. Game-plan SSE failure oracle + advice-labelling-holds

**File**: `src/test/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanApiIntegrationTests.java`

**Intent**: Add a test that drives the stream with a valid opponent (so `prepare` succeeds and the
stream opens) and stubs `generateStreaming(...)` to throw `LlmException`, then asserts the SSE
output sequence: a `meta` event carrying `AiDisclaimer.TEXT` arrives **before** an `error` event
(`{"message":"AI service is temporarily unavailable"}`), and the stream completes cleanly — proving
the advice label holds even when the LLM fails mid-stream. Complements (does not duplicate) the
existing `stream_llmFailure_emitsInStreamErrorEventNot503`, which asserts the HTTP-200-not-503
property but not the disclaimer-survives property.

**Contract**: New test method `stream_llmFailureMidStream_stillDeliveredAdviceDisclaimerBeforeError`,
reusing the streaming-failure stub (`doThrow(new LlmException(...)).when(llmClient)
.generateStreaming(any(), any())`, `:120`) and the opponent/match setup that lets `prepare`
succeed. Assert event names/order and that the `meta` payload's `disclaimer` equals
`AiDisclaimer.TEXT`.

### Success Criteria:

#### Automated Verification:

- Parse oracle passes: `./mvnw test -Dtest=MatchParseApiIntegrationTests`
- Game-plan oracle passes: `./mvnw test -Dtest=GamePlanApiIntegrationTests`
- Full suite green: `./mvnw test`
- Formatting clean: `./mvnw spotless:check`

#### Manual Verification:

- The parse oracle fails (red) if a future change leaks a `providerStatus`/`Retry-After` into the
  error response — i.e. it correctly flips when the deferred `transient-llm-retry` feature lands,
  signalling the gap is being closed.
- The game-plan oracle fails (red) if the `meta`/disclaimer event is moved after the LLM call or
  dropped on the failure path.

---

## Testing Strategy

### Unit Tests:

- Client `503` → `LlmException(providerStatus=503)` (Phase 1).
- Configured read timeout fires within budget on a slow provider → `LlmException`, no hang (Phase 2).
- Existing `SocketTimeoutException`→`LlmException` mapping retained (Phase 1, unchanged).

### Integration Tests:

- Parse path: `LlmException` → HTTP `503` + `ApiError`, no retry signal in body/headers (Phase 3).
- Game-plan stream: `LlmException` mid-stream → `meta`(disclaimer) then `error` then clean
  complete, HTTP `200` throughout (Phase 3).

### Manual Testing Steps:

1. Run `./mvnw test` — confirm all new and existing tests pass.
2. Temporarily raise `props.timeout()` above the body delay in the Phase 2 test — confirm it
   hangs/fails, proving the configured timeout is what's exercised; then revert.
3. Temporarily move the `meta` send after `service.stream` in `GamePlanController` (local only) —
   confirm the Phase 3 game-plan oracle goes red; then revert.

## Performance Considerations

The Phase 2 timeout test uses a short configured timeout (~300ms) against a body delay of a few
seconds, so it completes quickly while still exercising the real read timeout. Keep the body delay
modest (≤5s) and assert the bounded elapsed time so the test cannot become a multi-second drag or a
hang on regression.

## Migration Notes

None — tests only, no schema or data changes.

## References

- Related research: `context/changes/ai-failure-path-tests/research.md`
- Test strategy: `context/foundation/test-plan.md` §2 (Risk #2 + Risk Response Guidance, lines
  44/70), §3 (Phase 2), §4 (stub the transport), §6.1/§6.2 (cookbook), §7 (no retry here)
- Reuse targets: `src/test/java/.../llm/client/OpenAiCompatLlmClientTests.java:36-42,105-141`;
  `src/test/java/.../match/MatchParseApiIntegrationTests.java:38-39,90`;
  `src/test/java/.../match/gameplan/GamePlanApiIntegrationTests.java:42-43,118-135`
- Production under test: `llm/client/OpenAiCompatLlmClient.java:100-105`,
  `llm/client/LlmClientConfig.java:15-28`, `user/ApiExceptionHandler.java:24-29`,
  `match/gameplan/GamePlanController.java:33-68`, `llm/AiDisclaimer.java:5`
- Prior phase pattern: `context/foundation/lessons.md` (imports over FQNs; test conventions)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Client-layer transient-error tests (no new infra)

#### Automated

- [x] 1.1 New 503 test passes: `./mvnw test -Dtest=OpenAiCompatLlmClientTests` — b8fab4c
- [x] 1.2 Existing timeout-mapping test still present and green — b8fab4c
- [x] 1.3 Formatting/lint clean: `./mvnw spotless:check` — b8fab4c

#### Manual

- [x] 1.4 503 test goes red if the client stops capturing providerStatus — b8fab4c

### Phase 2: Real wall-clock timeout test (new infra)

#### Automated

- [x] 2.1 New timeout test passes: `./mvnw test -Dtest=OpenAiCompatLlmClientTimeoutTests`
- [x] 2.2 Full suite still green: `./mvnw test`
- [x] 2.3 `mvnw` resolves `mockwebserver:4.12.0` with no version-management error
- [x] 2.4 Formatting clean: `./mvnw spotless:check`

#### Manual

- [x] 2.5 Test hangs/fails if `props.timeout()` is raised above body delay (confirms configured timeout exercised)
- [x] 2.6 `change.md` note accurately reflects the added dependency

### Phase 3: API-layer failure oracles

#### Automated

- [ ] 3.1 Parse oracle passes: `./mvnw test -Dtest=MatchParseApiIntegrationTests`
- [ ] 3.2 Game-plan oracle passes: `./mvnw test -Dtest=GamePlanApiIntegrationTests`
- [ ] 3.3 Full suite green: `./mvnw test`
- [ ] 3.4 Formatting clean: `./mvnw spotless:check`

#### Manual

- [ ] 3.5 Parse oracle flips red if a retry signal leaks into the error response (gap-closing signal)
- [ ] 3.6 Game-plan oracle flips red if the disclaimer event is moved after the LLM call or dropped
