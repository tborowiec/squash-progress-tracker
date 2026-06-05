---
change_id: ownership-boundary-tests
title: Ownership-boundary & no-mis-save backend tests (test-plan Phase 1)
status: implemented
created: 2026-06-05
updated: 2026-06-05
archived_at: null
---

## Notes

Phase 1 of the phased test rollout (`context/foundation/test-plan.md` §3). Backend integration tests.

**Goal:** Prove cross-player access is rejected on every match endpoint and that confirmed == saved.

**Risks covered:**
- #1 Cross-player match access (IDOR) — Player A's token requesting/editing/deleting Player B's match id must be rejected (404/403) on **every** match endpoint, including list/filter.
- #3 Silent mis-save from AI parse — the persisted record byte-equals the confirmed preview; no save path bypasses the confirm gate.

**Layer:** Backend integration — MockMvc + `spring-security-test` + Testcontainers (PostgreSQL). Reuses the existing backend suite; no new infra.

**Anti-patterns to avoid:** testing only the happy owner path / asserting `200` instead of cross-tenant `404`/`403`; assertions copied from the parser's own output (tautological).

GitHub issue: #11. Plan must end with a sub-phase updating test-plan §6.2 / §6.4 cookbook entries.
