---
change_id: frontend-runner-bootstrap-tests
title: Frontend runner bootstrap + route-guard & api-client contract tests (test-plan Phase 3)
status: implementing
created: 2026-06-06
updated: 2026-06-06
archived_at: null
---

## Notes

Phase 3 of the phased test rollout in `context/foundation/test-plan.md` (§3).

Goal: stand up the frontend test runner (none today — 0 test files, no runner), then
prove the two frontend-cluster risks at the cheapest layer:

- **#4 Frontend route-guard regression** — unauthenticated render of a protected route
  redirects to login; a valid session renders the route. Cheapest layer: Vitest + Testing
  Library + router memory history (component test). Defence-in-depth + UX, not the real
  boundary (server-side).
- **#5 Frontend ↔ backend contract drift** — the api-client and backend DTOs agree on
  field names, score shape, and error-body shape for match create/list/error. Cheapest
  layer: a frontend api-client test against a recorded fixture (+ backend response-shape
  assertion already in scope elsewhere).

Stack to bootstrap (§4): Vitest + Testing Library for unit/component; api-client contract
test against a recorded fixture. Watch the anti-patterns in §2 Risk Response Guidance
(don't mock the guard you're testing; don't re-assert the client's own TS types).
