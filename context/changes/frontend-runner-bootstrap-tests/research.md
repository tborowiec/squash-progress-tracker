---
date: 2026-06-06T00:00:00Z
researcher: Tomasz Borowiec
git_commit: 2fb3ec4c65dcdea250efdfe74ac94a32decec998
branch: main
repository: squash-progress-tracker
topic: "Phase 3 — frontend test runner bootstrap + route-guard (#4) & api-client contract (#5)"
tags: [research, codebase, frontend, vitest, route-guard, api-contract, test-plan-phase-3]
status: complete
last_updated: 2026-06-06
last_updated_by: Tomasz Borowiec
---

# Research: Phase 3 — frontend test runner bootstrap + route-guard (#4) & api-client contract (#5)

**Date**: 2026-06-06
**Researcher**: Tomasz Borowiec
**Git Commit**: 2fb3ec4c65dcdea250efdfe74ac94a32decec998
**Branch**: main
**Repository**: squash-progress-tracker

## Research Question

Ground Phase 3 of the test rollout (`context/foundation/test-plan.md` §3): stand up the
frontend test runner (none exists today — 0 test files, no runner), then prove the two
frontend-cluster risks at the cheapest layer:

- **#4 Frontend route-guard regression** — an unauthenticated render of a protected route
  must redirect to login; a valid session must render the route (and a still-checking
  session must NOT be bounced).
- **#5 Frontend ↔ backend contract drift** — the api-client and backend DTOs must agree on
  field names, score shape, and error-body shape for match create/list/error.

## Summary

The frontend is well-shaped for testing and the bootstrap is low-risk:

- **Runner**: nothing test-related is installed. Add Vitest 3 + Testing Library (RTL 16 +
  explicit `@testing-library/dom` peer) + `jest-dom` + `user-event` + `jsdom`, configured
  via an **inline `test` block in the existing `vite.config.ts`** (least disruption). The
  `.gitignore` already covers `frontend/node_modules/` and `frontend/dist/`, so the
  node_modules lesson is already satisfied.
- **#4**: `ProtectedRoute` is a clean three-state layout guard (`loading → null`,
  `!user → <Navigate to="/login">`, else `<Outlet/>`). The correctness property to protect
  is that **loading is checked before unauthenticated** — they are distinct states, so an
  in-flight session is not bounced. The test must cover all three states with
  `MemoryRouter` + a controlled `AuthContext`, and must **not** mock the guard itself.
- **#5**: The score shape is settled on both sides — a **list of per-set integer pairs**
  (`{playerScore, opponentScore}` on request; `{setNumber, playerScore, opponentScore}` on
  response), not a string or single int. The error body is `ApiError {status, message,
  fieldErrors}` and the frontend declares a matching `ApiError` interface but **never
  decodes it** (no error interceptor). The two highest-value contract seams: the
  `matchDate` String-vs-LocalDate divergence between parse and persist, and the
  **two incompatible AI-failure JSON shapes** (parse 503 `{status,message,fieldErrors}` vs
  SSE game-plan `{message}`-only, HTTP 200).

Two integration constraints the plan must respect: the **lefthook `frontend-typecheck`
gate runs `tsc --noEmit` over all of `src/`**, so new test files must typecheck (jest-dom /
vitest globals must resolve), and **Biome lints test files** (advisory `noNonNullAssertion`
will warn but not block).

## Detailed Findings

### Area 1 — Route guard (#4)

**`frontend/src/components/ProtectedRoute.tsx`** — layout-route guard using `<Outlet/>`
(not children). Three states, checked in this order:

1. `loading === true` → returns `null` (renders nothing). **Checked FIRST** — a still-checking
   session does not redirect.
2. `loading === false && user === null` → `<Navigate to="/login" replace />` (hard redirect,
   no history entry).
3. authenticated → `<Outlet />`.

**Destination is NOT preserved**: `<Navigate>` carries no `state={{from: location}}`, and
`LoginPage` sends the user to `/` unconditionally after login. This is a known gap to
*assert against as current behavior*, not to fix in this phase.

**`frontend/src/contexts/AuthContext.tsx`** — state shape `{ user: UserResponse|null,
loading: boolean, setUser }`. No `isAuthenticated` boolean; auth derives from `!!user`.
On mount a `useEffect` calls `me()` (GET `/api/auth/me`): success → `setUser(user)`,
failure → `setUser(null)`, `finally` → `setLoading(false)`. Startup sequence:
`{null, loading:true}` → `{user|null, loading:false}`. **Loading and unauthenticated are
distinct, not collapsed** — this is the core correctness property for #4. `useAuth` throws
if used outside `AuthProvider`.

**`frontend/src/App.tsx` / `main.tsx`** — `BrowserRouter` lives in `main.tsx` wrapping
`<App/>`; `App.tsx` holds only `<AuthProvider><Routes>…`. So tests can mount under a
`MemoryRouter`. Public routes: `/login`, `/register`. Protected (children of the
`<Route element={<ProtectedRoute/>}>` layout route): `/`, `/matches/new`,
`/matches/:id/edit`, `/history`, `/game-plan`. Catch-all `*` → `/` (protected → funnels
anonymous users to `/login`).

**Testability**: render `ProtectedRoute` under `MemoryRouter` (with `initialEntries`) and
provide the auth state. Two valid approaches:
- **Mock `useAuth`** to return each of the three states directly — cleanest for the pure
  three-state logic.
- **Real `AuthProvider` + mocked `me` api** (resolve / reject / *hang* with a never-resolving
  promise) — needed to prove the "in-flight session is not bounced" property end-to-end.

Complications: `AuthProvider` fires a real GET `/api/auth/me` on mount (mock `../api/auth`
or `../api/client`); `api/client.ts` creates a module-level axios singleton + request
interceptor at import time (no network at import, jsdom-safe); a `ensureCsrfToken` bootstrap
does a one-time GET before the first *mutating* request (not triggered by GET `me`).

### Area 2 — api-client ↔ backend contract (#5)

**Frontend api layer:**
- `frontend/src/api/client.ts` — axios instance, `baseURL: ''`, `withCredentials: true`.
  Request interceptor attaches `X-XSRF-TOKEN` (from `XSRF-TOKEN` cookie) on mutating methods;
  CSRF bootstrap GETs `/api/auth/me` if no cookie. **No response unwrapping and no error
  normalization** — every call site does `.then(r => r.data)`; axios errors propagate raw.
- `frontend/src/api/matches.ts` — TS interfaces and the 7 functions:
  | fn | method | path | request | response |
  |---|---|---|---|---|
  | `createMatch` | POST | `/api/matches` | `CreateOrUpdateMatchRequest` | `MatchResponse` |
  | `getMatch` | GET | `/api/matches/{id}` | — | `MatchResponse` |
  | `updateMatch` | PUT | `/api/matches/{id}` | `CreateOrUpdateMatchRequest` | `MatchResponse` |
  | `deleteMatch` | DELETE | `/api/matches/{id}` | — | void |
  | `listMatches` | GET | `/api/matches?opponent=` | — | `MatchResponse[]` |
  | `listOpponents` | GET | `/api/matches/opponents` | — | `string[]` |
  | `parseMatch` | POST | `/api/matches/parse` | `{ text }` | `MatchParseResult` |
- `frontend/src/api/auth.ts` — `UserResponse {id, email}`; declares `ApiError {status,
  message, fieldErrors}` (but nothing decodes it); `me`/`login`/`register`/`logout`.
- `frontend/src/api/gameplans.ts` — raw `EventSource` (not axios) on GET
  `/api/game-plans/stream?opponent=`; `meta`/`token`/`done`/`error` events. The `error`
  handler **ignores the event payload** — discards the `{"message":…}` body.

**Backend (source of truth):**
- `match/MatchController.java` `@RequestMapping("/api/matches")` — mirrors the 7 routes
  (POST → 201, DELETE → 204, parse → 200).
- DTOs: `CreateOrUpdateMatchRequest` (`opponentName` NotBlank ≤255, `matchDate` **`LocalDate`**
  NotNull PastOrPresent, `notes` ≤2000, `sets` NotEmpty **max 5** @Valid); `SetScoreRequest`
  (`playerScore`/`opponentScore` Integer NotNull **0–99**); `MatchResponse` (`id`,
  `opponentName`, `matchDate` `LocalDate`, `notes`, `sets`, `setsWon` int, `setsLost` int,
  `result` enum); `SetScoreResponse` (`setNumber`, `playerScore`, `opponentScore`);
  `MatchResult` enum `WON|LOST|DRAW` (bare string via Jackson); `MatchParseResult`
  (`matchDate` is a **`String`**, sets have **no `setNumber`**).
- Error handling: `user/ApiExceptionHandler.java` (`@RestControllerAdvice`) returns
  `user/dto/ApiError.java` record `{int status, String message, Map<String,String>
  fieldErrors}`. 400 validation → `fieldErrors` populated (dotted nested keys like
  `sets[0].playerScore`); 503 `LlmException` → `{503, "AI service is temporarily
  unavailable", null}`; plus 401/404/409 cases.

**Score shape — RESOLVED**: list of per-set integer-pair objects. Request sets =
`{playerScore, opponentScore}`; response sets = `{setNumber, playerScore, opponentScore}`
(server assigns set numbers). `setsWon/setsLost/result` derived server-side in
`MatchResponse.from()`. Frontend TS types match field-for-field.

### Area 3 — runner bootstrap fit

- **Nothing installed**: vitest, @testing-library/*, jsdom/happy-dom all MISSING from
  `frontend/node_modules` and `package.json`. Installed core: vite 6.4.2, react 18.3.1,
  @vitejs/plugin-react 4.7.0, typescript 5.9.3.
- **Recommended deps**: `vitest@^3.2`, `@testing-library/react@^16.1`,
  `@testing-library/dom@^10.4` (explicit peer of RTL 16), `@testing-library/jest-dom@^6.6`,
  `@testing-library/user-event@^14.5`, `jsdom@^25`. **jsdom over happy-dom** (broader API
  fidelity for `document.cookie`; neither implements `EventSource`, but SSE game-plan is out
  of Phase-3 scope).
- **Config**: inline `test` block in `vite.config.ts` (`/// <reference types="vitest/config" />`
  at top), `{ environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'] }`;
  setup file does `import '@testing-library/jest-dom/vitest'`. Build `outDir` and `proxy`
  blocks untouched and irrelevant to tests.
- **Scripts**: `"test": "vitest"`, `"test:run": "vitest run"` (the non-watch / `--run`
  equivalent for hooks/CI).
- **TypeScript**: `tsconfig.json` has `types` unset (auto-includes all `@types/*`). Get
  vitest globals + jest-dom matcher types via triple-slash references in the setup file
  (`vitest/globals`, `@testing-library/jest-dom`) rather than setting `types: [...]` (which
  would disable auto-inclusion of `@types/react`). `isolatedModules: true` is fine; use
  `import type` for type-only imports.
- **Biome** (`biome.json` includes `src/**/*`) lints test files; `noNonNullAssertion` is
  **advisory (doesn't block)** per CLAUDE.md. Optional `overrides` to silence it on
  `**/*.test.tsx`, not required.

## Code References

- `frontend/src/components/ProtectedRoute.tsx:5-8` — three-state guard (loading→null,
  !user→Navigate, else Outlet); no `from` state on redirect
- `frontend/src/contexts/AuthContext.tsx:13-21` — `me()` on mount, loading/user transitions;
  `:26-30` — `useAuth` throws outside provider
- `frontend/src/App.tsx:14-27` — AuthProvider + Routes; public vs protected route table;
  catch-all `*` → `/`
- `frontend/src/main.tsx:14` — `BrowserRouter` wraps `<App/>`
- `frontend/src/api/client.ts:3-5,23-47` — axios singleton, withCredentials, CSRF
  interceptor/bootstrap, no response/error normalization
- `frontend/src/api/matches.ts:3-37` — request/response TS interfaces; `:39-57` — the 7 fns
- `frontend/src/api/auth.ts:3-12` — `UserResponse`, `ApiError` (declared, never decoded)
- `frontend/src/api/gameplans.ts:30-33` — SSE `error` handler discards payload
- `src/main/java/org/borowiec/squashprogresstracker/match/MatchController.java:24-59` — routes
- `src/main/java/.../match/dto/MatchResponse.java:8-25` — response shape + derived fields
- `src/main/java/.../match/dto/CreateOrUpdateMatchRequest.java:12-16` — request + validation
- `src/main/java/.../match/dto/SetScoreRequest.java:7-8` / `SetScoreResponse.java:5` — score shape
- `src/main/java/.../match/dto/MatchParseResult.java:5-6` — `matchDate` String, sets without `setNumber`
- `src/main/java/.../user/dto/ApiError.java:5` — `{status, message, fieldErrors}`
- `src/main/java/.../user/ApiExceptionHandler.java:24-52` — 503/400/404/409/401 bodies
- `src/main/java/.../gameplan/GamePlanController.java:60-68` — SSE error event `{message}`-only, HTTP 200
- `src/test/java/.../match/MatchApiIntegrationTests.java:64-95,184` — recordable success/error fixtures
- `frontend/vite.config.ts` — inline `test` block target; `frontend/package.json` — deps + scripts;
  `frontend/tsconfig.json:1-14` — `types` unset, `include:["src"]`, `isolatedModules`
- `lefthook.yml:22-35` — `biome-check` + `frontend-typecheck` gates (will run on new test files)
- `.gitignore` (root) — `frontend/node_modules/` + `frontend/dist/` already ignored

## Architecture Insights

- **The guard's correctness hinges on check ordering.** `loading` is tested before `!user`;
  any refactor that reorders, removes, or collapses the loading branch would either bounce a
  valid in-flight session (false negative) or flash protected content before the session is
  known (false positive). The test must pin both directions, including the hang/loading case.
- **The frontend trusts the backend contract implicitly** — no response envelope, no error
  decoding, raw axios errors. So the contract test's value is entirely in pinning the
  backend's *real* JSON (recorded fixture) against the client's decode expectations, not in
  re-asserting the client's own TS types (the named anti-pattern for #5).
- **AI failure has two distinct JSON shapes.** Non-streaming parse → HTTP 503
  `{status,message,fieldErrors}` (axios rejects); streaming game-plan → HTTP 200 SSE `error`
  event `{message}` only, whose body the frontend *ignores*. Phase 3's #5 scope is match
  create/list/error (the axios path); the SSE shape is noted but belongs with #2 follow-up.
- **`matchDate` is the most likely silent-fail seam**: parse returns a String, persist
  expects a `LocalDate` (ISO `yyyy-MM-dd`). If the LLM returns a non-ISO string, create 400s.

## Historical Context (from prior changes)

- The frontend was built backend-first across `context/archive/2026-05-30-minimal-auth`,
  `2026-05-31-manual-match-and-history`, `2026-06-04-ai-match-entry`,
  `2026-06-04-edit-delete-match` — **every plan was backend-focused**; the SPA always shipped
  without a runner. The deferral was deliberate and sequencing-driven
  (`context/foundation/test-plan.md:92`): Phase 3 "must first stand up a test runner (cost)",
  so it follows the cheap backend wins of Phases 1–2.
- Phase 2 (`context/changes/ai-failure-path-tests/`) established the **503 error-body oracle**
  at the API layer — `{status:503, message:"AI service is temporarily unavailable"}`. Reuse
  this exact body as the frontend contract fixture for the error case.
- `context/foundation/lessons.md` — relevant priors: (a) "Add node_modules/ to .gitignore
  before npm install" — already satisfied here; (b) "Set the GitHub issue to In Progress when
  any work on a change begins" — applies to this research phase; (c) "Keep Squash MVP board
  in sync"; (d) the formatter-strips-unused-imports lesson is Java-only (palantir hook), not
  Biome, so it does not apply to the frontend test files.

## Related Research

- `context/foundation/test-plan.md` — §2 Risk Response Guidance rows for #4 (`:72`) and #5
  (`:73`); §4 Stack (`:105-106`); §6.3/§6.5 cookbook recipes (TBD, this phase fills them).
- `context/changes/ai-failure-path-tests/` (Phase 2 artifacts) — backend failure-path
  conventions and the 503 oracle.

## Open Questions

1. **#4 test surface**: cover `ProtectedRoute` in isolation only, or also a small router
   integration (anonymous hitting `/history` lands on `/login`)? Recommendation: isolation
   for the three states + one router-level redirect assertion; keep it thin.
2. **#5 fixture sourcing**: record a static JSON fixture from a real backend response (e.g.
   captured from `MatchApiIntegrationTests` expectations) vs. hand-write it. Recommendation:
   hand-write from the verified DTO shapes above and cite the backend test as the oracle —
   avoids a live-backend dependency in the runner bootstrap.
3. **Error-case scope for #5**: assert just the success contract (create/list), or also pin
   the `ApiError {status,message,fieldErrors}` body and the 503 shape? The error body is
   declared-but-unused on the frontend, so pinning it guards against silent drift — worth
   including one error-body assertion.
4. **Whether to relax Biome `noNonNullAssertion` on test files** via an `overrides` block.
   Advisory only, so optional; defer to the plan.
