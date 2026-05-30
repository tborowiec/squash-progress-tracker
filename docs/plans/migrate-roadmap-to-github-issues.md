# Migrate roadmap.md → GitHub Issues

## Context

`context/foundation/roadmap.md` holds the MVP roadmap as 2 foundations (F-01, F-02), 4 slices (S-01–S-04), 1 Open Roadmap Question (LLM-provider decision), and 4 Parked non-goals. This plan turns it into a tracked backlog in the project's **task-management system: GitHub Issues**, driven by the `gh` CLI.

**Verified live state:** `gh` 2.93.0, authed as `tborowiec`, token scopes `repo, admin:public_key, gist, read:org`. Repo `tborowiec/squash-progress-tracker`, Issues enabled. Only the 9 default labels exist; **no milestones, no issues** (clean slate). Token has **no `project` scope**, so a GitHub Projects v2 board is out of scope here (would need `gh auth refresh -s project`) — Issues + labels + milestone is the plan.

**Outcome:** 7 work/decision issues + 1 parent tracking issue, under an `MVP v1` milestone, labeled by type/stream/status, with prerequisite edges encoded as "Blocked by #N" references (which auto-create backlinks on the prerequisite issue).

## Decisions locked with user

- **Scope:** Foundations + slices + the provider decision = 7 issues. Parked non-goals are listed as a note inside the tracking issue, not issues.
- **Structure:** one `MVP v1` milestone + labels + a parent `Roadmap: MVP` tracking issue with a per-stream checklist.
- **Dependencies:** body "Blocked by #N" refs + a `blocked` label on items gated by the provider decision. (Native GitHub issue-dependencies API is an optional later enhancement, not used here.)

## Execution

All steps via `gh` from the repo root. The migration is a single bash script that captures each created issue's number into a variable so cross-references resolve deterministically (no hard-coded numbers).

### Step 1 — Create labels (`gh label create --force`)

| Label | Color | Applied to |
| --- | --- | --- |
| `type: foundation` | `1d76db` | F-01, F-02 |
| `type: slice` | `0e8a16` | S-01–S-04 |
| `north-star` | `fbca04` | S-02 |
| `stream: account-match-log` | `c5def5` | F-01, S-01, S-04 |
| `stream: ai-value-loop` | `bfd4f2` | F-02, S-02, S-03 |
| `status: ready` | `0e8a16` | F-01 |
| `status: proposed` | `fef2c0` | F-02, S-01–S-04 |
| `decision` | `d876e3` | provider-decision issue |
| `blocked` | `b60205` | F-02, S-02, S-03 (gated by provider decision) |

### Step 2 — Create the milestone

`gh api repos/{owner}/{repo}/milestones -f title='MVP v1' -f description='MVP roadmap derived from context/foundation/roadmap.md — speed-biased, 2 foundations + 4 slices.'`
Then pass `--milestone 'MVP v1'` (by title) on each issue.

### Step 3 — Create issues in dependency order (each body via `--body-file -` heredoc)

Creation order so every "Blocked by" ref points at an already-created number:

1. **Tracking** `Roadmap: MVP — Squash Progress Tracker` — placeholder body (filled in Step 4). Labels: `documentation`. Milestone: `MVP v1`.
2. **Decision** `Decision: choose LLM provider + integration path` — from Open Roadmap Question 1. Labels: `decision`. Body: the question, owner (user, leaning Gemini), what it blocks (F-02 → S-02, S-03), and that the client can be planned provider-agnostically first. Capture `N_DEC`.
3. **F-01** `F-01 minimal-auth: email+password auth & per-player ownership boundary`. Labels: `type: foundation`, `stream: account-match-log`, `status: ready`. No prereqs. Capture `N_F01`.
4. **F-02** `F-02 llm-client: provider-agnostic LLM client`. Labels: `type: foundation`, `stream: ai-value-loop`, `status: proposed`, `blocked`. Body "Blocked by #N_DEC". Capture `N_F02`.
5. **S-01** `S-01 manual-match-and-history: manual logging form + history`. Labels: `type: slice`, `stream: account-match-log`, `status: proposed`. "Blocked by #N_F01". Capture `N_S01`.
6. **S-02** `S-02 ai-game-plan: AI game plan for an opponent (north star)`. Labels: `type: slice`, `stream: ai-value-loop`, `status: proposed`, `north-star`, `blocked`. "Blocked by #N_F01, #N_S01, #N_F02". Capture `N_S02`.
7. **S-03** `S-03 ai-match-entry: natural-language match entry w/ AI preview`. Labels: `type: slice`, `stream: ai-value-loop`, `status: proposed`, `blocked`. "Blocked by #N_F01, #N_S01, #N_F02". Capture `N_S03`.
8. **S-04** `S-04 edit-delete-match: edit & delete a saved match`. Labels: `type: slice`, `stream: account-match-log`, `status: proposed`. "Blocked by #N_F01, #N_S01". Capture `N_S04`.

**Per-issue body template** (filled from the roadmap fields verbatim):

```
**Roadmap ID:** F-01 · **Change ID:** `minimal-auth`

**Outcome:** <Outcome line>

**PRD refs:** <refs>
**Prerequisites:** <Blocked by #N links, or —>
**Parallel with:** <roadmap IDs, or —>
**Unlocks:** <S-NN…>            ← foundations only
**Blockers:** <text, or —>
**Unknowns:** <text, or —>
**Risk:** <Risk line>
**Status:** <ready|proposed>

---
Source: `context/foundation/roadmap.md` (F-01). Plan with `/10x-plan minimal-auth`.
```

### Step 4 — Backfill the tracking issue (`gh issue edit N_TRACK --body-file -`)

Body: short vision recap + north-star line + a checklist grouped by stream using the captured numbers, e.g.

```
### Stream A — Account & match log
- [ ] #N_F01 F-01 minimal-auth (ready)
- [ ] #N_S01 S-01 manual-match-and-history
- [ ] #N_S04 S-04 edit-delete-match
### Stream B — AI value loop
- [ ] #N_DEC Decision: LLM provider (resolve first)
- [ ] #N_F02 F-02 llm-client
- [ ] #N_S02 S-02 ai-game-plan ⭐ north star
- [ ] #N_S03 S-03 ai-match-entry
### Parked (not planned for MVP)
- Mobile / mobile-browser support · Match sharing/social · Custom ML scoring · OAuth sign-in
```
Plus a link to `context/foundation/roadmap.md`.

## Critical files

- Read-only source: `context/foundation/roadmap.md` (the single source of truth; field values copied verbatim).
- No application files are modified — this writes only to GitHub via `gh`. The roadmap doc remains canonical.

## Verification

1. `gh issue list --milestone 'MVP v1' --state all` → 8 issues listed (7 work/decision + tracking).
2. `gh label list` → the 9 new labels present.
3. `gh issue view <N_S02>` → shows north-star label, `blocked` label, and "Blocked by #F-01/#S-01/#F-02" rendering as links; open the F-01 issue and confirm the backlink reference appears in its timeline.
4. `gh issue view <N_TRACK>` → checklist renders with all items grouped by stream, parked note present.
5. Spot-check one foundation and one slice body against the roadmap fields for fidelity.

## Rollback

If anything looks wrong: `gh issue delete <N> --yes` for each created issue, `gh api -X DELETE repos/{owner}/{repo}/milestones/<num>` for the milestone, `gh label delete <name> --yes` for labels. Nothing in the git repo changes, so there is no commit to revert.
