# Frontend Runner Bootstrap + Route-Guard & API-Client Contract Tests — Implementation Plan

## Overview

Phase 3 of the phased test rollout (`context/foundation/test-plan.md` §3). The frontend SPA has
**zero test files and no runner today**. This plan stands up the frontend test runner
(Vitest 3 + Testing Library) and then uses it to prove the two frontend-cluster risks at the
cheapest layer:

- **#4 Route-guard regression** — an unauthenticated render of a protected route redirects to
  login; a valid session renders the route; a still-checking (in-flight) session is NOT bounced.
- **#5 Frontend ↔ backend contract drift** — the api-client and backend DTOs agree on field
  names, score shape, and error-body shape for match create/list/error.

## Current State Analysis

- **No runner, no test deps.** `frontend/package.json` has `dev`/`build`/`preview`/`lint`/
  `format`/`typecheck` scripts but no `test` script. None of vitest, `@testing-library/*`,
  jsdom are installed. Installed: vite 6, react 18.3.1, `@vitejs/plugin-react` 4, typescript 5,
  Biome 2.4.16.
- **`ProtectedRoute` is a clean three-state layout guard** (`frontend/src/components/ProtectedRoute.tsx:4-9`):
  `if (loading) return null` → `if (!user) return <Navigate to="/login" replace />` → `return <Outlet />`.
  **`loading` is checked before `!user`** — the core correctness property. The redirect carries
  no `state={{from}}` (destination is not preserved — current behavior to assert, not fix).
- **`AuthContext`** (`frontend/src/contexts/AuthContext.tsx:12-24`): `{user, loading, setUser}`,
  no `isAuthenticated`. On mount a `useEffect` calls `me()` (GET `/api/auth/me`):
  success→`setUser`, failure→`setUser(null)`, `finally`→`setLoading(false)`. Startup sequence
  `{null, loading:true}` → `{user|null, loading:false}`. `useAuth` throws outside `AuthProvider`.
- **Router topology** (`frontend/src/App.tsx`, `frontend/src/main.tsx:14`): `BrowserRouter` lives
  in `main.tsx` wrapping `<App/>`, so `App` itself holds only `<AuthProvider><Routes>…`. Tests can
  mount under a `MemoryRouter`. Protected routes (`/`, `/matches/new`, `/matches/:id/edit`,
  `/history`, `/game-plan`) are children of `<Route element={<ProtectedRoute/>}>`; public: `/login`,
  `/register`; catch-all `*` → `/`.
- **api-client** (`frontend/src/api/client.ts`): module-level axios singleton, `baseURL: ''`,
  `withCredentials: true`, a request interceptor that attaches `X-XSRF-TOKEN` on mutating methods
  and a one-time CSRF bootstrap GET before the first mutation. **No response unwrapping, no error
  normalization** — every call site does `.then(r => r.data)`; axios errors propagate raw.
- **api/matches.ts** (verified field-for-field against backend DTOs): request `SetScoreRequest
  {playerScore, opponentScore}`; response `SetScoreResponse {setNumber, playerScore, opponentScore}`;
  `MatchResponse {id, opponentName, matchDate, notes?, sets[], setsWon, setsLost, result:'WON'|'LOST'|'DRAW'}`.
  Score shape is a **list of per-set integer pairs**, not a string or single int.
- **api/auth.ts** declares `ApiError {status, message, fieldErrors}` but **nothing decodes it**.
- **Two integration gates run on new test files** (`lefthook.yml:22-35`): `frontend-typecheck`
  runs `tsc --noEmit` over all of `src/` (test files MUST typecheck — vitest globals + jest-dom
  matcher types must resolve), and Biome lints them (`noNonNullAssertion` advisory, won't block).
- `.gitignore` already covers `frontend/node_modules/` + `frontend/dist/` (node_modules lesson
  already satisfied).

## Desired End State

`cd frontend && npm run test:run` exits green with three test files: a runner smoke test, the
route-guard test (`ProtectedRoute.test.tsx`), and the api-client contract test
(`matches.contract.test.tsx`). `npm run typecheck` stays green (vitest + jest-dom types resolve).
`npx biome check src` reports no errors. The route-guard test pins all three guard states plus one
router-level redirect; the contract test pins create/list success shapes against hand-written
fixtures and one `ApiError`/503 error-body assertion. The test-plan cookbook §6.3 and §6.5 are
filled in.

### Key Discoveries:

- The guard's correctness hinges on **check ordering** — `loading` before `!user`. The test must
  pin both directions, including the never-resolving (`me()` hangs) loading case
  (`frontend/src/components/ProtectedRoute.tsx:6-7`).
- The frontend **trusts the backend contract implicitly** (no error decoding), so the contract
  test's value is pinning the backend's *real* JSON against the client's decode expectations — NOT
  re-asserting the client's own TS types (the named anti-pattern for #5, test-plan.md §2 row #5).
- `tsconfig.json` has `types` unset (auto-includes all `@types/*`); resolve vitest globals +
  jest-dom matcher types via triple-slash references in the setup file, **not** by setting
  `types: [...]` (which would disable auto-inclusion of `@types/react`).
- Phase 2 (`context/changes/ai-failure-path-tests/`) established the **503 error-body oracle**:
  `{status:503, message:"AI service is temporarily unavailable"}`. Reuse this exact body as the
  frontend contract fixture for the error case.

## What We're NOT Doing

- **Not** testing the SSE game-plan path (`gameplans.ts`, `EventSource`) — jsdom has no
  `EventSource`, and the SSE `{message}`-only error shape belongs with a #2 follow-up, not Phase 3.
- **Not** fixing the destination-not-preserved redirect gap (no `state={{from}}`) — asserted as
  current behavior, not changed here.
- **Not** adding retry/backoff for transient 503s (test-plan §7 — a separate feature change).
- **Not** wiring CI or a post-edit hook for the frontend suite (Phase 4 / Module 3 Lesson 3).
- **Not** writing E2E/Playwright or container-smoke tests (Phase 5 / Module 3 Lesson 4).
- **Not** recording fixtures from a live backend — fixtures are hand-written from verified DTO
  shapes with the backend integration tests cited as oracle.
- **Not** standing up MSW or axios-mock-adapter — the transport is stubbed with Vitest's `vi.mock`.
- **Not** exhaustively pinning every backend error variant (400/401/404/409) — only success +
  one `ApiError`/503 assertion.

## Implementation Approach

Three phases, each independently verifiable: (1) bootstrap the runner and prove it runs green with
a trivial smoke test before any real test logic depends on it; (2) the route-guard component test
(#4); (3) the api-client contract test (#5). Sequencing the bootstrap first means the typecheck and
Biome gates are validated against a minimal file before the real tests are layered on, so any
type/lint surprise surfaces cheaply.

## Critical Implementation Details

- **Type resolution under the `tsc --noEmit` gate.** Do NOT add `types: [...]` to `tsconfig.json`
  — that disables auto-inclusion of `@types/react`. Instead, the setup file (`src/test/setup.ts`)
  carries triple-slash references (`/// <reference types="vitest/globals" />` and
  `/// <reference types="@testing-library/jest-dom" />`) and `import '@testing-library/jest-dom/vitest'`.
  The `vite.config.ts` gets `/// <reference types="vitest/config" />` at the top so the `test` block
  typechecks. `isolatedModules: true` is set — use `import type` for type-only imports.
- **The in-flight (loading) case requires a never-resolving promise.** To prove a still-checking
  session is not bounced, the real-`AuthProvider` variant must mock `me()` to return a promise that
  never settles; asserting `loading` stays true and no `<Navigate>` fires. A resolved/rejected mock
  only covers the terminal states.
- **`AuthProvider` fires a real GET on mount.** Any test that uses the real provider must mock
  `../api/auth` (or `../api/client`) so no network call escapes jsdom. The pure three-state logic is
  better tested by mocking `useAuth` directly to return each state.

## Phase 1: Runner Bootstrap

### Overview

Install the test toolchain, configure Vitest inline in `vite.config.ts`, add the setup file and
test scripts, resolve types under the typecheck gate, add the Biome override, and prove the runner
runs green with one trivial smoke test.

### Changes Required:

#### 1. Test dependencies

**File**: `frontend/package.json`

**Intent**: Add the Vitest + Testing Library toolchain as devDependencies and the `test` /
`test:run` scripts so the runner exists and hooks/CI can invoke a non-watch run.

**Contract**: devDeps — `vitest@^3.2`, `@testing-library/react@^16.1`,
`@testing-library/dom@^10.4` (explicit RTL-16 peer), `@testing-library/jest-dom@^6.6`,
`@testing-library/user-event@^14.5`, `jsdom@^25`. Scripts — `"test": "vitest"`,
`"test:run": "vitest run"`. Install via `npm install` in `frontend/` (writes `package-lock.json`).

#### 2. Vitest config (inline)

**File**: `frontend/vite.config.ts`

**Intent**: Add an inline `test` block to the existing config (least disruption — no separate
`vitest.config.ts`). Leave `build.outDir` and `server.proxy` untouched.

**Contract**: Triple-slash `/// <reference types="vitest/config" />` at the top of the file;
`test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'] }`.

#### 3. Test setup file

**File**: `frontend/src/test/setup.ts` (new)

**Intent**: Register jest-dom matchers and pull in the global + matcher types so test files
typecheck under `tsc --noEmit` without touching `tsconfig.json`'s `types`.

**Contract**: `/// <reference types="vitest/globals" />` and
`/// <reference types="@testing-library/jest-dom" />` at the top; body is
`import '@testing-library/jest-dom/vitest'`.

#### 4. Biome override for test files

**File**: `frontend/biome.json`

**Intent**: Silence the advisory `noNonNullAssertion` rule on test files so test code that uses
non-null assertions produces clean lint output.

**Contract**: Add an `overrides` entry matching `**/*.test.tsx` (and `**/*.test.ts`) that disables
`suspicious.noNonNullAssertion` (set to `off`). Existing `src/**` linter config otherwise unchanged.

#### 5. Runner smoke test

**File**: `frontend/src/test/smoke.test.ts` (new)

**Intent**: A trivial always-green test that proves the runner, jsdom environment, globals, and
jest-dom matchers all resolve before any real test depends on them.

**Contract**: One `describe`/`it` asserting a basic truth and one jest-dom matcher against a
DOM node, using global `expect`/`it` (no imports needed beyond the test body). Removable once
Phases 2–3 land, or kept as a runner canary.

### Success Criteria:

#### Automated Verification:

- [ ] Deps install cleanly: `cd frontend && npm install`
- [ ] Runner runs green: `cd frontend && npm run test:run`
- [ ] Typecheck passes (vitest + jest-dom types resolve): `cd frontend && npm run typecheck`
- [ ] Biome reports no errors: `cd frontend && npx biome check src`

#### Manual Verification:

- [ ] `npm run test` (watch mode) starts and re-runs on file change, then exits cleanly with `q`
- [ ] `git status` shows `package-lock.json` updated and `node_modules/` still ignored

**Implementation Note**: After completing this phase and all automated verification passes, pause
for manual confirmation before proceeding to Phase 2.

---

## Phase 2: Route-Guard Tests (#4)

### Overview

Prove `ProtectedRoute` redirects an unauthenticated render to `/login`, renders the route for a
valid session, and does NOT bounce a still-checking session — plus one thin router-level redirect
assertion. Do not mock the guard itself.

### Changes Required:

#### 1. ProtectedRoute three-state test

**File**: `frontend/src/components/ProtectedRoute.test.tsx` (new)

**Intent**: Pin the guard's three states and the loading-before-unauthenticated ordering — the
core correctness property — by mounting the real `ProtectedRoute` under a `MemoryRouter` with the
auth state controlled per case.

**Contract**: Three cases via mocked `useAuth` (the pure-logic states): `{loading:true}` →
renders nothing (no redirect, no Outlet content); `{loading:false, user:null}` → redirects to
`/login`; `{loading:false, user:{…}}` → renders the protected `<Outlet/>` content. A fourth case
uses the **real `AuthProvider`** with `me()` mocked to a **never-resolving promise** to prove the
in-flight session stays on `loading` and is not bounced. Mount with a `MemoryRouter` whose routes
include a `<Route element={<ProtectedRoute/>}>` wrapping a sentinel protected element, plus a
`/login` sentinel, so the redirect destination is observable. Assert the redirect lands on the
`/login` sentinel (current behavior: no `from` state preserved).

#### 2. Router-level redirect assertion

**File**: `frontend/src/components/ProtectedRoute.test.tsx` (same file)

**Intent**: One thin integration assertion that an anonymous user hitting a protected path is
funneled to `/login` through real router config — proving `<Navigate>` wires through, not just the
component-in-isolation return value.

**Contract**: Mount a small `MemoryRouter` with `initialEntries={['/history']}` (a real protected
path) and the protected-layout route table, with auth state unauthenticated; assert the `/login`
sentinel renders. Keep it to a single path — breadth belongs to the component cases above.

### Success Criteria:

#### Automated Verification:

- [ ] Guard tests pass: `cd frontend && npm run test:run`
- [ ] Typecheck passes: `cd frontend && npm run typecheck`
- [ ] Biome reports no errors: `cd frontend && npx biome check src`

#### Manual Verification:

- [ ] Temporarily reorder the guard (`!user` before `loading`) and confirm the in-flight case
  test FAILS — proving the test actually pins the ordering, not just the terminal states
- [ ] Confirm the guard itself is not mocked (only `useAuth` / `me` are)

**Implementation Note**: After completing this phase and all automated verification passes, pause
for manual confirmation before proceeding to Phase 3.

---

## Phase 3: API-Client Contract Tests (#5)

### Overview

Pin the api-client ↔ backend contract for match create/list (field names, per-set integer-pair
score shape) against hand-written fixtures, plus one `ApiError`/503 error-body assertion. Stub the
transport with `vi.mock`; cite the backend integration tests as the fixture oracle.

### Changes Required:

#### 1. Hand-written contract fixtures

**File**: `frontend/src/api/__fixtures__/match-contract.ts` (new; co-located fixture module)

**Intent**: Freeze the backend's real JSON shapes as typed fixtures sourced from the verified DTO
shapes, with the backend integration test cited as the oracle of record — no live-backend
dependency.

**Contract**: Export a `MatchResponse`-shaped success object (`{id, opponentName, matchDate,
notes, sets:[{setNumber, playerScore, opponentScore}…], setsWon, setsLost, result}`), a
`MatchResponse[]` list fixture, and an `ApiError`-shaped error object reusing the Phase 2 503
oracle `{status:503, message:"AI service is temporarily unavailable", fieldErrors:null}`. Cite
`src/test/java/.../match/MatchApiIntegrationTests.java:64-95,184` and the Phase 2 503 oracle in a
header comment.

#### 2. api-client contract test

**File**: `frontend/src/api/matches.contract.test.tsx` (new)

**Intent**: Prove the api functions decode the backend's real JSON into the expected shape — the
runtime contract — without re-asserting the client's own TS types.

**Contract**: `vi.mock('./client')` to stub the axios singleton's `get`/`post` to resolve
`{ data: <fixture> }`. Assert `createMatch(...)` returns an object whose fields match the success
fixture (including `sets` as per-set integer-pair objects with `setNumber`, and `result` as a bare
`'WON'|'LOST'|'DRAW'` string); assert `listMatches()` returns the array fixture. For the error case,
make the stubbed transport reject with an axios-style error carrying the `ApiError` fixture as
`response.data`, and assert the rejection's `response.data` matches `{status:503, message, fieldErrors}`
— pinning the declared-but-unused `ApiError` shape as a drift tripwire. Assertions must read fixture
field **values/shape**, not just `typeof`.

#### 3. Fill in test-plan cookbook

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the §6.3 and §6.5 "TBD" stubs with the concrete patterns this phase
establishes, so the next frontend test author follows them.

**Contract**: §6.3 — route-guard component pattern (Vitest + RTL + `MemoryRouter`, mock `useAuth`
for the three states, real `AuthProvider` + never-resolving `me()` for the in-flight case, don't
mock the guard, run `npm run test:run`). §6.5 — api-client contract pattern (`vi.mock('./client')`,
hand-written fixtures from DTO shapes with backend test as oracle, assert decode-shape + one
error-body, don't re-assert TS types). Do not alter §1–§5 strategy or §2 risk definitions.

### Success Criteria:

#### Automated Verification:

- [ ] Contract tests pass: `cd frontend && npm run test:run`
- [ ] Full frontend suite green (smoke + guard + contract): `cd frontend && npm run test:run`
- [ ] Typecheck passes: `cd frontend && npm run typecheck`
- [ ] Biome reports no errors: `cd frontend && npx biome check src`

#### Manual Verification:

- [ ] Temporarily rename a fixture field (e.g. `setNumber` → `set_number`) and confirm the contract
  test FAILS — proving it pins the real field names, not the TS types
- [ ] Confirm §6.3 and §6.5 no longer read "TBD" and describe the shipped patterns
- [ ] Lefthook pre-commit gate (`biome-check` + `frontend-typecheck`) passes on the staged test files

**Implementation Note**: After completing this phase and all automated verification passes, pause
for final manual confirmation. The change is then ready to commit and the GitHub issue moved to Done
on merge to main.

---

## Testing Strategy

### Unit / Component Tests:

- **Route guard (#4)**: three states (loading→nothing, unauth→`/login`, auth→Outlet) + the
  never-resolving in-flight case + one router-level redirect. Edge: ordering must be `loading`
  before `!user` (the in-flight case is the canary).
- **api-client contract (#5)**: create + list success decode-shape; one `ApiError`/503 error body.
  Edge: per-set integer-pair score shape (not string/int); `result` as bare enum string.

### Integration Tests:

- None new — Phase 3 is unit/component + contract only. The backend response-shape integration
  assertion (the #5 backend half) is in scope elsewhere (Phase 1/2 backend suite).

### Manual Testing Steps:

1. Run `cd frontend && npm run test:run` — all three test files green.
2. Reorder the guard states (`!user` before `loading`) — confirm the in-flight test fails; revert.
3. Rename a fixture field — confirm the contract test fails; revert.
4. Run `npm run typecheck` and `npx biome check src` — both clean.

## Performance Considerations

Negligible. Three small jsdom test files; the runner starts in well under a second. Keep tests off
the network (jsdom-safe: no real `me()` call escapes because the provider's `me` is mocked).

## Migration Notes

None — purely additive. New devDependencies and `package-lock.json` changes; no runtime/build
behavior changes (`vite build` ignores the `test` block).

## References

- Research: `context/changes/frontend-runner-bootstrap-tests/research.md`
- Change brief: `context/changes/frontend-runner-bootstrap-tests/change.md`
- Test plan: `context/foundation/test-plan.md` §2 (rows #4/#5), §3 (Phase 3), §4 (Stack), §6.3/§6.5
- Guard: `frontend/src/components/ProtectedRoute.tsx:4-9`; auth: `frontend/src/contexts/AuthContext.tsx:12-24`
- api-client: `frontend/src/api/client.ts`, `frontend/src/api/matches.ts`, `frontend/src/api/auth.ts`
- Fixture oracle: `src/test/java/.../match/MatchApiIntegrationTests.java:64-95,184`; Phase 2 503 oracle
  (`context/changes/ai-failure-path-tests/`)
- Gates: `lefthook.yml:22-35` (`biome-check` + `frontend-typecheck`)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Runner Bootstrap

#### Automated

- [x] 1.1 Deps install cleanly: `cd frontend && npm install` — fbc8f79
- [x] 1.2 Runner runs green: `cd frontend && npm run test:run` — fbc8f79
- [x] 1.3 Typecheck passes (vitest + jest-dom types resolve): `cd frontend && npm run typecheck` — fbc8f79
- [x] 1.4 Biome reports no errors: `cd frontend && npx biome check src` — fbc8f79

#### Manual

- [x] 1.5 `npm run test` (watch mode) starts and re-runs on change, exits cleanly with `q` — fbc8f79
- [x] 1.6 `git status` shows `package-lock.json` updated and `node_modules/` still ignored — fbc8f79

### Phase 2: Route-Guard Tests (#4)

#### Automated

- [x] 2.1 Guard tests pass: `cd frontend && npm run test:run` — c784c3c
- [x] 2.2 Typecheck passes: `cd frontend && npm run typecheck` — c784c3c
- [x] 2.3 Biome reports no errors: `cd frontend && npx biome check src` — c784c3c

#### Manual

- [x] 2.4 Reordered guard (`!user` before `loading`) makes the in-flight case FAIL — c784c3c
- [x] 2.5 Guard itself is not mocked (only `useAuth` / `me`) — c784c3c

### Phase 3: API-Client Contract Tests (#5)

#### Automated

- [x] 3.1 Contract tests pass: `cd frontend && npm run test:run`
- [x] 3.2 Full frontend suite green (smoke + guard + contract): `cd frontend && npm run test:run`
- [x] 3.3 Typecheck passes: `cd frontend && npm run typecheck`
- [x] 3.4 Biome reports no errors: `cd frontend && npx biome check src`

#### Manual

- [x] 3.5 Renamed fixture field (e.g. `setNumber`→`set_number`) makes the contract test FAIL
- [x] 3.6 §6.3 and §6.5 no longer read "TBD" and describe the shipped patterns
- [x] 3.7 Lefthook pre-commit gate passes on the staged test files
