---
date: 2026-06-03T19:04:35+02:00
researcher: Tomasz Borowiec
git_commit: fc57ebceef470b55eb8709bac87698c8cdadd5de
branch: main
repository: tborowiec/squash-progress-tracker
topic: "Which LLM API provider (and Java integration path) best fits the AI features — free option strongly preferred"
tags: [research, llm-provider, llm-client, F-02, gemini, openai, anthropic, spring-ai, free-tier, privacy]
status: complete
last_updated: 2026-06-03
last_updated_by: Tomasz Borowiec
---

# Research: Which LLM API provider (and Java integration path) best fits the AI features

**Date**: 2026-06-03T19:04:35+02:00
**Researcher**: Tomasz Borowiec
**Git Commit**: fc57ebceef470b55eb8709bac87698c8cdadd5de
**Branch**: main
**Repository**: tborowiec/squash-progress-tracker

## Research Question

Resolve **Open Roadmap Question 1** (GitHub issue #2): which AI API provider best suits this project's needs — **Anthropic, Google Gemini, or OpenAI** (plus free-friendly alternatives) — and which **integration path** (Spring AI abstraction vs a direct vendor Java SDK)? A **free option is strongly preferred**. Produce a comparison of options with strengths and weaknesses to support a decision.

> Scope agreed before research: (1) compare the three named providers **plus** free-friendly extras (Groq, Mistral, OpenRouter, local Ollama); (2) **cover both** Spring AI and direct SDKs, then recommend one; (3) **flag** free-tier data-privacy as a clearly-marked factor but don't auto-disqualify on it.

## Summary

There is **one decisive technical constraint** that the provider choice does not even touch: **the integration path**.

- **Spring Boot 4.0.6 currently has no production-GA Spring AI release.** Spring AI's GA line (1.1.x) targets Spring Boot 3.x only; Boot 4 support lives in Spring AI **2.0.x, which is still pre-GA (milestone 2.0.0-M8, 2026-05-27)** as of today. GA was scheduled for 2026-05-28 but had not shipped as of this research. So adopting Spring AI now means depending on a **pre-GA milestone**. Direct vendor Java SDKs (OpenAI, Anthropic, Google) are plain HTTP clients, **GA/stable, and independent of the Spring version** — zero Boot 4 risk.
- **Recommendation on path:** for F-02 *today*, a **thin direct-SDK adapter behind your own `LlmClient` interface** is the lower-risk choice on Boot 4.0.6. Keep watching for Spring AI 2.0 GA (imminent) — if it ships before F-02 is implemented, it becomes attractive because its `ChatClient` abstraction is exactly the provider-swap layer F-02 wants.

On the **provider**, weighing "free strongly preferred" against the project's **hard privacy guardrail** (one player's match history must never leak; AGENTS.md):

- **Google Gemini** has the most generous *permanent* free tier and excellent cheap structured-output models — but its **free tier trains on your prompts/outputs with human review and offers no opt-out**. That collides directly with storing real users' private match data. The clean reconciliation is **Gemini's paid tier**, which does *not* train and is trivially cheap at MVP volume (~$0.10–0.25 / 1M input tokens). This keeps the user's existing Gemini lean intact.
- **Groq** (free, does **not** train, fastest inference) and **Mistral** (free with ~1B tokens/month, **EU-native**, trains-by-default but **opt-out available**) are the strongest privacy-respecting free options — but both serve open-weight models whose structured-JSON reliability for parsing needs validation.
- **OpenAI** and **Anthropic** have **no permanent free tier** (only ~$5 expiring trial credit) but the **best API privacy** (no training by default). Anthropic's cheapest model (Haiku) is ~5–10× the per-token cost of Gemini/OpenAI's small tiers — weakest cost fit.

**Bottom line:** Provider-agnosticism (the `LLM_API_KEY` abstraction) is the real safeguard, because the free-tier landscape moves monthly. The pragmatic MVP path: **build a thin direct-SDK adapter; develop against a free tier using only synthetic/test data; point real user traffic at a no-training paid tier (Gemini Flash-Lite is the cheapest credible default, with Mistral/Groq as swap-in alternatives).**

## Detailed Findings

### Internal constraints (from foundation docs)

These shape what "best" means and are not negotiable inputs to the decision:

- **Stack:** Java 21 / Spring Boot 4.0.6 / Maven (`context/foundation/tech-stack.md:1-24`, `pom.xml`). JPA, Security, Flyway, Validation, Actuator already on the classpath; **no AI/LLM SDK wired yet**.
- **Two AI workloads** drive different model needs:
  - **A — structured parse** (FR-003): free text → structured JSON match record. Needs **reliable structured/JSON output** and a **<5s perceived** latency budget (`context/foundation/prd.md:102-103`). Short in/out, higher volume.
  - **B — game-plan generation** (FR-010): a few paragraphs of tactical advice. Quality matters more; latency less critical because it is **streamed with progress feedback** (`context/foundation/prd.md:102`).
- **Hard privacy guardrail:** "One player's match history is never visible to another" + "enforce the auth boundary on every data-access query" (`AGENTS.md` Hard Rules; `context/foundation/prd.md:39,104`). This is why a free tier that **trains on submitted prompts** is a real concern, not a footnote.
- **Provider-agnostic by design:** artifacts use a placeholder `LLM_API_KEY` and "the AI-provider API"; rename only once the provider is locked (`context/foundation/infrastructure.md:108,115`). F-02's own brief says build it "thin" and "minimal — just enough for the first AI slice to call" (`context/foundation/roadmap.md:79,87`).
- **Deploy region ↔ provider RTT:** the Render service region is fixed and non-edge; if it is far from the chosen provider's API region, round-trips eat the <5s parse budget (`context/foundation/infrastructure.md:76,97`). Deploy region is **EU/Frankfurt** (`infrastructure.md` — Render, Frankfurt). Favors providers with EU endpoints/residency (Mistral EU-native; Gemini via Vertex EU multi-region; OpenAI `eu.api.openai.com`).
- **Cost posture:** solo dev, 3-week after-hours MVP, low QPS, small data (`context/foundation/prd.md:8-16`), and the deployment decision was explicitly cost-minimizing (`infrastructure.md`). "Free strongly preferred" is consistent with the whole project's framing.

### Integration path (the pivot finding)

**Path 1 — Spring AI:**
- Latest **GA is 1.1.7 (2026-05-22), Spring Boot 3.x / Spring Framework 6 only** — cannot load in a Boot 4 context. [releases](https://github.com/spring-projects/spring-ai/releases)
- Boot 4.0 / Spring Framework 7 / Jakarta EE 11 support is the **Spring AI 2.0** line, with Boot 4.0 as a *hard* dependency. Latest is **2.0.0-M8 (2026-05-27), a pre-GA milestone**; GA was scheduled 2026-05-28 but not yet announced as of this research. [2.0.0-M1 blog](https://spring.io/blog/2025/12/11/spring-ai-2-0-0-M1-available-now/), [M8 blog](https://spring.io/blog/2026/05/27/spring-ai-2-0-0-M8-available-now/), [EPIC #3379](https://github.com/spring-projects/spring-ai/issues/3379)
- Value proposition (when on a supported Boot version): one `ChatClient`/`ChatModel` API across 20+ providers (OpenAI, Anthropic — now wrapping the official Java SDK in 2.0 — Vertex/Gemini, Ollama, Mistral, Groq via OpenAI-compat); structured output via `.entity(MyPojo.class)`; streaming via reactive `Flux`. This is **exactly the provider-swap layer F-02 wants**. [structured-output docs](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- No `llms.txt` published (docs `llms.txt` URL 404s).

**Path 2 — direct official vendor Java SDKs (all GA/stable, Spring-version-independent):**

| SDK | Maven coords | Status | Structured output | Streaming | Custom base-URL |
|---|---|---|---|---|---|
| OpenAI | `com.openai:openai-java` (~4.38.0) | official, active | JSON-schema → POJO via `responseFormat(Class)` | sync + async, `ChatCompletionAccumulator` | **yes** (`baseUrl()` / `OPENAI_BASE_URL`) |
| Anthropic | `com.anthropic:anthropic-java` (~2.35.x) | official, stable | schema derivation via `outputConfig(Class)` | `MessageAccumulator` | yes |
| Google Gemini/Vertex | `com.google.genai:google-genai` (~1.54+) | **GA since May 2025** | response schema | yes | Gemini-API vs Vertex switch built-in |

- **Groq / Mistral / OpenRouter are OpenAI-API-compatible** — the official `openai-java` SDK (or a plain `RestClient`/`WebClient`) works against them just by overriding the base URL (e.g. Groq `https://api.groq.com/openai/v1`). No extra SDK needed. [openai-java README](https://github.com/openai/openai-java)
- Cost of this path: you write/maintain the thin `LlmClient` interface + per-vendor adapter yourself (no unified structured-output/streaming surface across vendors). For F-02's "minimal, one caller" scope, that adapter is small.

**Trade-off:** the Boot 4.0.6 constraint is the pivot. Spring AI gives the swap abstraction for free but, today, only via a **pre-GA milestone**. Direct SDKs are all GA and Boot-agnostic but you hand-roll the (small) abstraction. **Re-check for Spring AI 2.0 GA before implementing F-02** — if it has shipped, Path 1's main objection disappears.

### Provider comparison — free tier, pricing, privacy, EU

| Provider | Permanent free tier? | Free-tier privacy | Cheapest capable paid model (in/out per 1M) | EU fit |
|---|---|---|---|---|
| **Google Gemini** | **Yes** (Flash / Flash-Lite; ~10–30 RPM, ~250–1,500 RPD; Pro removed from free Apr 2026) | ⚠️ **Trains on free-tier I/O with human review; no opt-out** | Flash-Lite ~**$0.10–0.25 / $0.40–1.50** (2.5 vs 3.1 — see recency note) | Vertex EU multi-region (current models); Frankfurt single-region lags on newest models; Developer API not EU-guaranteed |
| **OpenAI** | No (only ~$5 trial, expires) | ✅ API not trained on by default (~30-day abuse retention) | nano tier ~**$0.10–0.20 / $0.40–1.25** | `eu.api.openai.com` residency, **+10% uplift** |
| **Anthropic** | No (~$5 trial) | ✅ **Best** — API not trained on by default | Haiku ~**$1.00 / $5.00** (~5–10× the others) | EU residency option; no latency detail surfaced |
| **Groq** | **Yes** (open-weight models; ~30 RPM / 1,000+ RPD, no card) | ✅ **Does not train**; ZDR available | from ~$0.05 in; Llama 3.3 70B ~$0.59/$0.79 | GDPR DPA (EU rep in Hamburg); inference US-hosted, not EU-pinned |
| **Mistral** | **Yes** ("Experiment", ~1B tokens/mo, low RPM) | ⚠️ **Trains by default — but opt-out available** | Small ~$0.20 / $0.60 | ✅ **Best — EU-native (French), GDPR-native** |
| **OpenRouter (free models)** | Yes (`:free` IDs; 20 RPM, 50–1,000 RPD) | ⚠️ **Mixed** — OpenRouter doesn't train, but downstream free providers may; non-deterministic data location | $0 free / routes to upstream price | Routing US-based; data location non-deterministic |
| **Ollama (self-host)** | Yes (free runtime; pay for hardware/VPS) | ✅ **Best possible — 100% local** | N/A (self-run) | ✅ Perfect — runs on EU/Frankfurt VPS |

Sources: [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing), [Gemini terms (free trains)](https://ai.google.dev/gemini-api/terms), [Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits), [OpenAI pricing](https://openai.com/api/pricing/), [OpenAI EU residency](https://openai.com/index/introducing-data-residency-in-europe/), [Anthropic pricing](https://platform.claude.com/docs/en/about-claude/pricing), [Anthropic data retention](https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training), [Groq your-data](https://console.groq.com/docs/your-data), [Groq rate limits](https://console.groq.com/docs/rate-limits), [Mistral training policy](https://help.mistral.ai/en/articles/347617-do-you-use-my-user-data-to-train-your-artificial-intelligence-models), [Mistral tiers](https://docs.mistral.ai/admin/user-management-finops/tier), [OpenRouter privacy](https://openrouter.ai/docs/guides/privacy/data-collection), [OpenRouter limits](https://openrouter.ai/docs/api/reference/limits), [Ollama](https://github.com/ollama/ollama).

Disqualified-for-MVP free options: **Cohere** trial forbids production/commercial use; **Together** offers only expiring trial credit.

### Model fit for the two workloads

- **Workload A (fast/cheap structured parse):** cheapest credible options with *native, reliable* structured-output guarantees are **Gemini Flash-Lite** (97% schema-compliance reputation, sub-second–~1.8s p95, EU multi-region) and **OpenAI nano tier** (explicitly recommended for extraction/classification). **Anthropic Haiku** works but is ~5–10× the cost and least suited to high volume (prompt caching mitigates if the schema prompt is static). Groq/Mistral open models are fastest/free but JSON reliability must be validated. [Gemini Flash-Lite GA](https://cloud.google.com/blog/products/ai-machine-learning/gemini-3-1-flash-lite-is-now-generally-available), [OpenAI nano](https://openai.com/index/introducing-gpt-5-4-mini-and-nano/)
- **Workload B (game-plan quality):** mid-tier models — Gemini Flash (3-flash cheaper, 3.5-flash higher quality), OpenAI mini tier, or Claude Sonnet (strongest prose). Latency non-critical (streamed).
- **One-provider simplicity:** Gemini Flash-Lite for A + a Gemini Flash for B = one provider, one key, one SDK, free-tier dev path — consistent with the user's existing lean.

## Code References

- `pom.xml` — Spring Boot 4.0.6 starters present (web, security, data-jpa, flyway, validation, actuator); **no AI/LLM dependency** to extend.
- `context/foundation/roadmap.md:77-88` — F-02 brief: thin, provider-agnostic, minimal client behind `LLM_API_KEY`; blocked on this provider decision.
- `context/foundation/roadmap.md:154-156` — Open Roadmap Question 1 (this research's subject).
- `context/foundation/prd.md:100-105` — NFRs: visible progress, <5s perceived parse, no cross-player access, desktop browsers.
- `context/foundation/infrastructure.md:76,97,108,115` — region↔provider RTT risk; `LLM_API_KEY` placeholder; rename-on-decision.
- `AGENTS.md` (Hard Rules) — privacy guardrail + "label every AI game plan as AI-generated advice".

## Architecture Insights

- **The abstraction is the hedge, not the provider.** Because free tiers, prices, and model IDs shift monthly (Google cut free limits 50–80% in Dec 2025; model lineups turned over multiple times in H1 2026), F-02's value is the swap layer behind `LLM_API_KEY`. Pick a default provider, but make switching a config + adapter change, not a rewrite. This is consistent with the roadmap's provider-agnostic framing.
- **Two-tier provider strategy resolves the free-vs-privacy tension cleanly:** develop and demo against a free tier using only **synthetic/test data**; route **real user match data** to a **no-training tier** (paid Gemini/OpenAI/Anthropic, or Groq/Mistral-with-opt-out). At MVP volume the paid cost is cents.
- **OpenAI-compatibility is leverage:** standardizing the adapter on the OpenAI wire format makes Groq, Mistral, and OpenRouter swap-in via base-URL override — broadening the swap set for free.
- **EU region alignment matters for the <5s NFR:** Mistral (EU-native), Gemini Vertex EU multi-region, and OpenAI `eu.api.openai.com` all keep RTT and data inside the EU from the Frankfurt backend; this both protects the latency budget and simplifies GDPR.

## Historical Context (from prior changes)

- `MEMORY.md` → `ai-provider-deferred.md` — provider was deliberately deferred; **user leaning Gemini**; keep artifacts provider-agnostic under `LLM_API_KEY`. This research confirms Gemini is a strong cost/capability choice **and** surfaces the free-tier training caveat the lean should account for.
- `context/foundation/infrastructure.md` (2026-05-30) — deployment chosen as Render/Frankfurt under a cost-minimizing lens; explicitly left the LLM provider out of scope but carried forward the region-RTT and key-rotation constraints reused here.
- `context/foundation/lessons.md` — no LLM-specific lesson yet; existing lessons (Docker build context, project-board sync) are orthogonal to this decision.

## Related Research

- None prior on the LLM provider. This is the first `research.md` for change `llm-client` (F-02). Deployment-side research lives in `context/foundation/infrastructure.md`.

## Open Questions

1. **Has Spring AI 2.0 reached GA?** Scheduled 2026-05-28, only M8 seen. **Re-check at F-02 plan time** — a GA release flips the path recommendation toward Spring AI.
2. **Do Groq/Mistral open-weight models hit the ≥90% parse-accuracy secondary success criterion** (`prd.md:34`) for workload A with strict JSON output? Needs a quick spike before relying on them as the primary provider.
3. **Confirm current Gemini Flash-Lite model ID + price** at implementation (2.5 vs 3.1 Flash-Lite seen at $0.10/$0.40 vs $0.25/$1.50) — both trivially cheap, but lock the exact ID.
4. **Decision still owned by user:** does the free-tier convenience of Gemini justify the data-training caveat for *dev only*, with paid/no-training for real data — or prefer a provider that never trains (Groq, or Gemini paid from day one)?

## Recommendation (decision-ready)

- **Integration path:** **direct official Java SDK behind a thin `LlmClient` interface** for F-02 today (Boot 4.0.6 has no GA Spring AI). Revisit Spring AI 2.0 if it GAs before implementation.
- **Provider default:** **Google Gemini Flash-Lite** (cheapest credible structured output, free dev tier, matches the existing lean) — but **use the paid/no-training tier for real user data**, since the free tier trains on prompts with no opt-out.
- **Strong privacy-first alternatives kept swap-in:** **Mistral** (EU-native, free with opt-out) and **Groq** (free, never trains). Standardize the adapter on the OpenAI wire format so all three remain interchangeable via config.
