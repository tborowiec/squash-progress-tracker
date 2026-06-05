# Ownership-boundary & No-mis-save Backend Tests — Implementation Plan

## Overview

This is **Phase 1 of the test-plan rollout** (`context/foundation/test-plan.md` §3, row 1): backend integration tests that lock two existing-but-under-tested contracts in the `match/` package.

- **Risk #1 — Cross-player match access (IDOR).** Enforcement is already solid: every by-id route funnels through `MatchService.requireOwned → findByIdAndUserId`, and a cross-tenant miss returns **404** (enumeration-safe, not 403). The gap is *durability* — coverage is hand-written per endpoint, so a future by-id route that forgets `requireOwned` inherits no test. We add a **parameterized foreign-id → 404 sweep** over every `/api/matches/{id}` route so a new endpoint fails the suite by construction, plus a parameterized **anonymous → 401** sweep.
- **Risk #3 — Silent mis-save from AI parse.** Architecture is safe: parse and save are separate endpoints; save takes the structured, confirmed `CreateOrUpdateMatchRequest` and never re-parses. The gaps are two genuinely-missing tests: **confirmed == saved on a fresh re-GET** (expected values sourced from the test's own constants, never parser output) and **parse is side-effect-free** (previewing persists nothing).

The final phase fills the §6.2/§6.4 cookbook entries and corrects the §4 stack note (which wrongly says `@WithMockUser`).

This is **gap-closing and behaviour-locking, not greenfield.** We add only the genuinely-missing assertions and a durable contract; we do **not** re-add cross-user tests that already pass.

## Current State Analysis

**Enforcement (production code) is correct today** — verified by research against `db94bab`:

- The single chokepoint: `MatchService.requireOwned(id)` (`MatchService.java:79-82`) queries `findByIdAndUserId(id, currentUser.currentUserId())` and throws `MatchNotFoundException` on a non-owned id. Every by-id route (`GET`/`PUT`/`DELETE /api/matches/{id}`) routes through it.
- `MatchNotFoundException` maps to HTTP **404** (`ApiExceptionHandler.java:39-43`) — the *same* 404 for "doesn't exist" and "exists but not yours". This is the intended, enumeration-safe IDOR posture: assert **404, not 403**.
- Collection routes filter by `userId` at the repository layer: list / `?opponent=` (`MatchService.java:65-72`), opponents JPQL (`MatchRepository.java:21`), game-plan SSE (`GamePlanService.java:32-43`).
- Unauthenticated requests get **401** from the global `.anyRequest().authenticated()` gate before reaching any controller.
- Identity is read from the security context (`CurrentUser.currentUserId()`), never from a request parameter.

**Parse/save separation (production code) is correct today:**

- `POST /api/matches/parse` (`MatchController.java:31-34` → `MatchParseService.java:26-30`) calls the LLM and returns a preview DTO. It **persists nothing** — no `save` call on that path.
- `POST /api/matches` and `PUT /api/matches/{id}` accept `@Valid CreateOrUpdateMatchRequest` (the confirmed structured payload — there is no `text` field). The only two `matchRepository.save(...)` callers are `MatchService.create:35` and `update:57`, both fed exclusively by that DTO. The LLM is never on the save path.
- `applySets` (`MatchService.java:84-93`) copies each set's `playerScore`/`opponentScore` verbatim and **re-derives `setNumber = index+1`** — the client never supplies it.

**Existing test coverage (the harness to reuse and the gaps):**

- `src/test/java/.../match/MatchApiIntegrationTests.java` (21 `@Test` methods) establishes the harness: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` + `@Container @ServiceConnection static PostgreSQLContainer<>("postgres:17")`. Auth is **session-based** — a `registerAndLogin(email)` helper returns a `MockHttpSession`; mutations require `.with(csrf())`. Helpers: `createMatch(session, body)`, constant `VALID_MATCH`. **Not** `@WithMockUser`.
- Already-passing cross-tenant tests (**do not re-add these**): `playerBCannotSeePlayerAsMatches:167` (unfiltered + filtered list empty), `opponentsReturnsOnlyCallerOwned:217`, `crossUserGetReturns404:363`, `crossUserUpdateReturns404:373`, `crossUserDeleteReturns404:386`, anon `:186/:398/:407`; `GamePlanApiIntegrationTests.java:141` (SSE cross-tenant → 404).
- **#1 gap:** the above are hand-written per endpoint. No single parameterized sweep auto-covers a future by-id route.
- **#3 gap:** `createMatchReturns201AndDerivedScores:75-88` asserts only the **POST response echo** of three fields + derived scores. No test (a) re-reads the persisted record in a fresh request and asserts field-by-field equality against the confirmed input (incl. `notes`, `matchDate`, exact per-set scores in order), nor (b) asserts the preview endpoint is side-effect-free.

### Key Discoveries

- **One chokepoint = one place to test the contract.** Because all by-id access flows through `requireOwned`, a parameterized sweep is cheap and high-signal — it guards the chokepoint against future bypass. This beats adding more one-off cross-user tests (`MatchService.java:79-82`).
- **404-not-403 is an intentional security choice** (no id enumeration) — encode it as the expected status (`ApiExceptionHandler.java:39-43`).
- **Parse/save separation is the structural guarantee behind #3.** The meaningful tests are "preview persists nothing" + "saved == confirmed on fresh read, sourced from test inputs" — not from the LLM/parser, which would green-light a re-parse bug.
- **Server-owned fields to exclude from any confirmed==saved equality:** `id` (DB), `userId` (from principal, `MatchService.java:28`), `createdAt` (`@PrePersist`, `Match.java:36-41`), and `MatchSet.setNumber` (derived = index+1).
- **The LLM edge is stubbed with `@MockitoBean LlmClient`** — required for the deterministic parse-side-effect test; no real provider call in CI.

## Desired End State

Two new dedicated test classes plus an updated test plan:

1. `MatchOwnershipBoundaryTests` — a parameterized by-id route table proving Player B → **404** for Player A's match id on every by-id route, and a parameterized anonymous → **401** sweep. A new by-id endpoint that forgets `requireOwned` fails this suite by construction.
2. `MatchNoMisSaveTests` — confirmed == saved on a fresh re-GET (create path), the same full-equality check on the update path, and parse-is-side-effect-free.
3. `test-plan.md` §6.2 and §6.4 cookbook entries filled with location / naming / reference test / run command; §4 stack note corrected from `@WithMockUser` to the real session-based harness.

**Verification:** `./mvnw test -Dtest=MatchOwnershipBoundaryTests` and `./mvnw test -Dtest=MatchNoMisSaveTests` both pass; full `./mvnw test` stays green; `test-plan.md` §6.2/§6.4/§4 no longer read "TBD" / "@WithMockUser".

## What We're NOT Doing

- **Not re-adding cross-tenant tests that already pass.** The collection-route isolation is already covered and is *traceable*, not duplicated. Existing-coverage ledger (leave in place, do not rewrite):
  | Cross-tenant contract | Already covered by | Status |
  |---|---|---|
  | unfiltered list empty for non-owner | `MatchApiIntegrationTests.java:167` | keep |
  | `?opponent=` filtered list empty for non-owner | `MatchApiIntegrationTests.java:180` | keep |
  | opponents endpoint caller-scoped | `MatchApiIntegrationTests.java:217` | keep |
  | game-plan SSE cross-tenant → 404 | `GamePlanApiIntegrationTests.java:141` | keep (stays separate — opponent-param, not id-based; see below) |
  | by-id get/update/delete cross-tenant → 404 (one-offs) | `MatchApiIntegrationTests.java:363/373/386` | superseded by the Phase 1 sweep, but **leave in place** — removing passing tests is out of scope |
- **Not folding the game-plan SSE route into the #1 sweep.** SSE is opponent-param-filtered (not by-id) and returns a stream; it does not fit the uniform "404 for foreign id" table shape and is already covered. Decision confirmed at plan time.
- **Not changing any production code.** Research found no IDOR vulnerability and no mis-save bypass. This change is tests + the test-plan doc only. If a test fails against current code, that is a finding to report — not a signal to "fix" production silently.
- **Not adding retry/backoff or AI failure-path tests** — that is Phase 2 (#2), a separate change.
- **Not asserting a cryptographic parse→save binding.** The confirm gate is a client-side contract; the server exposes parse and create as independent endpoints. The #3 tests assert *structural separation + verbatim persistence*, not a server-enforced link.

## Implementation Approach

Two new test classes (decision: dedicated classes, isolates the durable contract sweep from happy-path noise) authored by **copying the established harness** from `MatchApiIntegrationTests` (same annotations, a local `registerAndLogin` / `createMatch` / fixture pattern). Ordered by cost × signal and risk priority: **#1 first** (High × High, the top risk), **#3 second** (High × Medium), then the cookbook/stack-note reconciliation.

The high-signal move for #1 is a **JUnit `@ParameterizedTest` / `@MethodSource` route table** keyed on `(httpMethod, pathTemplate)` so every by-id route is asserted uniformly and a new route is one table row away from coverage.

For #3, every expected value is a **named test constant**; the test re-GETs in a fresh request and compares against those constants — never against any parser/LLM output (the oracle trap).

## Critical Implementation Details

- **Assert 404, not 403, on cross-tenant by-id access**, and assert against a match that genuinely *exists* (owned by Player A) — this proves the "exists but not yours" branch, not "doesn't exist". (`ApiExceptionHandler.java:39-43`.)
- **`setNumber` is server-derived (index+1), not client-supplied.** In the confirmed==saved test, assert per-set scores in *posted order* and assert `setNumber == index+1` is produced by the server — do not expect a client-supplied set number. (`MatchService.java:84-93`.)
- **Exclude `id`, `userId`, `createdAt`, `setNumber` from the confirmed==saved equality** — they are server-owned. (`MatchService.java:28`, `Match.java:36-41`.)
- **The parse-side-effect test must stub `LlmClient` with `@MockitoBean`** so it is deterministic and never calls the real provider; assert the caller's list is empty *after* a parse call.
- **Naming convention (AGENTS.md):** `<ClassName>Tests.java`; reference an imported short type name, never a fully qualified name (lessons.md).

## Phase 1: Ownership-boundary contract sweep (`MatchOwnershipBoundaryTests`)

### Overview

Create `src/test/java/.../match/MatchOwnershipBoundaryTests.java` with a parameterized route table that turns the per-endpoint IDOR checks into a durable contract: any by-id match route must reject a non-owner with 404 and an anonymous caller with 401.

### Changes Required

#### 1.1 Foreign-id → 404 parameterized sweep over by-id routes

**File**: `src/test/java/org/borowiec/squashprogresstracker/match/MatchOwnershipBoundaryTests.java` (new)

**Intent**: Prove that an authenticated Player B receives **404** for Player A's match id on **every** by-id route — `GET`, `PUT`, `DELETE /api/matches/{id}` — driven by a `@MethodSource` route table so a future by-id endpoint is covered by adding one row.

**Contract**:
- Harness copied from `MatchApiIntegrationTests` (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` + `@Container @ServiceConnection PostgreSQLContainer<>("postgres:17")`; local `registerAndLogin` → `MockHttpSession`; `.with(csrf())` on PUT/DELETE).
- Setup: register+login A and B; A creates a match → capture `matchAId`.
- `@ParameterizedTest @MethodSource` over rows `(HttpMethod, "/api/matches/{id}")`; each row issues B's session against `matchAId` and asserts `status().isNotFound()`. PUT row carries a minimal valid body; DELETE/PUT carry `.with(csrf())`.
- **Behavior asserted**: cross-tenant by-id access → 404 on every by-id route.
- **Regression caught**: a new/refactored by-id endpoint that omits `requireOwned`, or one that returns 403/200 or leaks the foreign record.
- **Research source**: `MatchService.requireOwned:79-82`, `MatchRepository.findByIdAndUserId:18-19`, `ApiExceptionHandler.java:39-43` (404).
- **Edge/boundary**: assert **404 specifically** (not 403, not 200); the target match **exists** (owned by A) so the test exercises "exists-but-not-yours", not "missing id".
- **Anti-pattern avoided**: testing only the happy owner path; asserting 200 instead of cross-tenant 404; re-adding one-off cross-user tests — this consolidates them into a route table that future endpoints join by construction.

#### 1.2 Anonymous → 401 parameterized sweep

**File**: same new file

**Intent**: Prove an unauthenticated caller is rejected with **401** before reaching any controller, across the by-id routes (and a representative mutation), driven by the same table mechanism.

**Contract**:
- `@ParameterizedTest @MethodSource` over `(HttpMethod, path)` rows with **no session attached**; assert `status().isUnauthorized()`. Mutating rows still attach `.with(csrf())` so the assertion isolates the auth gate, not CSRF.
- **Behavior asserted**: unauthenticated request to a match route → 401.
- **Regression caught**: the global `.anyRequest().authenticated()` gate weakened, or a route accidentally permitted anonymously.
- **Research source**: security global gate (research §"Cross-tenant miss → 404", 401 note).
- **Edge/boundary**: assert **401** (not 403, not a 302 redirect) — the API contract is a JSON 401, not a login redirect.
- **Anti-pattern avoided**: assuming session auth redirects (302); asserting only that "some non-2xx" came back instead of the exact 401.

### Success Criteria

#### Automated Verification:

- [ ] New class compiles and runs: `./mvnw test -Dtest=MatchOwnershipBoundaryTests`
- [ ] The foreign-id sweep asserts 404 (not 403/200) for every by-id route row
- [ ] The anonymous sweep asserts 401 for every row
- [ ] Full suite stays green: `./mvnw test`

#### Manual Verification:

- [ ] Adding a hypothetical new by-id route as a table row would require no other change to get IDOR coverage (read the `@MethodSource` and confirm)
- [ ] No fully qualified class names used where an import suffices (lessons.md)

**Implementation Note**: After Phase 1 automated verification passes, pause for human confirmation before Phase 2.

---

## Phase 2: No-mis-save persistence-fidelity (`MatchNoMisSaveTests`)

### Overview

Create `src/test/java/.../match/MatchNoMisSaveTests.java` proving the persisted record equals the confirmed input on a fresh read, and that previewing persists nothing.

### Changes Required

#### 2.1 Confirmed == saved via fresh re-GET (create path)

**File**: `src/test/java/org/borowiec/squashprogresstracker/match/MatchNoMisSaveTests.java` (new)

**Intent**: POST a distinctive `CreateOrUpdateMatchRequest`, then **re-GET** the new id in a fresh request and assert the persisted record equals the **posted input constants** field-by-field — closing the gap that the existing test only checks the POST response echo.

**Contract**:
- Define a fixture with distinctive, asymmetric values: non-empty `notes`, a specific `matchDate`, and ≥2 sets with asymmetric `playerScore`/`opponentScore` in a deliberate order (so a reordering bug is detectable). Expected values are **named test constants**.
- POST (`.with(csrf())`, A's session) → capture `id`; then `GET /api/matches/{id}` (fresh request) and assert equality against the constants for: `opponentName`, `matchDate`, `notes`, and each set's `playerScore`/`opponentScore` **in posted order**.
- Assert `setNumber == index+1` is **server-derived**; exclude `id`, `userId`, `createdAt` from equality.
- **Behavior asserted**: persisted record == confirmed input on a fresh read.
- **Regression caught**: a save path that re-parses, drops `notes`, alters `matchDate`, or reorders/mutates per-set scores.
- **Research source**: `MatchService.create:25-36`, `applySets:84-93`, `CreateOrUpdateMatchRequest.java:13-18`.
- **Edge/boundary**: asymmetric per-set scores + explicit order; non-empty `notes`; `setNumber` asserted as derived, not echoed.
- **Anti-pattern avoided**: the **oracle problem** — expected values come from the test's own constants, never from parser/LLM output.

#### 2.2 Confirmed == saved on the update path (PUT)

**File**: same new file

**Intent**: Extend the same full-equality discipline to `PUT /api/matches/{id}` — a confirmed edit must persist verbatim — complementing (not duplicating) the existing derived-field PUT test.

**Contract**:
- A creates a match; then PUT (`.with(csrf())`, A's session) a second distinctive `CreateOrUpdateMatchRequest` (different `notes`/`matchDate`/sets); re-GET and assert full equality vs the **updated** posted constants, same excluded server-owned fields, same in-order set check.
- **Behavior asserted**: a confirmed update persists exactly what was sent, on a fresh read.
- **Regression caught**: an update path that partially applies, retains stale fields, or mis-derives `setNumber` after a set-count change.
- **Research source**: `MatchService.update:43-58`, `applySets:84-93`; complements `ownerUpdateChangesFieldsAndDerivedScores:278` (which asserts derived fields only).
- **Edge/boundary**: change the set count between create and update so `setNumber` re-derivation is exercised.
- **Anti-pattern avoided**: oracle problem (expected from constants); re-adding the existing derived-field PUT test (this asserts full confirmed==saved equality instead).

#### 2.3 Parse is side-effect-free

**File**: same new file

**Intent**: Prove `POST /api/matches/parse` persists nothing — locking "no silent mis-save via the parse path."

**Contract**:
- Stub the LLM edge with `@MockitoBean LlmClient` returning a canned structured result; A logs in; assert A's `GET /api/matches` list is empty; POST `/api/matches/parse` (`.with(csrf())`); assert A's list is **still empty**.
- **Behavior asserted**: previewing a parse creates no match.
- **Regression caught**: a parse path that silently persists, bypassing the confirm gate.
- **Research source**: `MatchParseService.java:26-30` (no `save`), `MatchController.java:31-34`.
- **Edge/boundary**: list asserted empty **before and after**; LLM stubbed so the test is deterministic and never hits the real provider.
- **Anti-pattern avoided**: asserting on parser output (tautology); calling the real LLM in a test.

### Success Criteria

#### Automated Verification:

- [ ] New class compiles and runs: `./mvnw test -Dtest=MatchNoMisSaveTests`
- [ ] Confirmed==saved (create) asserts `opponentName`/`matchDate`/`notes` + per-set scores in posted order on a fresh GET
- [ ] Confirmed==saved (update) asserts full equality after a PUT with a changed set count
- [ ] Parse-side-effect test shows the list empty before and after a stubbed parse
- [ ] Full suite stays green: `./mvnw test`

#### Manual Verification:

- [ ] Confirm every expected value traces to a test constant, never to parser/LLM output (read the assertions)
- [ ] Confirm `id`/`userId`/`createdAt`/`setNumber` are excluded from equality and `setNumber` is asserted as server-derived

**Implementation Note**: After Phase 2 automated verification passes, pause for human confirmation before Phase 3.

---

## Phase 3: Cookbook + stack-note reconciliation (`test-plan.md`)

### Overview

Fill the §6 cookbook entries this phase owns and correct the §4 stack note that misdescribes the harness. Documentation only.

### Changes Required

#### 3.1 Fill §6.2 — Adding a backend integration test (auth boundary / persistence)

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the "TBD" with the concrete pattern: location, naming, reference tests, run command, mocking policy.

**Contract**: location `src/test/java/.../match/`; naming `<ClassName>Tests.java`; reference tests `MatchOwnershipBoundaryTests` (boundary) and `MatchNoMisSaveTests` (persistence fidelity); run `./mvnw test -Dtest=MatchOwnershipBoundaryTests` (single method: `-Dtest=ClassName#method`); mocking policy: real Postgres via Testcontainers, stub only the LLM HTTP edge (`@MockitoBean LlmClient`), assert request→response **and** persisted side-effects on a fresh read.

#### 3.2 Fill §6.4 — Adding a test for a new match API endpoint (ownership)

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the "TBD" with the durable-sweep pattern.

**Contract**: the canonical pattern is to add the new by-id route as a row in the `MatchOwnershipBoundaryTests` `@MethodSource` table so "Player B → 404 for Player A's id" is asserted by construction; assert **404, not 403** (enumeration-safe); reference `MatchOwnershipBoundaryTests`; run `./mvnw test -Dtest=MatchOwnershipBoundaryTests`.

#### 3.3 Correct the §4 stack note (harness is session-based, not `@WithMockUser`)

**File**: `context/foundation/test-plan.md`

**Intent**: Fix the "backend security tests" row, which wrongly says `@WithMockUser`.

**Contract**: change the Notes cell to describe the actual harness — session-based auth via `registerAndLogin(email)` → `MockHttpSession`, two sessions (A/B) for cross-tenant tests, `.with(csrf())` on mutations; not `@WithMockUser`.

#### 3.4 (Optional) §6.7 per-phase note

**File**: `context/foundation/test-plan.md`

**Intent**: Append a 2–3 line note capturing the phase's takeaway (the durable route-table sweep + the oracle-trap avoidance), if useful to future readers.

**Contract**: a short bullet under §6.7; optional.

### Success Criteria

#### Automated Verification:

- [ ] §6.2, §6.4 no longer contain "TBD — see §3 Phase 1": `grep -n "TBD" context/foundation/test-plan.md` shows neither §6.2 nor §6.4
- [ ] §4 "backend security tests" row no longer contains "@WithMockUser": `grep -n "WithMockUser" context/foundation/test-plan.md` returns nothing

#### Manual Verification:

- [ ] §6.2/§6.4 reference test names and run commands match the classes actually created in Phases 1–2
- [ ] §4 stack note accurately describes the session-based harness

**Implementation Note**: After Phase 3, mark §3 Phase 1 row `complete` and set `change.md` status to `implemented`; loop `/10x-test-plan` to advance the rollout.

---

## Testing Strategy

### Unit Tests

- None. This change is integration-only by design (auth boundary + persistence fidelity need a real Postgres and the full Spring/security stack).

### Integration Tests

- `MatchOwnershipBoundaryTests`: parameterized foreign-id → 404 sweep over by-id routes; parameterized anonymous → 401 sweep.
- `MatchNoMisSaveTests`: confirmed==saved on fresh re-GET (create + update paths); parse-is-side-effect-free (LLM stubbed).

### Manual Testing Steps

1. `./mvnw test -Dtest=MatchOwnershipBoundaryTests` — passes; assertions are 404/401, not 403/200.
2. `./mvnw test -Dtest=MatchNoMisSaveTests` — passes; expected values trace to test constants.
3. `./mvnw test` — full suite green, no regressions.
4. Read both new classes to confirm no fully qualified class names and no parser-output-as-oracle.

## Performance Considerations

Each class boots a Spring context + a `postgres:17` Testcontainer. Two new `@SpringBootTest` classes add container-startup cost; acceptable for the integration tier and consistent with the existing suite. The parameterized sweeps reuse one context per class.

## Migration Notes

None — additive test files plus a doc edit. No production code, schema, or data changes.

## References

- Related research: `context/changes/ownership-boundary-tests/research.md`
- Change brief: `context/changes/ownership-boundary-tests/change.md`
- Test plan: `context/foundation/test-plan.md` (§3 row 1, §6.2/§6.4, §4)
- Harness to copy: `src/test/java/.../match/MatchApiIntegrationTests.java:24-70`
- Chokepoint under test: `src/main/java/.../match/MatchService.java:79-82`; 404 mapping `user/ApiExceptionHandler.java:39-43`
- Lessons: no fully qualified class names (`context/foundation/lessons.md`); board sync (set issue #11 → In Progress when work begins)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Ownership-boundary contract sweep

#### Automated

- [x] 1.1 New class compiles and runs: `./mvnw test -Dtest=MatchOwnershipBoundaryTests` — 591ad2b
- [x] 1.2 Foreign-id sweep asserts 404 (not 403/200) for every by-id route row — 591ad2b
- [x] 1.3 Anonymous sweep asserts 401 for every row — 591ad2b
- [x] 1.4 Full suite stays green: `./mvnw test` — 591ad2b

#### Manual

- [x] 1.5 A hypothetical new by-id route needs only a table row to gain IDOR coverage — 591ad2b
- [x] 1.6 No fully qualified class names where an import suffices — 591ad2b

### Phase 2: No-mis-save persistence-fidelity

#### Automated

- [x] 2.1 New class compiles and runs: `./mvnw test -Dtest=MatchNoMisSaveTests` — 82405f4
- [x] 2.2 Confirmed==saved (create) asserts opponentName/matchDate/notes + per-set scores in posted order on a fresh GET — 82405f4
- [x] 2.3 Confirmed==saved (update) asserts full equality after a PUT with a changed set count — 82405f4
- [x] 2.4 Parse-side-effect test shows the list empty before and after a stubbed parse — 82405f4
- [x] 2.5 Full suite stays green: `./mvnw test` — 82405f4

#### Manual

- [x] 2.6 Every expected value traces to a test constant, never parser/LLM output — 82405f4
- [x] 2.7 id/userId/createdAt/setNumber excluded from equality; setNumber asserted as server-derived — 82405f4

### Phase 3: Cookbook + stack-note reconciliation

#### Automated

- [x] 3.1 §6.2 and §6.4 no longer contain "TBD"
- [x] 3.2 §4 "backend security tests" row no longer contains "@WithMockUser"

#### Manual

- [x] 3.3 §6.2/§6.4 reference test names + run commands match the created classes
- [x] 3.4 §4 stack note accurately describes the session-based harness
