# Ownership-boundary & No-mis-save Backend Tests — Plan Brief

> Full plan: `context/changes/ownership-boundary-tests/plan.md`
> Research: `context/changes/ownership-boundary-tests/research.md`

## What & Why

Phase 1 of the test-plan rollout: backend integration tests that lock two
existing-but-under-tested contracts in `match/`. **#1 (IDOR):** one player must
never read/edit/delete another's match by id. **#3 (no mis-save):** the saved
record must equal the confirmed preview, and previewing must persist nothing.
Enforcement is already correct in production — this change makes it *regression-proof*.

## Starting Point

Production code is sound: every by-id route funnels through
`MatchService.requireOwned → findByIdAndUserId` (cross-tenant miss → **404**,
enumeration-safe), and parse/save are separate endpoints (save never re-parses).
Coverage exists but is hand-written per endpoint, and #3's two key checks
(confirmed==saved on a fresh read; parse-is-side-effect-free) are missing.

## Desired End State

Two dedicated test classes — `MatchOwnershipBoundaryTests` (a parameterized
route-table sweep so a future by-id endpoint forgetting `requireOwned` fails by
construction) and `MatchNoMisSaveTests` (confirmed==saved on re-GET + parse
persists nothing) — plus a filled §6.2/§6.4 cookbook and a corrected §4 stack note.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Test-file home | Two dedicated classes | Isolates the durable contract sweep from happy-path noise; matches cookbook "reference test" framing | Plan |
| #1 mechanism | Parameterized `@MethodSource` route table | A new by-id route gains IDOR coverage by adding one row, not a new test | Research |
| Cross-tenant status | Assert **404**, not 403 | Enumeration-safe IDOR posture is the intended contract | Research |
| "empty results on list/filter" | Existing-coverage ledger, not new tests | Those already pass; re-adding passing one-offs is research's named anti-pattern | Plan |
| Game-plan SSE | Stays in `GamePlanApiIntegrationTests` | Opponent-param (not by-id); breaks the uniform sweep shape; already covered | Plan |
| #3 oracle | Expected values from test constants | Sourcing from parser output would green-light a re-parse bug | Research |

## Scope

**In scope:** `MatchOwnershipBoundaryTests` (404 sweep + 401 sweep);
`MatchNoMisSaveTests` (confirmed==saved create + update; parse side-effect-free);
`test-plan.md` §6.2/§6.4 cookbook + §4 stack-note fix.

**Out of scope:** any production code change; re-adding passing cross-tenant
tests; folding SSE into the sweep; retry/backoff (Phase 2); a cryptographic
parse→save binding.

## Architecture / Approach

Copy the established harness from `MatchApiIntegrationTests` (`@SpringBootTest` +
`@AutoConfigureMockMvc` + `@Testcontainers` + `postgres:17`, session-based
`registerAndLogin` → `MockHttpSession`, `.with(csrf())` on mutations,
`@MockitoBean LlmClient` for the parse test). #1 uses a JUnit parameterized route
table; #3 sources every expected value from named test constants and re-GETs in a
fresh request.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Ownership sweep | `MatchOwnershipBoundaryTests` — 404 + 401 route-table sweeps | Asserting 403/200 instead of cross-tenant 404 |
| 2. No-mis-save | `MatchNoMisSaveTests` — confirmed==saved + parse side-effect-free | The oracle trap (expected from parser output) |
| 3. Cookbook + stack note | §6.2/§6.4 filled; §4 `@WithMockUser` corrected | Reference names drifting from created classes |

**Prerequisites:** existing backend suite + Testcontainers (present); set GitHub
issue #11 → In Progress on the Squash MVP board at implement start.
**Estimated effort:** ~1–2 sessions across 3 phases.

## Open Risks & Assumptions

- Assumes research's "no IDOR vuln / no mis-save bypass" holds; if a new test
  fails against current code, that is a finding to report, not a prod fix to slip in.
- Two new `@SpringBootTest` classes add container-startup cost (accepted for the
  integration tier).

## Success Criteria (Summary)

- A non-owner gets 404 (not 403) and an anonymous caller 401 on every by-id route — by construction for future routes.
- A re-GET of a saved match equals the confirmed input field-by-field (incl. notes + per-set order); a parse persists nothing.
- `test-plan.md` §6.2/§6.4 read as real cookbook entries and §4 no longer says `@WithMockUser`.
