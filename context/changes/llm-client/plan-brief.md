# Provider-Agnostic LLM Client (F-02) — Plan Brief

> Full plan: `context/changes/llm-client/plan.md`
> Research: `context/changes/llm-client/research.md`

## What & Why

Build the thin `LlmClient` enabler that both AI features call: S-02 (AI game plan, the north star) and S-03 (AI match entry). One interface wraps an OpenAI-compatible `/chat/completions` endpoint via Spring `RestClient`, defaulting to Google Gemini Flash-Lite. Building it once avoids duplicating provider wiring, advice-labelling, and progress plumbing across two slices.

## Starting Point

Spring Boot 4.0.6 app with auth + match logging already built (`user/`, `match/` packages). No AI/LLM dependency wired. Clean conventions to mirror: constructor injection, DTO records with `from()` factories, `CurrentUser` auth boundary, a `@RestControllerAdvice` error funnel, env-var config, and Jackson 3 (`tools.jackson.*`).

## Desired End State

An injectable `LlmClient` bean offering `generate` (text) and `generateStructured` (typed JSON via `response_format: json_schema`), bounded by a configurable timeout, with all failures funneled to a `503`. A server-side advice-disclaimer envelope and a progress-state vocabulary exist for the AI slices to reuse. Build is green on mocked transport; a developer with a real key can run one gated live smoke test against Gemini.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Integration path | Direct HTTP via Spring `RestClient` (no SDK, no Spring AI) | Spring AI 2.0 still pre-GA on Boot 4 (re-verified: 2.0.0-M8); `RestClient` is Boot-native and adds no dependency | Research / Plan |
| Provider default | Gemini Flash-Lite via OpenAI-compat endpoint | Cheapest credible structured output, matches your lean; free tier for synthetic dev data, paid no-training tier for real data | Research / Plan |
| Model ID | Pin GA `gemini-2.5-flash-lite` (not the `-latest` alias) | Stable/reproducible + cheapest ($0.10/$0.40); `gemini-3.1-flash-lite-preview` is an env-swap upgrade when it GAs | Plan |
| Wire format | OpenAI-compatible | Makes Gemini/Groq/Mistral a `base-url` + `model` config swap, never a code change | Research |
| Client surface | `generate` + `generateStructured` (typed) | Give both AI slices a complete call contract up front | Plan |
| Progress plumbing | Convention now, sync client; SSE deferred to S-02 | Ship the shared progress vocabulary without over-building transport ahead of a UI | Plan |
| Advice labelling | Server-side disclaimer constant + response envelope | Enforces the AGENTS.md hard rule at the source both slices reuse | Plan |
| Verification | Mocked HTTP transport + key-gated live smoke test | CI-safe and deterministic, real call still verifiable on demand | Plan |

## Scope

**In scope:** `llm` package (interface, DTOs, exception, properties, configured `RestClient` bean); `OpenAiCompatLlmClient` adapter (text + structured); advice-labelling + progress conventions; `LlmException → 503` mapping; mock + gated-live tests; env wiring (`application.properties`, `render.yaml`).

**Out of scope:** AI endpoints/prompts/UI (S-02/S-03); streaming/SSE transport; retries/circuit-breaker/caching; persistence of prompts; live LLM calls in CI; renaming `LLM_API_KEY`.

## Architecture / Approach

`LlmClient` interface → `OpenAiCompatLlmClient` (the only file that knows the wire format) → configured `llmRestClient` bean → OpenAI-compatible endpoint. Provider selection is pure config (`llm.base-url`, `llm.model`). Failures collapse to `LlmException`, mapped to a `503` by the existing advice. Two conventions (`AiContent`/`AiDisclaimer`, `LlmProgress`) sit beside the client for the slices to consume.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Contract, config & conventions | Interface, DTOs, exception, properties, `RestClient` bean, advice-label + progress conventions, error mapping | Over-designing the conventions ahead of a real caller |
| 2. OpenAI-compat adapter | `generate` + `generateStructured` with `response_format` json_schema, timeout, error translation | JSON-Schema derivation from a `Class<T>` proving fiddly |
| 3. Verification & ops wiring | Mock-transport tests, gated live smoke, env/`render.yaml` wiring | Mock drifting from the real API contract (offset by the smoke test) |

**Prerequisites:** None (parallel with F-01/S-01). A real `LLM_API_KEY` is needed only for the Phase 3 live smoke test and any AI slice.
**Estimated effort:** ~1–2 sessions across 3 phases; no new dependency, small surface.

## Open Risks & Assumptions

- Gemini's free tier trains on prompts with no opt-out — real user data MUST go to the paid no-training tier; free tier is dev/synthetic only.
- Model ID is decided: GA `gemini-2.5-flash-lite` is pinned (resolves `research.md:131`); `gemini-3.1-flash-lite-preview` is a documented env override (faster but Preview + ~2.5–3.75× pricier) for later. No alias as the default — aliases re-point silently.
- The single per-bean timeout can't yet be overridden per-call; S-03's tight (~4s) parse budget is handed forward as a known limitation.
- Open-weight swap providers (Groq/Mistral) need a JSON-parse-reliability spike before becoming primary (`research.md` Open Q2) — Gemini is the locked default, so this isn't blocking.

## Success Criteria (Summary)

- `./mvnw test` is green with no key present (live smoke test skips, not fails).
- An injected `LlmClient` returns coherent text and a typed structured object from a real Gemini call.
- Swapping provider is a `base-url` + `model` config change with zero code edits.
