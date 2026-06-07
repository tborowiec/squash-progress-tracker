# Frontend Runner Bootstrap + Route-Guard & API-Client Contract Tests — Plan Brief

> Full plan: `context/changes/frontend-runner-bootstrap-tests/plan.md`
> Research: `context/changes/frontend-runner-bootstrap-tests/research.md`

## What & Why

Phase 3 of the phased test rollout. The frontend SPA has **zero tests and no runner today** —
its hottest-churn, zero-coverage area. We stand up a Vitest + Testing Library runner and use it to
prove the two frontend-cluster risks at the cheapest layer: the **route guard** behaves correctly
(#4) and the **api-client matches the backend contract** (#5).

## Starting Point

`frontend/` runs on Vite 6 + React 18.3 with Biome for lint/format and a lefthook pre-commit gate,
but no `test` script and none of vitest/Testing Library/jsdom installed. `ProtectedRoute` is a clean
three-state guard (`loading→null`, `!user→<Navigate to="/login">`, else `<Outlet/>`); the api-client
does no response unwrapping or error decoding — every call site does `.then(r => r.data)`.

## Desired End State

`cd frontend && npm run test:run` exits green with three files — a runner smoke test, a route-guard
test pinning all three states plus a router-level redirect, and an api-client contract test pinning
create/list success shapes and one `ApiError`/503 error body. `npm run typecheck` and `biome check`
stay clean, and the test-plan cookbook (§6.3, §6.5) is filled in with the shipped patterns.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Runner + config | Vitest 3 + RTL 16 + jsdom, inline `test` block in `vite.config.ts` | Least disruption; jsdom gives broader API fidelity | Research |
| Type resolution | Triple-slash refs in setup file, not `tsconfig types:[]` | Keeps `@types/react` auto-included under the `tsc --noEmit` gate | Research |
| #4 test surface | Three states + one router redirect | Pins the core ordering property AND proves `<Navigate>` wires through | Plan |
| #5 fixture sourcing | Hand-written from DTO shapes, backend test as oracle | No live-backend dependency in the bootstrap; deterministic | Plan |
| #5 error scope | Success + one `ApiError`/503 assertion | Guards the declared-but-unused `ApiError` shape as a drift tripwire | Plan |
| Transport stub | `vi.mock('./client')` | Zero new deps; native to Vitest; fine for decode-shape testing | Plan |
| Biome on tests | Add `overrides` to silence `noNonNullAssertion` on `*.test.tsx` | Clean lint output in tests for a non-blocking advisory rule | Plan |

## Scope

**In scope:** runner bootstrap (deps, config, setup, scripts, type resolution, Biome override,
smoke test); route-guard component test (#4); api-client contract test (#5); cookbook §6.3/§6.5.

**Out of scope:** SSE game-plan/`EventSource` tests; the destination-not-preserved redirect fix;
503 retry/backoff; CI wiring & post-edit hooks (Phase 4); E2E/container smoke (Phase 5); live-backend
fixture recording; MSW/axios-mock-adapter; exhaustive error-variant matrix.

## Architecture / Approach

Three additive phases. **Phase 1** installs the toolchain and validates the typecheck + Biome gates
against a trivial smoke test before real tests depend on them. **Phase 2** tests the real
`ProtectedRoute` under a `MemoryRouter` — `useAuth` mocked for the three pure states, the real
`AuthProvider` with a never-resolving `me()` for the in-flight case — never mocking the guard itself.
**Phase 3** stubs the axios singleton with `vi.mock` and asserts the api functions decode hand-written
fixtures into the expected runtime shape, including one error body.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Runner bootstrap | Working Vitest runner, green smoke test, gates pass | Vitest/jest-dom types not resolving under `tsc --noEmit` |
| 2. Route-guard tests (#4) | Three-state guard + in-flight case + router redirect | Mocking too much (the guard) or missing the loading-before-unauth ordering |
| 3. Contract tests (#5) | Create/list decode-shape + one `ApiError`/503 body | Re-asserting TS types instead of the backend's real JSON |

**Prerequisites:** `frontend/` builds today; `npm` available; `.gitignore` already covers
`node_modules/` (satisfied). No backend or DB needed.
**Estimated effort:** ~1–2 sessions across 3 phases (small, well-grounded by research).

## Open Risks & Assumptions

- Hand-written fixtures can drift from backend DTOs if they change — mitigated by citing the backend
  integration test as oracle and the typecheck gate; a recorded fixture was deliberately rejected to
  keep the bootstrap infra-free.
- The in-flight (never-resolving promise) case must actually fail when the guard ordering is reversed
  — manual verification step confirms the canary works.
- `jest-dom`/vitest type resolution via triple-slash refs must hold under the lefthook
  `frontend-typecheck` gate (validated in Phase 1 before real tests land).

## Success Criteria (Summary)

- `npm run test:run` is green with all three test files; `typecheck` and `biome check` clean.
- The guard test fails if the `loading`-before-`!user` ordering is reversed (canary proven).
- The contract test fails if a backend field is renamed (pins real JSON, not TS types).
