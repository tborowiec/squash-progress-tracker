<!-- PLAN-REVIEW-REPORT -->
# Plan Review: AI Game Plan for an Opponent (S-02)

- **Plan**: context/changes/ai-game-plan/plan.md
- **Mode**: Deep
- **Date**: 2026-06-04
- **Verdict**: REVISE → SOUND (all findings fixed in plan)
- **Findings**: 1 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | FAIL → fixed (F1) |
| Blind Spots | WARNING → fixed (F2, F4) |
| Plan Completeness | WARNING → fixed (F3) |

## Grounding

10/10 paths ✓, symbols ✓ (`CurrentUser.currentUserId()`, `findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc`, `LlmException → 503` at `ApiExceptionHandler.java:23-27`), brief↔plan ✓. Blast-radius: `LlmClient` has a single concrete impl (`OpenAiCompatLlmClient`) and no test fakes — adding `generateStreaming` is contained.

## Findings

### F1 — Async SSE worker loses SecurityContext and pins a DB transaction across the whole LLM stream

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 — change 1 (GamePlanService) & change 3 (controller flow)
- **Detail**: `CurrentUser.currentUserId()` reads thread-local `SecurityContextHolder` (`CurrentUser.java:19`), which does not propagate to the `SseEmitter` worker thread → `IllegalStateException` / 500 if called there. A `@Transactional` load+stream method would pin a pooled DB connection for the entire multi-second LLM stream. The plan's change-1 (one load+stream method) and change-3 (load synchronously before the emitter) contradicted on threading and neither resolved what runs on the worker.
- **Fix**: Split the service — `prepare(opponentName) → GamePlanContext{request, matchCount, lowData}` does all security/DB/prompt work on the request thread in a short read-only tx (throws `GamePlanUnavailableException` → 404 when empty); the worker only calls `generateStreaming(ctx.request(), onToken)` — no `CurrentUser`, repo, or tx. Also documented in Critical Implementation Details ("Request-thread vs SSE-worker boundary").
- **Decision**: FIXED (Fix in plan)

### F2 — "Streaming" is the one new surface, but no automated test can prove incremental delivery

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 — Success Criteria
- **Detail**: `MockRestServiceServer` (bound to the RestClient builder, `OpenAiCompatLlmClientTests.java:39`) replaces the request factory and serves the `text/event-stream` body from an in-memory buffer, so the Phase-1 test proves SSE parsing + `onToken` ordering only — the increments are an artifact of splitting a complete buffer. Real transport (`SimpleClientHttpRequestFactory`, `LlmClientConfig.java:17`) is exercised only by the gated live smoke (skips without a key). Success criteria read as if streaming itself were CI-proven.
- **Fix**: Relabelled the mock test as a parsing test, stated incremental delivery is verified only by the gated live smoke, and added an optional parser-unit test (package-private static parser over a chunked Reader asserting `onToken` fires before EOF). Parser made package-private static for testability.
- **Decision**: FIXED (Fix in plan)

### F3 — MockMvc async SSE test has zero precedent and hidden requirements

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — Success Criteria
- **Detail**: No test uses `SseEmitter`/`asyncDispatch`/`getAsyncResult` (grep: zero hits). The plan asked for a MockMvc async SSE test but omitted: (a) the mocked `generateStreaming` must invoke its `Consumer<String>` synchronously on the calling thread or the async result never completes (hang); (b) assert via `getResponse().getContentAsString()` after `asyncDispatch(mvcResult)`, not `getAsyncResult()`.
- **Fix**: Added an explicit test recipe to Phase 2 success criteria (synchronous `doAnswer` stub, `request().asyncStarted()` → `asyncDispatch`, assert on `getContentAsString()` + `text/event-stream`) for both happy and error paths.
- **Decision**: FIXED (Fix in plan)

### F4 — EventSource auto-reconnect can silently re-trigger a full (paid) generation

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 3 — change 1 (EventSource client) & Performance
- **Detail**: Native `EventSource` auto-reconnects on any close that isn't an explicit `.close()`. The plan closed on named `done`/`error` events (normal path fine) but not on native transport drops (no payload) — a reopened GET re-runs a brand-new full LLM generation (cost + interleaving). Performance section noted only the upside.
- **Fix**: Specified `es.close()` in all terminal paths including native `es.onerror`, and added a cost-per-generation note to Performance.
- **Decision**: FIXED (Fix in plan)
