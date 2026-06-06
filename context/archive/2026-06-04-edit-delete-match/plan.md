# Edit & Delete Match Implementation Plan

## Overview

Let a signed-in player **edit** and **delete** their own saved match records (PRD FR-008, FR-009; roadmap slice S-04). This extends the existing match CRUD — which today only supports create, list, and list-opponents — with ownership-scoped get-one, update, and delete operations on the backend, then surfaces an edit flow and an inline-confirm delete on the history page. No new architecture: it rides the established `CurrentUser` ownership boundary, the `ApiExceptionHandler` pattern, and the existing React form/history components.

## Current State Analysis

What exists today (`manual-match-and-history`, S-01):

- **Entity** `Match.java:11` (`@Table("matches")`) with `userId`, `opponentName`, `matchDate`, `notes`, `createdAt`, and `@OneToMany(mappedBy="match", cascade=ALL, orphanRemoval=true)` `sets` ordered by `setNumber` (`Match.java:32`). `addSet(...)` wires the back-reference (`Match.java:43`).
- **Repository** `MatchRepository.java` already exposes the ownership-safe single fetch `Optional<Match> findByIdAndUserId(Long id, Long userId)` (`:19`, `@EntityGraph("sets")`) — explicitly noted in the S-01 plan as "seeds S-04". Plus the list/opponent finders.
- **Service** `MatchService.java` resolves the owner from `currentUser.currentUserId()` on every call (`:25,:44,:53`) and never trusts a client id. `create` builds sets by 1-based index (`:30-37`).
- **Controller** `MatchController.java` (`/api/matches`) has only `POST`, `GET`, `GET /opponents`.
- **DTOs** `CreateMatchRequest` (validation: `@NotBlank`/`@Size(255)` opponent, `@NotNull`/`@PastOrPresent` date, `@Size` notes, `@NotEmpty`/`@Size(max=5)`/`@Valid` sets; each set score `@NotNull`/`@Min(0)`/`@Max(99)`), `MatchResponse.from(Match)` (derives `setsWon`/`setsLost`/`result`).
- **Exception handling** `ApiExceptionHandler.java` maps `GamePlanUnavailableException → 404` with a clean message — the template for a `MatchNotFoundException`.
- **Frontend** React + Vite. `LogMatchPage.tsx` holds the match form (opponent/date/notes + 1–5 dynamic set rows + live result). `HistoryPage.tsx` renders read-only match cards (no actions). `api/matches.ts` has `createMatch`/`listMatches`/`listOpponents`. `client.ts` auto-attaches `X-XSRF-TOKEN` on mutating requests. Routes in `App.tsx:14-24` under `ProtectedRoute`. **No modal/confirm-dialog pattern exists anywhere.**
- **Tests** `MatchApiIntegrationTests.java` — Testcontainers + MockMvc, `registerAndLogin(email)` helper, CSRF via `.with(csrf())`, ownership hard-rule tests already present for list/opponents.

## Desired End State

- API: `GET /api/matches/{id}` (200 owner / 404 otherwise), `PUT /api/matches/{id}` (200 full replacement / 404 / 400 on validation), `DELETE /api/matches/{id}` (204 / 404). Every one scoped through `findByIdAndUserId`.
- A player on `/history` can click **Edit** on any of their match cards, land on `/matches/:id/edit` with the form prefilled, change any field (opponent, date, notes, sets), save, and return to an updated history. They can click **Delete**, confirm inline, and watch the card disappear.
- A player can never read, edit, or delete another player's match — all three new endpoints return **404** for a non-owned or non-existent id.
- The create path (`/matches/new`) behaves exactly as before, now powered by a shared form component.

Verify: `./mvnw test` green (incl. new ownership + CRUD tests); manual walk-through of edit and delete on the running app.

### Key Discoveries:

- `findByIdAndUserId` already exists (`MatchRepository.java:19`) — no new repository method needed for ownership-safe fetch.
- The `404-for-not-owned` decision is already idiomatic here: `ApiExceptionHandler` maps a domain not-found to 404 (`GamePlanUnavailableException`). Reusing this avoids leaking existence of other users' records by id.
- `orphanRemoval=true` on `sets` (`Match.java:32`) makes "replace all sets on update" the natural mechanism — but there is a JPA flush-ordering gotcha against the `uq_match_sets_match_set (match_id, set_number)` constraint (see Critical Implementation Details).
- The existing form lives inside `LogMatchPage.tsx` as local state + JSX — extractable into a `MatchForm` that takes initial values + an `onSubmit`, leaving create/edit pages thin.

## What We're NOT Doing

- No dedicated match **detail** page — edit/delete actions live on the history cards.
- No modal/overlay component — delete uses an **inline two-step** confirm.
- No partial-update (PATCH) semantics — `PUT` is a full replacement of the match.
- No optimistic-concurrency / version field — last write wins (single-owner data, concurrent self-edits are not a real scenario).
- No change to `createdAt` (immutable, `updatable=false`) and no new migration — the schema is unchanged.
- No pagination, soft-delete, or audit trail.

## Implementation Approach

Backend first (Phase 1): rename the shared DTO, add the not-found exception + handler mapping, add three service methods and three controller endpoints, and prove the ownership boundary and the set-replacement path with integration tests. Then frontend in two slices: extract the shared form and build the edit flow (Phase 2), then add the delete action with inline confirm to history (Phase 3). Each frontend phase keeps the app shippable on its own.

## Critical Implementation Details

- **Set-replacement flush ordering (Phase 1, update path).** `match_sets` has `UNIQUE (match_id, set_number)`. When `update` replaces the set collection, naively clearing and re-adding rows that reuse the same `set_number` values can make Hibernate attempt the new INSERTs before the orphan DELETEs in one flush, violating the unique constraint. Mutate the *managed* collection (`match.getSets().clear()` then `addSet(...)` per request set, re-numbering 1..N by index) and force an `entityManager.flush()` (or `saveAndFlush`) **after the clear, before the inserts** so deletes are sent first. Do **not** reassign via `setSets(new list)` — that breaks orphanRemoval tracking. A test must replace a 4-set match with a 2-set match whose set numbers overlap to catch a regression here.

## Phase 1: Backend — get-one / update / delete API

### Overview

Add ownership-scoped read-one, update, and delete to the match domain, with a 404 for non-owned/non-existent ids, and rename the create DTO to reflect its dual create/update role.

### Changes Required:

#### 1. Rename the request DTO

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/dto/CreateMatchRequest.java` → `CreateOrUpdateMatchRequest.java`

**Intent**: The same validated body now drives both create and update; rename to say so. Rename the record + file and update all references in `MatchController.java` and `MatchService.java` (the only two referencing files).

**Contract**: Record name `CreateOrUpdateMatchRequest` with identical fields and validation annotations as today. No field or rule changes.

#### 2. Match-not-found exception

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchNotFoundException.java` (new)

**Intent**: A domain exception thrown when a get/update/delete targets an id not owned by (or not existing for) the current user.

**Contract**: `RuntimeException` subclass, mirrors `GamePlanUnavailableException`'s shape.

#### 3. Exception → 404 mapping

**File**: `src/main/java/org/borowiec/squashprogresstracker/user/ApiExceptionHandler.java`

**Intent**: Map `MatchNotFoundException` to a 404 with a generic message that does not reveal whether the record exists for another user.

**Contract**: New `@ExceptionHandler(MatchNotFoundException.class) @ResponseStatus(NOT_FOUND)` returning `ApiError.of(404, "Match not found")`, following the existing `handleGamePlanUnavailable` pattern.

#### 4. Service: get / update / delete

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchService.java`

**Intent**: Add `get(id)`, `update(id, request)`, `delete(id)` — each loading via `findByIdAndUserId(id, currentUser.currentUserId())` and throwing `MatchNotFoundException` on miss. `get` is read-only and returns `MatchResponse`. `update` replaces opponent/date/notes and the set collection (see Critical Implementation Details for the flush ordering), returns the updated `MatchResponse`. `delete` removes the match (cascade/orphanRemoval clears its sets).

**Contract**:
- `@Transactional(readOnly = true) MatchResponse get(Long id)`
- `@Transactional MatchResponse update(Long id, CreateOrUpdateMatchRequest request)`
- `@Transactional void delete(Long id)`
- All three resolve owner via `currentUser.currentUserId()`; none accept a client-supplied user id.

#### 5. Controller: three endpoints

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchController.java`

**Intent**: Expose the three service methods under `/api/matches/{id}`.

**Contract**:
- `@GetMapping("/{id}") MatchResponse get(@PathVariable Long id)` → 200
- `@PutMapping("/{id}") MatchResponse update(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateMatchRequest request)` → 200
- `@DeleteMapping("/{id}") @ResponseStatus(NO_CONTENT) void delete(@PathVariable Long id)` → 204

#### 6. Integration tests

**File**: `src/test/java/org/borowiec/squashprogresstracker/match/MatchApiIntegrationTests.java`

**Intent**: Extend the existing suite (reuse `registerAndLogin`, `VALID_MATCH`, `.with(csrf())`) to cover the new endpoints' happy/sad paths and the ownership hard rule.

**Contract**: New tests — owner `GET /{id}` returns the match (200); owner `PUT /{id}` updates fields and derived scores (200); owner `DELETE /{id}` returns 204 and a subsequent `GET /{id}` returns 404; **cross-user `GET`/`PUT`/`DELETE` of another player's id each return 404**; anonymous `PUT`/`DELETE` return 401; `PUT` with invalid body returns 400 with `fieldErrors`; **set-replacement test**: create a 4-set match, `PUT` it down to 2 sets with overlapping set numbers, assert 200 and exactly 2 persisted sets with correct derived result (guards the flush-ordering gotcha).

### Success Criteria:

#### Automated Verification:

- Build compiles: `./mvnw -q compile`
- Full test suite passes: `./mvnw test`
- New ownership tests pass: `./mvnw test -Dtest=MatchApiIntegrationTests`

#### Manual Verification:

- `PUT`/`DELETE` of a non-owned id returns 404 (e.g. via curl with two sessions), not 403 or 200
- Updating a match's sets does not leave orphaned `match_sets` rows in the DB

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Frontend — shared form + edit flow

### Overview

Extract the match form into a reusable component, add get/update API wrappers, and build the prefilled edit page at `/matches/:id/edit` — without changing create behavior.

### Changes Required:

#### 1. Extract shared form component

**File**: `frontend/src/components/MatchForm.tsx` (new), `frontend/src/pages/LogMatchPage.tsx`

**Intent**: Move the form markup, set-row state, validation/error display, and live-result UI out of `LogMatchPage` into a `MatchForm` that accepts initial values, a submit handler, and a submit-button label. `LogMatchPage` becomes a thin wrapper that calls `createMatch` and navigates to `/history`.

**Contract**: `MatchForm` props — `initial?: { opponentName, matchDate, notes, sets }`, `onSubmit(payload: CreateOrUpdateMatchRequest): Promise<void>`, `submitLabel: string`. It owns local form state, `fieldErrors`/`globalError`/`busy`, and maps `AxiosError<ApiError>` exactly as `LogMatchPage` does today. Create page must remain byte-for-byte equivalent in behavior.

#### 2. API wrappers: get + update

**File**: `frontend/src/api/matches.ts`

**Intent**: Add `getMatch(id)` and `updateMatch(id, data)`. Optionally rename `CreateMatchRequest` TS interface to `CreateOrUpdateMatchRequest` (with a re-export alias to avoid churn) to mirror the backend.

**Contract**:
- `getMatch(id: number): Promise<MatchResponse>` → `GET /api/matches/{id}`
- `updateMatch(id: number, data: CreateOrUpdateMatchRequest): Promise<MatchResponse>` → `PUT /api/matches/{id}`

#### 3. Edit page + route

**File**: `frontend/src/pages/EditMatchPage.tsx` (new), `frontend/src/App.tsx`

**Intent**: New page reads `:id` from the route, fetches via `getMatch`, maps the `MatchResponse` (sets → form `SetRow`s as strings) into `MatchForm`'s `initial`, and on submit calls `updateMatch` then navigates to `/history`. Handle the load state and a 404 (match gone / not owned) with a friendly message + link back to history. Register `/matches/:id/edit` inside the `ProtectedRoute` block.

**Contract**: Route `<Route path="/matches/:id/edit" element={<EditMatchPage />} />` under `ProtectedRoute` in `App.tsx:17-22`. On `GET` 404, render an inline "Match not found" state rather than crashing.

### Success Criteria:

#### Automated Verification:

- Frontend builds: `cd frontend && npm run build`

#### Manual Verification:

- `/matches/new` still creates a match exactly as before (form, validation errors, live result, redirect)
- Clicking Edit (added in Phase 3) — or navigating directly to `/matches/:id/edit` for an owned match — shows the form prefilled with current values
- Editing any field (opponent, date, notes, add/remove sets) and saving persists the change and returns to an updated history
- Visiting `/matches/:id/edit` for a non-owned/nonexistent id shows the not-found state, not a crash

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Frontend — history card actions (edit + inline-confirm delete)

### Overview

Add Edit and Delete actions to each history card; delete uses an inline two-step confirm with a busy state and refetches on success.

### Changes Required:

#### 1. Delete API wrapper

**File**: `frontend/src/api/matches.ts`

**Intent**: Add `deleteMatch(id)`.

**Contract**: `deleteMatch(id: number): Promise<void>` → `DELETE /api/matches/{id}`.

#### 2. Card actions on history

**File**: `frontend/src/pages/HistoryPage.tsx`

**Intent**: Add an actions row/area to each match card with an **Edit** action (navigate to `/matches/:id/edit`) and a **Delete** action that swaps in place to a "Confirm? / Cancel" pair; confirming calls `deleteMatch`, shows a busy state, and on success refetches the current (possibly filtered) list. Track which card is in confirm/busy state by match id so only the targeted card changes. Surface a delete failure with a non-destructive inline error.

**Contract**: Per-card local UI state keyed by `match.id` (e.g. `confirmingId`, `deletingId`). Edit styled restrained (text/teal), Delete styled with `var(--error)` per the existing color tokens. Success handler reuses the existing `listMatches(filter || undefined)` refetch path; preserve the active opponent filter.

### Success Criteria:

#### Automated Verification:

- Frontend builds: `cd frontend && npm run build`

#### Manual Verification:

- Each history card shows Edit and Delete actions without dominating the read view
- Delete shows an inline Confirm/Cancel step (no browser dialog, no full-page modal); Cancel aborts
- Confirming delete removes the match and the list refreshes with the filter intact
- Edit navigates to the prefilled edit page for that match
- A failed delete shows an inline error and leaves the card in place

**Implementation Note**: After this phase and all automated verification passes, pause for final manual confirmation.

---

## Testing Strategy

### Unit / Integration Tests (backend):

- Owner get-one / update / delete happy paths (200/200/204)
- Cross-user get/update/delete → 404 each (auth-boundary hard rule)
- Anonymous update/delete → 401
- Update with invalid body → 400 + `fieldErrors`
- Set-replacement: 4 sets → 2 sets with overlapping set numbers persists exactly 2 sets and correct derived result (flush-ordering guard)

### Manual Testing Steps:

1. Log in as player A, create a match, edit every field, confirm history reflects it.
2. Delete a match via inline confirm; confirm it disappears and the filter persists.
3. Direct-navigate to `/matches/:id/edit` and refresh — page reloads correctly (GET-by-id prefill).
4. As player B, attempt `PUT`/`DELETE`/`GET` on player A's match id (curl) → 404 each.
5. Confirm `/matches/new` create flow is unchanged.

## Performance Considerations

Negligible — single-row operations scoped by indexed `(id, user_id)`. The set-replacement extra flush is per-edit and trivial.

## Migration Notes

No schema migration. `created_at` is immutable and untouched by update.

## References

- Roadmap slice: `context/foundation/roadmap.md` (S-04)
- PRD: `context/foundation/prd.md` (FR-008, FR-009, Access Control)
- Prior plan this extends: `context/changes/manual-match-and-history/plan.md`
- Ownership primitive: `src/main/java/org/borowiec/squashprogresstracker/security/CurrentUser.java:14`
- Seeded fetch: `src/main/java/org/borowiec/squashprogresstracker/match/MatchRepository.java:19`
- 404 handler pattern: `src/main/java/org/borowiec/squashprogresstracker/user/ApiExceptionHandler.java`
- Existing tests to extend: `src/test/java/org/borowiec/squashprogresstracker/match/MatchApiIntegrationTests.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend — get-one / update / delete API

#### Automated

- [x] 1.1 Build compiles: `./mvnw -q compile` — a595e91
- [x] 1.2 Full test suite passes: `./mvnw test` — a595e91
- [x] 1.3 New ownership tests pass: `./mvnw test -Dtest=MatchApiIntegrationTests` — a595e91

#### Manual

- [x] 1.4 PUT/DELETE of a non-owned id returns 404 (two sessions), not 403 or 200 — a595e91
- [x] 1.5 Updating a match's sets leaves no orphaned `match_sets` rows — a595e91

### Phase 2: Frontend — shared form + edit flow

#### Automated

- [x] 2.1 Frontend builds: `cd frontend && npm run build` — cc1d139

#### Manual

- [x] 2.2 `/matches/new` still creates a match exactly as before — cc1d139
- [x] 2.3 Edit page shows the form prefilled with current values — cc1d139
- [x] 2.4 Editing any field and saving persists and returns to updated history — cc1d139
- [x] 2.5 `/matches/:id/edit` for a non-owned/nonexistent id shows the not-found state, not a crash — cc1d139

### Phase 3: Frontend — history card actions (edit + inline-confirm delete)

#### Automated

- [x] 3.1 Frontend builds: `cd frontend && npm run build` — f996d4b

#### Manual

- [x] 3.2 Each card shows Edit and Delete without dominating the read view — f996d4b
- [x] 3.3 Delete shows an inline Confirm/Cancel step; Cancel aborts — f996d4b
- [x] 3.4 Confirming delete removes the match and refreshes with filter intact — f996d4b
- [x] 3.5 Edit navigates to the prefilled edit page — f996d4b
- [x] 3.6 A failed delete shows an inline error and leaves the card in place — f996d4b
