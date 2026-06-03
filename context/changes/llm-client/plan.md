# Provider-Agnostic LLM Client (F-02) Implementation Plan

## Overview

Build the thin, provider-agnostic `LlmClient` enabler both AI slices (S-02 game plan, S-03 AI match entry) will call. It wraps an **OpenAI-compatible `/chat/completions` endpoint** behind one interface, using Spring's `RestClient` (no new dependency), defaulting to **Google Gemini Flash-Lite** via its OpenAI-compat endpoint. The client exposes a free-text `generate` and a typed `generateStructured`. Alongside the client, F-02 ships the two cross-cutting conventions the roadmap assigns it: a **server-side "AI-generated advice" labelling** contract and a **progress-state contract** — both reused (not re-invented) by S-02/S-03. Verification uses a mocked HTTP transport plus a live smoke test that auto-skips without a key.

This is a foundation enabler. It adds **no `/api` feature endpoints** — those land in S-02/S-03. Scope is "minimal — just enough for the first AI slice to call" (`roadmap.md:79`), with the deliberate exception (confirmed during planning) that the typed structured-output method is included now so both AI slices inherit a complete call contract.

## Current State Analysis

- **Stack:** Java 21 / Spring Boot 4.0.6 / Maven. `spring-boot-starter-webmvc`, `-security`, `-data-jpa`, `-validation`, `-flyway`, `-actuator` present (`pom.xml`). **No AI/LLM dependency** — and none is needed: `RestClient` ships with `spring-web` (pulled by `-webmvc`), Jackson 3 (`tools.jackson.*`) is on the classpath, and `MockRestServiceServer` ships with the test starter.
- **Conventions to mirror** (all confirmed in the codebase):
  - Constructor injection, `@Service`, `@RestController` under `/api` (`match/MatchController.java:11-19`).
  - DTO **records** with static `from()` factories (`match/dto/MatchResponse.java`, `user/dto/ApiError.java`).
  - `CurrentUser` carries the per-player auth boundary (`security/CurrentUser.java`, used in `match/MatchService.java:25`).
  - Centralized error mapping via `@RestControllerAdvice` returning `ApiError` (`user/ApiExceptionHandler.java:15-47`).
  - Env-var config placeholders in `application.properties` (e.g. `${DB_HOST}`), `validate` DDL, UTC.
  - **Jackson 3**: note `SecurityConfig.java:3` imports `tools.jackson.databind.ObjectMapper` — use Jackson 3 packages, not `com.fasterxml.jackson.*`.
  - Integration tests use Testcontainers + `spring-security-test` (`match/MatchApiIntegrationTests.java`).
- **Research settled** (`research.md`): integration path = direct SDK/HTTP behind a thin `LlmClient` (Spring AI 2.0 still pre-GA — **re-verified 2026-06-03: latest is 2.0.0-M8, GA slipped past its May 28 target**, so the direct path stands); standardize on the **OpenAI wire format** so Gemini/Groq/Mistral are config-swappable; default **Gemini Flash-Lite**, free tier for synthetic dev data only, **paid no-training tier for real user data**.
- **Constraints:** `<5s` perceived parse budget and "continuous progress feedback" NFRs (`prd.md:100-105`); hard privacy guardrail (no cross-player data) and the "label every AI game plan as advice" hard rule (`AGENTS.md`); Render/Frankfurt backend favors EU-reachable endpoints (`infrastructure.md`).

## Desired End State

A Spring-managed `LlmClient` bean is available for injection. Calling `generate(LlmRequest)` returns model text; calling `generateStructured(LlmRequest, Class<T>)` returns a typed, Jackson-deserialized `T` produced via `response_format: json_schema`. Both calls are bounded by a configurable timeout and translate provider/transport failures into a single `LlmException`, which the existing advice maps to a clean `ApiError`. A shared `AiDisclaimer` constant and an `AiContent<T>` envelope marker exist for slices to wrap AI output (server-side advice labelling), and a documented `LlmProgress` contract exists for slices to drive progress UX. The build is green with mocked-transport tests; a developer with a real `LLM_API_KEY` can run one gated smoke test that performs a real Gemini call.

Verify by: `./mvnw test` passes (mock-transport tests cover both methods + error/timeout); injecting `LlmClient` into a throwaway runner with a real key returns a coherent completion; `grep` confirms no `com.fasterxml.jackson` imports; the live smoke test is skipped (not failed) when `LLM_API_KEY` is unset.

### Key Discoveries:

- No new Maven dependency required — `RestClient` + `MockRestServiceServer` + Jackson 3 are all already present (`pom.xml`).
- Jackson 3 package is `tools.jackson.*` (`security/SecurityConfig.java:3`) — easy to get wrong from training data.
- Error funnel already exists: extend `ApiExceptionHandler` (`user/ApiExceptionHandler.java:15`) rather than inventing a new one.
- Gemini exposes an OpenAI-compatible base URL (`https://generativelanguage.googleapis.com/v1beta/openai`), so the same adapter serves Gemini, Groq, and Mistral by changing `base-url` + `model` only (`research.md:74,114`).
- Model id is **pinned to the GA `gemini-2.5-flash-lite`** (not the `-latest` alias) for reproducibility; `gemini-3.1-flash-lite-preview` is a documented env-swap upgrade (resolves `research.md:131`).

## What We're NOT Doing

- No AI feature endpoints, controllers, prompts, or UI — S-02 (game plan) and S-03 (AI entry) own those.
- No streaming / SSE transport. F-02 ships the **progress contract** (vocabulary) only; the streaming UX is S-02's to build (decision: "Convention + synchronous now").
- No Spring AI dependency (pre-GA on Boot 4) and no vendor SDK (`openai-java` / `google-genai`) — plain `RestClient` per decision.
- No retry/backoff, circuit breaker, caching, or token-budgeting — out of scope for a thin MVP enabler; a single bounded timeout is the only resilience control.
- No persistence of prompts/responses, no usage metering.
- No provider rename of `LLM_API_KEY` to a Gemini-specific name — the placeholder stays provider-agnostic by design (`infrastructure.md:108,115`).
- No live LLM call in CI — CI runs mock-transport tests only.

## Implementation Approach

A small `llm` package mirroring the existing feature-package layout (`user/`, `match/`). Phase 1 lays down the pure-contract surface (interface, DTOs, exception, properties, configured `RestClient` bean, the two conventions, and error mapping) — all unit-testable with no network. Phase 2 implements the single `OpenAiCompatLlmClient` adapter that turns `LlmRequest` into an OpenAI `chat/completions` body (adding `response_format: json_schema` for the structured path), parses the first choice, enforces the timeout, and maps failures to `LlmException`. Phase 3 verifies with `MockRestServiceServer` (deterministic, key-free) plus a key-gated live smoke test, and wires the env placeholders into `application.properties`, `render.yaml`, and local-run docs.

The OpenAI wire format is the swap seam: provider choice is `base-url` + `model` configuration, never a code change.

## Critical Implementation Details

- **Jackson 3 only.** Use `tools.jackson.databind.*` for any `ObjectMapper`/`JsonNode` work (`SecurityConfig.java:3`). A `com.fasterxml.jackson.*` import will compile against transitive jars but diverges from the project's chosen Jackson and must not be introduced.
- **Timeout must sit under the parse budget.** The `<5s` perceived-parse NFR (`prd.md:102`) bounds the structured path. Configure `RestClient`'s underlying request factory with a read timeout (default `20s` for game-plan generosity is wrong for parse) — make the bean-level timeout a property (default `30s` connect/read ceiling for generation) **and** let `LlmRequest.timeout` override the read timeout per call so S-03's parse caller can set it tight (~4s) without a second bean. When `LlmRequest.timeout` is null the configured default applies; the connect timeout stays bean-level.
- **`response_format` json_schema shape.** OpenAI-compat structured output requires `{"type":"json_schema","json_schema":{"name":...,"schema":{...},"strict":true}}`. The JSON Schema is derived from the target `Class<T>`. **Unverified:** `strict:true` is an OpenAI-specific field; Gemini's OpenAI-compat endpoint accepts `json_schema` but may treat `strict` as advisory (best-effort JSON) rather than enforced — `research.md`'s 97% compliance figure is for Gemini's *native* API, not the compat layer. This is not load-bearing for correctness because the adapter parses the returned content with Jackson regardless; if a provider returns content that doesn't deserialize into `T` (compat layer ignored `strict`, or a swapped provider doesn't support `json_schema` at all), the adapter must surface a clear `LlmException` rather than silently returning unparsed text. The Phase 3 live smoke test's structured case is the gate that confirms Gemini's compat layer actually yields deserializable typed output.

## Phase 1: Contract, Config & Conventions

### Overview

Establish everything F-02 owns that requires no network call: the client interface and its data types, configuration binding, the `RestClient` bean, the advice-labelling and progress conventions, and error translation. After this phase the project compiles and the conventions are unit-tested, but no real call is wired yet.

### Changes Required:

#### 1. LLM package + client contract

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/LlmClient.java`

**Intent**: Define the single provider-agnostic seam both AI slices depend on, decoupled from any transport or vendor.

**Contract**: Interface with two methods — `String generate(LlmRequest request)` and `<T> T generateStructured(LlmRequest request, Class<T> type)`. Both declared to throw the unchecked `LlmException`.

#### 2. Request/response value types

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/dto/LlmRequest.java` (+ `LlmMessage.java`, `LlmRole.java` enum)

**Intent**: Carry the call inputs in a vendor-neutral shape that maps cleanly onto OpenAI `messages`.

**Contract**: `LlmRequest` is a record holding an ordered `List<LlmMessage>` (and optionally a `Double temperature`, `Integer maxTokens` — nullable, omitted from the wire body when null) plus a nullable `java.time.Duration timeout` for a **per-call** override of the bean-level read timeout (S-03's parse path sets ~4s; null = use the configured default). `LlmMessage` is a record of `(LlmRole role, String content)`; `LlmRole` enum = `SYSTEM, USER, ASSISTANT`. Provide a `LlmRequest.ofUser(String)` convenience factory mirroring the project's `from()`/factory idiom.

#### 3. Failure type

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/LlmException.java`

**Intent**: One exception the rest of the app catches, regardless of which provider/transport failed.

**Contract**: Extends `RuntimeException`; carries the original cause and an optional `Integer providerStatus` (upstream HTTP status when known). Used by the advice in change #7.

#### 4. Configuration properties

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/LlmClientProperties.java`

**Intent**: Bind all provider-swap knobs to config so changing providers is config-only.

**Contract**: `@ConfigurationProperties(prefix = "llm")` record/class with: `apiKey`, `baseUrl`, `model` (text default), `structuredModel` (resolves to `model` when **blank**, not just null — the `application.properties` default binds an empty string `${LLM_STRUCTURED_MODEL:}`, so use a `hasText` / blank check, never a `!= null` check), `timeout` (`java.time.Duration`). Enable via `@EnableConfigurationProperties` on the config class (#6). Validate `apiKey`/`baseUrl` presence lazily (the client fails with `LlmException` on first use if missing, so the app still boots for non-AI slices and tests).

#### 5. Application config wiring

**File**: `src/main/resources/application.properties`

**Intent**: Surface the `llm.*` properties bound to provider-agnostic env placeholders, defaulting to Gemini's OpenAI-compat endpoint.

**Contract**: Add `llm.api-key=${LLM_API_KEY:}`, `llm.base-url=${LLM_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}`, `llm.model=${LLM_MODEL:gemini-2.5-flash-lite}`, `llm.structured-model=${LLM_STRUCTURED_MODEL:}`, `llm.timeout=${LLM_TIMEOUT:30s}`. Empty `api-key` default keeps boot working without a key.

**Model choice (resolves `research.md:131` Open Q3 — 2.5 vs 3.1):** pin the **explicit GA** id `gemini-2.5-flash-lite` ($0.10/$0.40 per 1M, ~97% structured-output compliance, sub-second–~1.8s p95). Do **not** use the `gemini-flash-lite-latest` alias as the committed default — an alias silently re-points when Google promotes a new model, changing price/behavior of a "foundation" with no code change. `gemini-3.1-flash-lite-preview` ($0.25/$1.50, faster TTFT, still Preview) is a documented env override (`LLM_MODEL`) to adopt once it GAs or if the parse latency budget demands it.

#### 6. RestClient bean for the LLM

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/LlmClientConfig.java`

**Intent**: Provide one configured `RestClient` scoped to the LLM endpoint, with base URL, bearer auth, and the bounded timeout, so the adapter stays transport-thin.

**Contract**: `@Configuration` + `@EnableConfigurationProperties(LlmClientProperties.class)`. A `@Bean` `RestClient llmRestClient(LlmClientProperties)` that sets `baseUrl`, a default `Authorization: Bearer <apiKey>` header, JSON content type, and a `ClientHttpRequestFactory` with connect/read timeout = `properties.timeout()`. Keep this bean distinct from any app-wide `RestClient` (qualify by name).

#### 7. Advice-labelling convention (server-side)

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/AiDisclaimer.java` + `src/main/java/org/borowiec/squashprogresstracker/llm/AiContent.java`

**Intent**: Make "this is AI-generated advice" originate server-side and impossible to drop, satisfying the AGENTS.md hard rule once for both slices.

**Contract**: `AiDisclaimer` exposes a single canonical `String TEXT` constant (e.g. "AI-generated advice — not factual analysis. Verify before relying on it."). `AiContent<T>` is a record `(T content, boolean aiGenerated, String disclaimer)` with a factory `AiContent.of(T)` that sets `aiGenerated=true` and `disclaimer=AiDisclaimer.TEXT`. S-02/S-03 wrap their AI response DTOs in `AiContent` so the label travels in the response envelope. Documented as the required wrapper for any AI-derived response.

#### 8. Progress-state contract

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/LlmProgress.java`

**Intent**: Give both slices one vocabulary for the "continuous progress feedback" NFR without building streaming transport yet.

**Contract**: An enum `LlmProgress { SUBMITTED, GENERATING, COMPLETED, FAILED }` with short human-readable labels. F-02 ships the vocabulary and a one-paragraph doc comment stating S-02 realizes it over SSE; F-02 itself is synchronous and does not emit these. No transport.

#### 9. Error mapping

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/ApiExceptionHandler.java`

**Intent**: Translate `LlmException` to a clean client error through the existing funnel rather than leaking stack traces.

**Contract**: Add an `@ExceptionHandler(LlmException.class)` returning `ApiError.of(503, "AI service is temporarily unavailable")` with `@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)`. Log the exception's `providerStatus` (when present) and cause at WARN so the upstream failure is diagnosable server-side, while the client still sees only the clean 503. Mirror the existing handlers' style (`user/ApiExceptionHandler.java:30-46`). (Handler placement is fine in the `user` package — it's a global `@RestControllerAdvice`; a follow-up may relocate it, out of scope here.)

### Success Criteria:

#### Automated Verification:

- Compiles: `./mvnw -q compile`
- Full test suite passes: `./mvnw test`
- Conventions unit-tested: `AiContent.of(x)` sets `aiGenerated=true` and `disclaimer=AiDisclaimer.TEXT`; `LlmRequest.ofUser` builds a single USER message.
- No Jackson 2 imports introduced: `! grep -rn "com.fasterxml.jackson" src/main/java/org/borowiec/squashprogresstracker/llm`
- Properties bind: a `@SpringBootTest` slice (or `ApplicationContextRunner`) confirms `LlmClientProperties` populates from `llm.*` defaults and the `llmRestClient` bean is creatable.

#### Manual Verification:

- The `llm` package layout reads like `user/`/`match/` (records, factories, constructor injection).
- `AiDisclaimer.TEXT` wording satisfies the "labelled as advice, not factual analysis" hard rule.
- `LlmProgress` doc comment makes clear F-02 ships the vocabulary, S-02 drives it.

**Implementation Note**: After this phase and passing automated verification, pause for human confirmation of the manual items before Phase 2.

---

## Phase 2: OpenAI-Compatible Adapter

### Overview

Implement the one adapter that fulfills `LlmClient` against an OpenAI-compatible `chat/completions` endpoint, including the typed structured-output path. This is the only place that knows the wire format.

### Changes Required:

#### 1. Adapter implementation

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/OpenAiCompatLlmClient.java`

**Intent**: Turn vendor-neutral `LlmRequest`s into OpenAI `chat/completions` calls and parse the result, for both the text and structured paths, with timeout and error translation.

**Contract**: `@Service implements LlmClient`, constructor-injected with the qualified `llmRestClient` and `LlmClientProperties` (+ a Jackson 3 `ObjectMapper` for schema/result handling).
- `generate`: POST `/chat/completions` with body `{model, messages}` (messages mapped from `LlmRequest`), adding `temperature` and `max_tokens` to the body only when the corresponding `LlmRequest` field is non-null; return `choices[0].message.content`.
- `generateStructured`: same (including the `temperature`/`max_tokens` omit-when-null rule), plus `response_format: {type:"json_schema", json_schema:{name, schema, strict:true}}` where `schema` is the JSON Schema derived from `type`; deserialize `choices[0].message.content` into `T` via the Jackson 3 `ObjectMapper`.
- Use `structuredModel` for the structured path, falling back to `model` when `structuredModel` is **blank** (`StringUtils.hasText`), since the config default binds an empty string — a `!= null` check would POST an empty model id; use `model` for `generate`.
- When `LlmRequest.timeout` is non-null, apply it as a per-request read-timeout override (e.g. via the RestClient request-factory settings for that exchange); otherwise use the bean-configured timeout. Verify the chosen request factory supports per-request timeout override.
- Map any `RestClientResponseException` / IO / timeout / empty-choices / JSON-parse failure to `LlmException` (carry the upstream status when present). Never return partial/unlabelled text on the structured path — throw instead.

A short code snippet is warranted here only for the `response_format` body shape (the one non-obvious wire detail) — see Critical Implementation Details. The rest follows the `RestClient` fluent pattern and needs no snippet.

#### 2. JSON-Schema derivation helper

**File**: `src/main/java/org/borowiec/squashprogresstracker/llm/JsonSchemaFactory.java` (small internal helper)

**Intent**: Produce the `schema` object for a target `Class<T>` so `generateStructured` can request strict typed output.

**Contract**: One method `JsonNode schemaFor(Class<?> type)` returning a JSON-Schema `ObjectNode` (properties + required) for flat record/POJO targets used by the AI slices. Keep it minimal — support the field types S-02/S-03 actually need (string, number, boolean, nested record, list); document unsupported shapes throw `IllegalArgumentException`. (If this proves fiddly, an acceptable fallback is a hand-written schema passed by the caller — but default to deriving from the class to keep the call site clean.)

### Success Criteria:

#### Automated Verification:

- Compiles: `./mvnw -q compile`
- Full suite passes: `./mvnw test`
- `generateStructured` request body includes a `response_format.json_schema.strict=true` (asserted via mock in Phase 3, but the body-builder is unit-coverable here).

#### Manual Verification:

- Adapter is the only file referencing `chat/completions` / OpenAI field names — the wire format is not leaked elsewhere.
- Swapping `llm.base-url` + `llm.model` to a Groq/Mistral value requires no code edit (read-through of the adapter confirms config-only swap).

**Implementation Note**: After this phase and passing automated verification, pause for human confirmation before Phase 3.

---

## Phase 3: Verification & Ops Wiring

### Overview

Prove the adapter against a mocked transport (deterministic, key-free) and against the real provider via a gated smoke test, then wire the env placeholders into runtime config and deployment.

### Changes Required:

#### 1. Mock-transport integration tests

**File**: `src/test/java/org/borowiec/squashprogresstracker/llm/OpenAiCompatLlmClientTests.java`

**Intent**: Exercise the real request-building and response-parsing wiring without a network or key.

**Contract**: Use `MockRestServiceServer` bound to the `llmRestClient`'s request factory (or a `RestClient.Builder` configured identically). Cases: (a) `generate` happy path returns the choice content and sends the bearer header + correct model; (b) `generateStructured` sends `response_format.json_schema.strict=true` and deserializes a typed record; (c) HTTP 4xx/5xx → `LlmException` carrying the status; (d) malformed/empty `choices` → `LlmException`; (e) a transport timeout/IO failure is **translated** to `LlmException` (simulated via `MockRestServiceServer`'s `withException(new SocketTimeoutException(...))` — note the mock replaces the request factory, so this covers exception *translation*, not real timeout enforcement); (f) the `llmRestClient` bean is built with the configured timeout `Duration` (read it back from the bean's request-factory settings) so the timeout *wiring* is covered even though no test triggers a real socket timeout. Assert request bodies with the Jackson 3 `ObjectMapper`.

#### 2. Gated live smoke test

**File**: `src/test/java/org/borowiec/squashprogresstracker/llm/LlmClientLiveSmokeTest.java`

**Intent**: Let a developer confirm the real Gemini call works, without ever failing CI.

**Contract**: `@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")` so it auto-skips when unset. Boots a minimal context (or builds the client from real properties), calls `generate(LlmRequest.ofUser("Reply with the single word: pong"))`, asserts a non-blank response; **and** calls `generateStructured` against a tiny record and asserts it deserializes into the expected typed fields. The structured assertion is the load-bearing gate confirming Gemini's OpenAI-compat layer yields deserializable typed output (it verifies the real contract that mock tests cannot — see Critical Implementation Details on `strict`). Marked clearly as a manual/local smoke test.

#### 3. Deployment + local env wiring

**File**: `render.yaml`, plus local-run docs (`run-local.sh` / README note)

**Intent**: Make the new env vars discoverable and settable in every environment without leaking secrets.

**Contract**: Add `LLM_API_KEY` (and optionally `LLM_BASE_URL`, `LLM_MODEL`) to `render.yaml` `envVars` as a `sync: false` secret placeholder (set in the Render dashboard, not committed). Document the same vars for local runs. Do **not** commit any real key. The `application.properties` defaults (Phase 1 #5) mean only `LLM_API_KEY` is mandatory.

### Success Criteria:

#### Automated Verification:

- Full suite passes with no key present: `./mvnw test` (live smoke test reports skipped, not failed).
- Mock-transport tests cover happy path, structured path, HTTP error, malformed response, and timeout/IO-exception translation; a separate assertion confirms the `llmRestClient` bean carries the configured timeout `Duration`.
- `render.yaml` parses and contains an `LLM_API_KEY` entry marked non-syncing/secret.

#### Manual Verification:

- With a real `LLM_API_KEY` exported, `./mvnw test -Dtest=LlmClientLiveSmokeTest` runs and returns a coherent Gemini completion within the timeout.
- Confirm the live call used the **paid/no-training** Gemini tier for any non-synthetic data (per `research.md` two-tier strategy); free tier only for throwaway prompts.
- Switching `LLM_BASE_URL`/`LLM_MODEL` to a Groq endpoint + model and re-running the smoke test succeeds with no code change (optional cross-provider confidence check).

**Implementation Note**: After this phase and passing automated verification, pause for human confirmation. On completion, set `change.md` `status: implemented` and move issue #2 / the F-02 board item to Done (per `lessons.md` board-sync rule).

---

## Testing Strategy

### Unit Tests:

- `AiContent.of` / `AiDisclaimer.TEXT` labelling invariant.
- `LlmRequest.ofUser` and message mapping.
- `JsonSchemaFactory.schemaFor` for the field types the slices use; unsupported shape throws.
- Request-body builder produces correct `model` + `response_format` for each method.
- Blank `structuredModel` (the empty-string config default) resolves to `model` for the structured path.

### Integration Tests:

- `MockRestServiceServer` end-to-end through the configured `RestClient`: happy text, happy structured (typed deserialization), HTTP error → `LlmException`, malformed/empty choices → `LlmException`, timeout → `LlmException`.
- (Existing suites must stay green — F-02 adds a global `@ExceptionHandler`; confirm no regression in `AuthIntegrationTests` / `MatchApiIntegrationTests`.)

### Manual Testing Steps:

1. Export a real `LLM_API_KEY`; run the gated smoke test; confirm a coherent completion and that latency fits the timeout.
2. Confirm structured output deserializes into a sample record.
3. (Optional) Point `LLM_BASE_URL`/`LLM_MODEL` at Groq; confirm config-only provider swap works.

## Performance Considerations

The `<5s` perceived-parse NFR (`prd.md:102`) is bounded by the configured `timeout`; the structured (parse) caller in S-03 should set it tight (~4s) while game-plan generation can be more generous. Gemini's EU reachability from Frankfurt keeps RTT inside the budget (`research.md:115`). No caching/retry in MVP — a slow or failed call surfaces as `LlmException` → `503`, and the progress contract lets the UI show a non-blocking wait state.

## Migration Notes

No data migration. New env var `LLM_API_KEY` must be set in Render (and locally) before any AI slice runs; the app boots without it (non-AI slices unaffected), failing only on first LLM use. Keep `LLM_API_KEY` provider-agnostic — do not rename to a Gemini-specific key.

## References

- Internal research: `context/changes/llm-client/research.md` (provider + integration-path decision; Spring AI 2.0 pre-GA; OpenAI-compat swap seam)
- Roadmap F-02 brief: `context/foundation/roadmap.md:77-88`
- NFRs / guardrails: `context/foundation/prd.md:100-105`; `AGENTS.md` Hard Rules
- Patterns to mirror: `match/MatchController.java`, `match/MatchService.java`, `user/ApiExceptionHandler.java`, `security/SecurityConfig.java` (Jackson 3), `match/MatchApiIntegrationTests.java`
- GitHub issue #2 (Open Roadmap Question 1 — provider)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Contract, Config & Conventions

#### Automated

- [x] 1.1 Compiles: `./mvnw -q compile` — 3d3a444
- [x] 1.2 Full test suite passes: `./mvnw test` — 3d3a444
- [x] 1.3 Conventions unit-tested (`AiContent.of`, `LlmRequest.ofUser`) — 3d3a444
- [x] 1.4 No Jackson 2 imports in `llm` package — 3d3a444
- [x] 1.5 Properties bind + `llmRestClient` bean creatable — 3d3a444

#### Manual

- [x] 1.6 `llm` package reads like `user/`/`match/` — 3d3a444
- [x] 1.7 `AiDisclaimer.TEXT` satisfies the advice-labelling hard rule — 3d3a444
- [x] 1.8 `LlmProgress` doc clarifies F-02 ships vocabulary, S-02 drives it — 3d3a444

### Phase 2: OpenAI-Compatible Adapter

#### Automated

- [x] 2.1 Compiles: `./mvnw -q compile` — 32b29ab
- [x] 2.2 Full suite passes: `./mvnw test` — 32b29ab
- [x] 2.3 `generateStructured` body includes `response_format.json_schema.strict=true`

#### Manual

- [x] 2.4 Wire format isolated to the adapter only — 32b29ab
- [x] 2.5 Base-url + model swap is config-only (read-through) — 32b29ab

### Phase 3: Verification & Ops Wiring

#### Automated

- [x] 3.1 Suite passes with no key (live smoke skipped, not failed)
- [x] 3.2 Mock-transport tests cover happy/structured/error/malformed/timeout-translation + bean-timeout-wiring assertion
- [x] 3.3 `render.yaml` parses with secret `LLM_API_KEY` entry

#### Manual

- [x] 3.4 Real-key smoke test returns a coherent Gemini completion within timeout
- [x] 3.5 Real/non-synthetic data uses the paid no-training tier
- [ ] 3.6 (Optional) Groq base-url/model swap works with no code change
