---
date: 2026-06-05T15:16:14+02:00
researcher: Tomasz Borowiec
git_commit: db94bab89cd2ad21e212d46f4673f7b56de1dc29
branch: main
repository: tborowiec/squash-progress-tracker
topic: "Ownership-boundary (#1 IDOR) & no-mis-save (#3) backend test grounding — test-plan Phase 1"
tags: [research, codebase, match, security, idor, persistence, testing]
status: complete
last_updated: 2026-06-05
last_updated_by: Tomasz Borowiec
---

# Research: Ownership-boundary (#1) & no-mis-save (#3) backend test grounding

**Date**: 2026-06-05T15:16:14+02:00
**Researcher**: Tomasz Borowiec
**Git Commit**: db94bab89cd2ad21e212d46f4673f7b56de1dc29
**Branch**: main
**Repository**: tborowiec/squash-progress-tracker

## Research Question

Ground test-plan **Phase 1** (`context/foundation/test-plan.md` §3, row 1) against current code, for two risks:

- **#1 Cross-player match access (IDOR)** — where (and whether) ownership is enforced per query; the new get-one/update/delete paths from `edit-delete-match`; coverage on **every** match endpoint incl. list/filter.
- **#3 Silent mis-save from AI parse** — the parse → preview → confirm → persist wiring; the source of the saved payload (confirmed structured input vs re-parsed text); whether any path bypasses the confirm gate.

The brief (`change.md`) requires the plan to avoid two anti-patterns: testing only the happy owner path / asserting `200` instead of cross-tenant `404`/`403`; and assertions copied from the parser's own output (tautological).

## Summary

**The headline finding reframes Phase 1: this is gap-closing and behaviour-locking, not greenfield.** The code already enforces ownership correctly on every match endpoint, and a real body of cross-tenant tests already exists. Phase 1 should therefore (a) add the **few genuinely missing** assertions, (b) convert the existing ad-hoc per-endpoint checks into a **contract that auto-covers future endpoints**, and (c) fill the §6.2/§6.4 cookbook. It must *not* re-add tests that already pass, and must avoid the tautological #3 trap.

**Risk #1 (IDOR) — enforcement is solid; coverage is good-but-ad-hoc.**
- Every match-by-id path funnels through one helper, `MatchService.requireOwned(id)`, which queries `findByIdAndUserId(id, currentUserId)` and throws `MatchNotFoundException` → HTTP **404** (enumeration-safe; not 403). List/filter/opponents/game-plan all filter by `userId` at the repository layer.
- Existing tests already cover cross-user get/update/delete (404), unfiltered **and** filtered list (empty), opponents (caller-owned only), game-plan stream (404), and anonymous (401).
- **Gap**: coverage is per-endpoint and hand-written. A *new* match-by-id endpoint added later inherits no ownership test automatically. The cheap, high-signal addition is a **single parameterized sweep** asserting "for every state-changing/reading match-by-id route, Player B gets 404 for Player A's id" — this is what turns happy-path-plus-a-few into a durable contract.

**Risk #3 (no mis-save) — architecture is safe; the locking test is genuinely missing.**
- Parse and save are **fully separate endpoints**. `POST /api/matches/parse` calls the LLM and returns a preview DTO; it persists **nothing**. `POST /api/matches` (and `PUT /api/matches/{id}`) accept the **already-structured, confirmed `CreateOrUpdateMatchRequest`** — there is no `text` field and the LLM is never touched on the save path. The saved record is sourced from the confirmed payload, never re-parsed. So a non-tautological confirmed==saved test is possible.
- **Gap**: the only create test (`createMatchReturns201AndDerivedScores`) asserts the **POST response echo** of three fields + derived scores. No test (i) re-reads the persisted record in a fresh request and asserts it **field-by-field equals the confirmed input** (incl. `notes`, `matchDate`, exact per-set `playerScore`/`opponentScore` in posted order), nor (ii) asserts the preview endpoint is **side-effect-free** (parsing does not create a match). Those two are the meaningful #3 tests.

## Detailed Findings

### Risk #1 — Ownership enforcement (current code)

**Identity resolution.** The current player id is read from the security context, not from any request parameter:
- `security/CurrentUser.java:14-24` — `currentUserId()` → `principal().getId()`, where `principal()` pulls `AppUserDetails` off `SecurityContextHolder...getAuthentication()` and throws `IllegalStateException` if absent.
- `security/AppUserDetails.java` — carries the DB `Long id`.

**The single chokepoint.** Every by-id match operation goes through one helper:
- `match/MatchService.java:79-82`
  ```java
  private Match requireOwned(Long id) {
      return matchRepository.findByIdAndUserId(id, currentUser.currentUserId())
              .orElseThrow(() -> new MatchNotFoundException(id));
  }
  ```
- Repository: `match/MatchRepository.java:18-19` — `Optional<Match> findByIdAndUserId(Long id, Long userId)` (id **and** owner in one query; a non-owned id yields empty Optional).

**Per-endpoint enforcement map** (`match/MatchController.java`, base path `/api/matches`):

| Endpoint | Controller | Service | Ownership mechanism |
|---|---|---|---|
| `POST /api/matches` (create) | `:25-29` | `MatchService.java:25-36` | binds `setUserId(currentUser.currentUserId())` at insert |
| `GET /api/matches` (list, opt. `?opponent=`) | `:36-39` | `MatchService.java:65-72` | repo `findByUserId...` / `findByUserIdAndOpponentName...` — both filter by `userId` |
| `GET /api/matches/opponents` | `:41-44` | `MatchService.java:74-77` | JPQL `WHERE m.userId = :userId` (`MatchRepository.java:21`) |
| `GET /api/matches/{id}` | `:46-49` | `MatchService.java:38-41` | `requireOwned(id)` |
| `PUT /api/matches/{id}` | `:51-54` | `MatchService.java:43-58` | `requireOwned(id)` before any mutation |
| `DELETE /api/matches/{id}` | `:56-60` | `MatchService.java:60-63` | `delete(requireOwned(id))` |
| `GET /api/game-plans/stream?opponent=` (SSE) | `match/gameplan/GamePlanController.java:26-57` | `GamePlanService.java:32-43` | repo `findByUserIdAndOpponentName...` filters by `userId` |

**Cross-tenant miss → 404 (deliberate, enumeration-safe).**
- `match/MatchNotFoundException.java` → mapped in `user/ApiExceptionHandler.java:39-43` with `@ResponseStatus(HttpStatus.NOT_FOUND)`. Returns the *same* 404 for "doesn't exist" and "exists but not yours" — correct IDOR posture; the plan should assert **404, not 403**, and treat that as the intended contract.
- Global gate: `.anyRequest().authenticated()` in the security config means unauthenticated requests get **401** before reaching the controller.

**`edit-delete-match` slice (the new paths).** get-one/update/delete were the new endpoints; all three use the same `requireOwned()` chokepoint as any pre-existing path — no weaker check. No IDOR gap introduced.

**Verdict (#1): no IDOR vulnerability in current code.** The risk to test is *regression* — a future endpoint or a refactor that bypasses `requireOwned`/`findByUserId`.

### Risk #1 — Existing test coverage (what NOT to re-add)

`src/test/java/.../match/MatchApiIntegrationTests.java` (21 `@Test` methods) already covers:
- `playerBCannotSeePlayerAsMatches:167` — B's **unfiltered list** is empty AND B's **filtered** list (`.param("opponent","Kowalski")`, line 180) is empty. *(So filtered-list cross-tenant IS covered.)*
- `opponentsReturnsOnlyCallerOwned:217` — opponents endpoint is caller-scoped.
- `crossUserGetReturns404:363`, `crossUserUpdateReturns404:373`, `crossUserDeleteReturns404:386` — the three by-id IDOR gates.
- `anonymousGetMatchesReturns401:186`, `anonymousUpdateReturns401:398`, `anonymousDeleteReturns401:407`.
- `match/gameplan/GamePlanApiIntegrationTests.java:141` — `stream_ownershipBoundary_userBCannotGetUserAsPlan` → 404.

**#1 gap worth Phase 1 effort:** the above are individually hand-written. The durable addition is a **table-/parameterized-driven sweep** over every match-by-id route (`GET`, `PUT`, `DELETE` — and any future one) asserting Player B → 404 for Player A's id, so a newly-added endpoint that forgets `requireOwned` fails the suite by construction. (Plus, optionally, a parameterized anonymous → 401 sweep.) This directly answers the brief's "every match endpoint" and "not just the happy owner path."

### Risk #3 — parse → preview → confirm → persist wiring

**Parse is preview-only and side-effect-free.**
- `match/MatchController.java:31-34` — `POST /api/matches/parse` takes `ParseMatchRequest{ String text }`, returns `MatchParseResult` (structured preview).
- `match/MatchParseService.java:26-30` — reads known opponents (`findDistinctOpponentNamesByUserId`), builds a prompt, calls `llmClient.generateStructured(...)`, returns. **No `save` call.** Confirmed: parsing never writes a match.

**Save accepts the confirmed structured payload — never re-parses.**
- `match/MatchController.java:25-29` — `POST /api/matches` takes `@Valid CreateOrUpdateMatchRequest` (the confirmed preview), not raw text. The LLM is not on this path. Same DTO + copy logic reused by `PUT /api/matches/{id}` (`:51-54`).
- `match/dto/CreateOrUpdateMatchRequest.java:13-18` — `{ opponentName, matchDate, notes, List<SetScoreRequest> sets }`; `SetScoreRequest{ playerScore, opponentScore }` (no `setNumber`).

**Request → entity mapping (verbatim carry-through).**
- `match/MatchService.java:25-36` (create) copies `opponentName`, `matchDate`, `notes` verbatim; `applySets` (`:84-93`) copies each set's `playerScore`/`opponentScore` verbatim and **re-derives `setNumber = index+1`** (client does not supply it).
- Server-owned fields excluded from any confirmed==saved assertion: `id` (DB), `userId` (from principal, `:28`), `createdAt` (`@PrePersist`, `Match.java:36-41`), and `MatchSet.setNumber` (derived from list order).

**No bypass of the confirm gate.** Only two `matchRepository.save(...)` callers exist — `MatchService.create` (`:35`) and `MatchService.update` (`:57`) — both fed exclusively by `CreateOrUpdateMatchRequest`. No import/seed/admin write path. `MatchParseService` and `GamePlanService` are read-only. The "confirm gate" is a client-side contract: the server simply exposes parse and create as independent endpoints and trusts the structured create payload; it does not cryptographically bind a save to a prior parse (so a #3 test asserts *structural separation + verbatim persistence*, not a cryptographic link).

### Risk #3 — Existing test coverage & the real gap

- `createMatchReturns201AndDerivedScores:75-88` asserts only the **POST response body**: `opponentName`, derived `setsWon`/`setsLost`/`result`, `sets.length()`. The response is built from the same in-transaction saved entity (`MatchResponse.from(matchRepository.save(match))`), so it does **not** prove a fresh read returns the confirmed values, and it never checks `notes`, `matchDate`, or exact per-set scores/order.
- `ownerGetByIdReturnsMatch:263`, `ownerUpdateChangesFieldsAndDerivedScores:278` (PUT then re-GET at `:306`), and `updateShrinksSetsWithOverlappingSetNumbers:327` (re-GET at `:355`) do re-read, but assert derived/changed fields, not a full confirmed==saved equality.

**#3 gaps worth Phase 1 effort (both non-tautological):**
1. **Confirmed==saved via fresh read.** POST a `CreateOrUpdateMatchRequest` with distinctive values (incl. non-empty `notes`, a specific `matchDate`, and ≥2 sets with asymmetric scores), then **re-GET** the new id (fresh request/transaction) and assert field-by-field equality against the *posted* input: `opponentName`, `matchDate`, `notes`, and per-set `playerScore`/`opponentScore` **in posted order**. Source the expected values from the test's own input constants — **not** from any parser output — to avoid the tautology the brief warns about. (Same shape applies to `PUT` for the update path.)
2. **Preview is side-effect-free / no save bypasses confirm.** Call `POST /api/matches/parse` (LLM stubbed) and assert that the caller's match list is **still empty** afterward — i.e. previewing never persists. This locks "no silent mis-save via the parse path."

## Code References

- `src/main/java/org/borowiec/squashprogresstracker/match/MatchController.java:25-60` — all match endpoints (create/list/opponents/get/update/delete) + `parse` at `:31-34`
- `src/main/java/org/borowiec/squashprogresstracker/match/MatchService.java:79-82` — `requireOwned()` ownership chokepoint
- `src/main/java/org/borowiec/squashprogresstracker/match/MatchService.java:25-36`,`:43-58`,`:65-77` — create / update / list+opponents
- `src/main/java/org/borowiec/squashprogresstracker/match/MatchRepository.java:12-22` — `findByUserId...`, `findByIdAndUserId`, opponents JPQL
- `src/main/java/org/borowiec/squashprogresstracker/match/MatchParseService.java:26-30` — parse path (no persistence)
- `src/main/java/org/borowiec/squashprogresstracker/match/dto/CreateOrUpdateMatchRequest.java:13-18`, `SetScoreRequest.java`, `MatchParseResult.java`, `ParseMatchRequest.java` — DTO shapes
- `src/main/java/org/borowiec/squashprogresstracker/match/Match.java:10-41`, `MatchSet.java` — entity, `@Table(name="matches")`, server-owned fields
- `src/main/java/org/borowiec/squashprogresstracker/security/CurrentUser.java:14-24`, `AppUserDetails.java` — identity resolution
- `src/main/java/org/borowiec/squashprogresstracker/user/ApiExceptionHandler.java:39-43` — `MatchNotFoundException` → 404
- `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanService.java:32-43` — game-plan ownership filter
- `src/main/resources/db/migration/V2__create_matches.sql:1-20` — `matches` table, `user_id BIGINT NOT NULL REFERENCES users(id)`, owner-scoped indexes
- **Tests (existing):** `src/test/java/.../match/MatchApiIntegrationTests.java` — IDOR gates at `:167,:217,:363,:373,:386`, anon at `:186,:398,:407`, create-echo at `:75`; `.../match/gameplan/GamePlanApiIntegrationTests.java:141`

## Architecture Insights

- **One chokepoint = one place to test the contract.** Because all by-id access flows through `requireOwned`, a parameterized regression sweep is both cheap and high-signal: it guards the chokepoint against being bypassed by future endpoints. This is the cost×signal-winning move over adding more one-off cross-user tests.
- **404-not-403 is an intentional security choice** (no id enumeration). The plan should encode it as the expected status, not "flag it as odd."
- **Parse/save separation is the structural guarantee behind #3.** The meaningful test is "preview persists nothing" + "saved == confirmed payload on fresh read," sourced from test inputs — not from the LLM/parser, which would green-light a re-parse bug.
- **Established integration harness to reuse (no base class):** copy `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` + `@Container @ServiceConnection static PostgreSQLContainer<>("postgres:17")`. Auth is **session-based**, not `@WithMockUser`: a `registerAndLogin(email)` helper registers + logs in and returns a `MockHttpSession`; two distinct sessions (A, B) drive cross-tenant tests. Mutations require `.with(csrf())`. The LLM edge is stubbed with `@MockitoBean LlmClient`. (Per `MatchApiIntegrationTests.java:24-70`.)
- **Naming/run:** `<ClassName>Tests.java`; `./mvnw test -Dtest=ClassName` (single method: `-Dtest=ClassName#method`). Live LLM smoke tests are env-gated and must never run in CI.

## Historical Context (from prior changes)

- `edit-delete-match` (recent commits `5bdd365`/`d80a090`) added get-one/update/delete-by-id and their cross-user 404 tests — this research confirms those new paths reuse the `requireOwned` chokepoint, so Phase 1 inherits a partially-tested surface rather than an untested one.
- `context/foundation/lessons.md` — relevant priors for the eventual plan/implement: *"Never use fully qualified class names when an import suffices"* (flagged in prior `MatchParse*Tests` review) and *"Keep Squash MVP project board in sync"* (issue #11 → In Progress on implement). No Docker/host.docker.internal concerns at this layer (Phase 1 is MockMvc + Testcontainers, no sibling-container networking).

## Related Research

- `context/foundation/test-plan.md` §2 (risk map), §4 (stack), §6.2/§6.4 (cookbook entries this phase must fill).
- No prior `research.md` exists under `context/archive/` (archive holds only `README.md`).

## Open Questions

- **Where should the new tests live** — extend `MatchApiIntegrationTests` (consistent harness, one more file in git) or a dedicated `MatchOwnershipBoundaryTests` / `MatchNoMisSaveTests` (clearer intent, isolates the contract sweep)? A plan-time call; the parameterized sweep argues mildly for a dedicated class.
- **Should the game-plan SSE endpoint be folded into the #1 parameterized sweep**, or stay covered in `GamePlanApiIntegrationTests`? It is already covered; the sweep would mainly future-proof it.
- **Confirm assertion granularity for sets** — assert the full ordered set list equals input, and explicitly assert `setNumber == index+1` is server-derived (so a test doesn't wrongly expect a client-supplied number).
