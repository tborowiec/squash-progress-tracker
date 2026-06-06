# AI Failure-Path Tests (Phase 2) — Plan Brief

> Full plan: `context/changes/ai-failure-path-tests/plan.md`
> Research: `context/changes/ai-failure-path-tests/research.md`

## What & Why

Phase 2 of the test rollout protects **Risk #2 — a transient AI error surfaced as a
dead-end** (a Gemini `503` shown identically to a permanent failure, so a player abandons
an action that would have worked). We add backend tests proving a transient/erroring
provider surfaces a **clean, bounded** error — no fake success, no infinite spinner —
across both AI failure surfaces, and that the AI-advice disclaimer holds even on failure.

## Starting Point

The LLM client (`OpenAiCompatLlmClient`, Spring `RestClient`, `30s` timeout) maps provider
errors to a single unchecked `LlmException` carrying only a nullable `providerStatus`. The
parse path collapses any failure to a uniform HTTP `503` + `ApiError`; the game-plan SSE
path emits an in-stream `error` event (HTTP `200` already committed). There is **no**
transient-vs-permanent distinction in code, and the `<5s` parse NFR is unenforced (only the
`30s` client timeout exists). A `MockRestServiceServer` + `@MockitoBean LlmClient` harness is
already in place; explicit `503`, a real wall-clock timeout, and a parse-path API failure
test are the gaps.

## Desired End State

`./mvnw test` proves: a `503` yields a clean error on both surfaces (never a `200` success);
the configured read timeout fires within budget on a slow provider (no hang); the transient
signal (`providerStatus==503`) is captured at the client but absent from the API response
(the documented gap, pinned as a regression anchor); and the game-plan disclaimer is still
delivered when the LLM fails mid-stream.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Transient/permanent distinction | Does not exist today | Every `LlmException` → one generic signal per path | Research |
| Oracle handling | Assert today's contract, document the gap | Avoids the oracle trap (test-plan:70); tests stay green + meaningful | Plan |
| Timeout test | Real wall-clock via MockWebServer **+** keep cheap mapping test | Proves the `30s` timeout actually fires, plus fast unit-level mapping | Plan |
| New infra | Add `mockwebserver` (test scope), amend the "no new infra" note | Only way to exercise a genuine read timeout; honesty about the exception | Plan |
| Path scope | Both parse + game-plan | The lived incident was a game-plan `503`; enables advice-labelling assertion | Plan |
| `providerStatus` | Assert captured at client + dropped at API | Makes the gap executable; regression anchor for future `transient-llm-retry` | Plan |
| Advice-labelling | Assert the label survives a mid-stream failure | Directly proves the phase's "advice-labelling holds" goal | Plan |

## Scope

**In scope:** explicit `503` client test + captured `providerStatus`; a real wall-clock
timeout test (MockWebServer); parse-path API `503` oracle with no-retry-signal assertion;
game-plan SSE in-stream-error + disclaimer-survives oracle.

**Out of scope:** retry-with-backoff (the separate `transient-llm-retry` feature); any
production code change (`retryable` field, `Retry-After`, `<5s` deadline); live-LLM tests;
frontend error-state tests (Phase 3); game-plan quality eval.

## Architecture / Approach

Three test layers, cheapest-signal-first. Phase 1 extends `OpenAiCompatLlmClientTests`
(`MockRestServiceServer`, no new dep). Phase 2 adds the one piece of new infra (MockWebServer)
for the single test needing a real delayed response, and amends the scope note. Phase 3 adds
end-to-end API oracles via `@MockitoBean LlmClient` for both the parse `503` and the game-plan
SSE/disclaimer behavior. All tests follow existing AssertJ style, `<ClassName>Tests` naming,
and `methodUnderTest_condition_expectedOutcome` methods.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Client transient-error tests | Explicit `503` → `LlmException(providerStatus=503)`; keep timeout-mapping test | Duplicating existing 5xx coverage — mitigated by targeting 503 specifically |
| 2. Wall-clock timeout test (new infra) | MockWebServer dep + test proving the configured timeout fires within budget | Unmanaged dependency version; flaky timing if delays mis-sized |
| 3. API-layer failure oracles | Parse `503` (no retry signal) + game-plan SSE error with disclaimer intact | Asserting an absence (`providerStatus` dropped) — must revisit when retry ships |

**Prerequisites:** none — reuses the existing backend suite (Testcontainers, MockMvc,
`spring-security-test`) plus the one added test dependency.
**Estimated effort:** ~1 session across 3 phases (tests only, ~5 new methods + 1 new class + 1 dep).

## Open Risks & Assumptions

- `mockwebserver:4.12.0` is not BOM-managed → version is pinned explicitly; assumes 4.x's
  `setBodyDelay`/`enqueue` API. If the resolved API differs, the implementer adjusts within
  the Phase 2 contract.
- The "no retry signal in response" assertion is a **negative** — it must be updated (expected
  to flip red) when `transient-llm-retry` lands; that red is the intended gap-closing signal.
- The `<5s` parse NFR is treated as an observation, not a guarded bound — no test asserts it.

## Success Criteria (Summary)

- A transient `503`/slow provider never produces a fake success and never hangs — proven on
  both the parse and game-plan paths.
- The captured-but-dropped `providerStatus` gap is pinned as an executable regression anchor.
- The AI-advice disclaimer is delivered even when the game-plan LLM call fails mid-stream.
