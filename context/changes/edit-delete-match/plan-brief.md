# Edit & Delete Match — Plan Brief

> Full plan: `context/changes/edit-delete-match/plan.md`

## What & Why

Let a signed-in player edit and delete their own saved match records (PRD FR-008/FR-009, roadmap S-04). Basic data hygiene: fixing a score typo or removing a wrong entry shouldn't require delete-and-re-log, and a mistyped date/opponent shouldn't be permanent.

## Starting Point

Match CRUD today supports only create, list, and list-opponents (`MatchController`). The backend is already seeded for this slice: `MatchRepository.findByIdAndUserId` exists (the ownership-safe single fetch, flagged "seeds S-04"), `CurrentUser.currentUserId()` scopes every query, and `ApiExceptionHandler` already maps a domain not-found to 404. The React frontend has a working match form (inside `LogMatchPage`) and read-only history cards with no actions and no modal pattern.

## Desired End State

Three new ownership-scoped endpoints (`GET/PUT/DELETE /api/matches/{id}`). On `/history`, each card gains Edit (→ prefilled `/matches/:id/edit`) and Delete (inline two-step confirm). A player can never touch another player's match — all three endpoints return 404 for a non-owned id. The create flow is unchanged, now sharing one form component.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Edit UI shape | Extract shared `MatchForm`, used by create + edit | One source of truth; edit/create can't drift | Plan |
| Edit scope | All fields (opponent, date, notes, sets) | FR-008 literally; reuses full form | Plan |
| Form prefill | `GET /api/matches/{id}` | Deep-linkable, refresh-safe; uses seeded fetch | Plan |
| Delete UX | Inline two-step confirm on card | No modal infra; matches app's inline style | Plan |
| Action placement | On history cards | History is the natural management surface | Plan |
| Non-owned id response | 404 (not 403) | Doesn't leak existence; matches existing handler | Plan |
| Update DTO | Reuse one record, renamed `CreateOrUpdateMatchRequest` | Identical validation for free; PUT = full replace | Plan |
| Test depth | Ownership boundary + CRUD happy/sad | Proves the hard rule + the orphan-removal path | Plan |

## Scope

**In scope:** get-one/update/delete API; `MatchNotFoundException`→404; DTO rename; shared `MatchForm`; `EditMatchPage` + route; edit/delete actions on history; ownership + set-replacement tests.

**Out of scope:** match detail page; modal component; PATCH/partial update; optimistic concurrency; soft-delete/audit; pagination; any schema migration.

## Architecture / Approach

Backend extends the existing service/controller/exception pattern — all three new methods load through `findByIdAndUserId(id, currentUserId())` and throw `MatchNotFoundException` (→404) on miss. Update does a full replacement, mutating the managed `sets` collection (orphanRemoval) with a flush between clear and re-insert. Frontend extracts the form, builds a prefilled edit page, and adds restrained card actions.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend API | get/update/delete endpoints + 404 + tests | JPA set-replacement flush ordering vs the `(match_id, set_number)` unique constraint |
| 2. Edit flow (FE) | Shared `MatchForm` + prefilled `EditMatchPage` | Refactoring the working create page without behavior drift |
| 3. Delete actions (FE) | Edit/Delete on cards, inline confirm | Per-card confirm/busy state; keeping cards uncluttered |

**Prerequisites:** F-01 (auth) and S-01 (match + history) — both complete.
**Estimated effort:** ~1–2 sessions across 3 phases.

## Open Risks & Assumptions

- The set-replacement flush gotcha is the one real backend trap — a dedicated test (4 sets → 2 overlapping) guards it.
- Refactoring `LogMatchPage` into `MatchForm` must keep create behavior identical; verified manually in Phase 2.
- Last-write-wins is acceptable (single-owner data; no concurrent-editor scenario).

## Success Criteria (Summary)

- A player can edit any field of, and delete, their own matches from history.
- Cross-user edit/delete/get is impossible — 404 every time (`./mvnw test` proves it).
- The create flow and history read view are visibly unchanged apart from the new card actions.
