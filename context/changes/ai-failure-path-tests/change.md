---
change_id: ai-failure-path-tests
title: AI failure-path tests — transient provider errors surface cleanly (Phase 2)
status: planned
created: 2026-06-06
updated: 2026-06-06
archived_at: null
---

## Notes

Phase 2 of `context/foundation/test-plan.md` (§3 Phased Rollout). Goal: prove a
transient/erroring provider surfaces a clean, retryable-signalled error — no fake
success, no infinite spin — and that AI advice-labelling holds.

- Risk covered: **#2 — Transient AI error surfaced as a dead-end** (a Gemini `503`
  shown identically to a permanent failure; player abandons an action that would
  have worked).
- Test types: **unit + integration** (stub the transport to return `503`/slow).
- Layer: backend, reusing the existing suite — no new infra.
- Provider under test: Gemini `gemini-2.5-flash` via OpenAI-compat endpoint
  (`OpenAiCompatLlmClient`), `timeout 30s` vs the `<5s` parse NFR.
- Risk Response Guidance (§2): a `503`/timeout maps to a clean, retryable-signalled
  error within the configured timeout — never a fake success, never an infinite
  spinner. Must challenge "final status was 200 ⇒ it worked" and the assumption
  that a timeout exists and fires within budget.
- **Out of scope** (§7): do NOT add retry-with-backoff — that is a separate feature
  change (`transient-llm-retry`), not a test. This phase asserts only today's
  clean-error behavior.
