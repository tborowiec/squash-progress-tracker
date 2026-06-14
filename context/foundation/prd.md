---
project: "Squash Progress Tracker"
version: 2
status: draft
created: 2026-05-20
updated: 2026-06-14
context_type: greenfield
product_type: web-app
target_scale:
  users: medium
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: null
  after_hours_only: true
---

## Vision & Problem Statement

Competitive squash players who track their ladder or club standings have no tool that turns their match history into a pre-match game plan. After each game they either log nothing — losing the data — or enter results manually into a spreadsheet that can't surface patterns or suggest tactics. The moment of pain arrives the night before a match against a known opponent: the player walks in blind because their history is either unrecorded or unanalysed.

The insight this product is built on: the friction of recording is what kills the data pipeline, and once data exists the real value is not a dashboard — it is a specific AI-generated game plan for the next match against that opponent. Recording is just fuel.

## User & Persona

**Primary persona:** Competitive/club-level squash player. Plays in a ladder, league, or club tournament structure. Has a stable set of recurring opponents. Motivated to improve and to win specific upcoming matches. Technically comfortable with a web app; does not need a mobile-native experience for MVP.

## Success Criteria

### Primary
A player can log a match via natural language text → AI parses it and shows a structured preview → player confirms → record is saved. The same player can select any opponent from their history and receive an AI-generated game plan for the next match against that opponent.

### Secondary
- Players use AI text entry for ≥75% of match logs, confirming AI entry is genuinely lower friction than the structured form.
- AI correctly parses ≥90% of naturally typed match result inputs.

### Guardrails
- AI parse result is always shown to the player for confirmation before saving — no silent mis-save.
- One player's match history is never visible to another player; the authentication boundary is enforced on every data access.
- AI-generated game plans are always explicitly labelled as AI-generated advice, not factual analysis.

## User Stories

### US-01: Player logs a match via AI text entry

- **Given** a signed-in player who wants to log a just-played match
- **When** they type a natural language description (e.g. "beat Kowalski 3:1 (11:5, 6:11, 11:2, 11:1) on May 5th, struggled in the second set")
- **Then** the AI returns a structured preview (opponent name, score, date, notes), the player confirms it, and the match is saved to their history

#### Acceptance Criteria
- AI produces a structured preview within a reasonable response time; the player sees progress indication during processing
- Player can edit any field in the preview before confirming
- Rejecting the preview discards the record without saving
- After saving, the match appears in the player's match history

---

### US-02: Player requests AI game plan before a match

- **Given** a signed-in player with at least one saved match against opponent X
- **When** they select opponent X from their history and tap "Generate game plan"
- **Then** the app displays an AI-generated tactical game plan for the next match against opponent X, clearly labelled as AI-generated advice

#### Acceptance Criteria
- Output is clearly labelled as AI-generated advice, not factual analysis
- The game plan references the player's historical performance against that opponent
- Player can request a new game plan at any time regardless of how many matches are logged

---

### US-03: Player uses the app in their preferred language

- **Given** a player opening the app, whose browser is configured for Polish
- **When** they first load the app, and later open the language switcher to choose English
- **Then** the interface initially renders in Polish (auto-detected), switches to English on demand, and that choice is remembered against their account on the next sign-in from any device — and any game plan they generate is written in the language the interface is currently showing

#### Acceptance Criteria
- On first visit the language defaults to the browser's preferred language when it is one of the supported languages (Polish, English); otherwise it falls back to English
- A signed-in player can switch language from the UI, and the preference is persisted to their account (survives sign-out/sign-in and follows them across devices)
- All interface text the player encounters is translated — no untranslated strings leak through in either language
- A game plan generated while the interface is in a given language is written in that same language

## Functional Requirements

### Authentication

- FR-001: Player can register with email and password. Priority: must-have
  > Socratic: Counter-argument considered: shared password or no auth simplifies v1. Resolution: kept; the privacy guardrail (no cross-player history visibility) requires per-player auth from day one.
- FR-002: Player can sign in and sign out. Priority: must-have
  > Socratic: (same resolution as FR-001 — auth pair is indivisible)

### Match Logging

- FR-003: Player can log a match by entering natural language text; AI parses it into a structured match record preview. Priority: must-have
  > Socratic: Counter-argument considered: AI misparsing could create more friction than a form. Resolution: kept; the confirm step (FR-004/005) is the mitigation — a misparse is just an extra edit, not silent data corruption.
- FR-004: Player can review the structured match record preview produced by AI parsing before committing. Priority: must-have
- FR-005: Player can confirm or reject the AI-parsed preview; confirmed records are saved, rejected records are discarded or returned to edit. Priority: must-have
- FR-006: Player can log a match using a manual structured form. Priority: must-have
  > Socratic: Counter-argument considered: the confirm+edit step in FR-005 makes a separate form redundant. Resolution: kept; form-first users exist who will never want to type free text.

### Match History

- FR-007: Player can view their full match history and filter it by opponent. Priority: must-have
  > Socratic: Counter-argument considered: a flat chronological list becomes unusable after 50+ matches. Resolution: FR-007 upgraded to include per-opponent filtering as a must-have; the flat list alone is insufficient.
- FR-008: Player can edit a saved match record. Priority: must-have
  > Socratic: Counter-argument considered: delete+re-log is an acceptable workaround that saves dev time. Resolution: kept; editing a score typo without deleting the record is basic data hygiene, not a luxury.
- FR-009: Player can delete a saved match record. Priority: must-have

### AI Analysis

- FR-010: Player can select an opponent from their match history and request an AI-generated game plan for the next match against that opponent. Priority: must-have
  > Socratic: Counter-argument considered: thin data (1 match) causes generic/hallucinated tips, eroding trust early. Resolution: kept; the "AI-generated advice" label guardrail sets expectations. The player decides when they have enough data to ask.

### Localization

- FR-011: The app is available in Polish and English; on first visit the language is auto-detected from the browser, falling back to English when the browser's preferred language is unsupported. Priority: must-have
  > Socratic: Counter-argument considered: English-only is cheaper and the persona is "technically comfortable". Resolution: added post-v1; the primary persona is Polish-club squash players (opponent names like "Kowalski" in US-01), so Polish is a first-class language, not a nice-to-have.
- FR-012: Player can switch the interface language from the UI, and the choice is persisted to their account so it follows them across sessions and devices. Priority: must-have
  > Socratic: Counter-argument considered: a browser-local cookie/localStorage is simpler than a DB column. Resolution: per-user persistence is the explicit requirement — the preference must follow the account, not the browser; localStorage only seeds the pre-sign-in default.
- FR-013: AI-generated game plans are produced in the player's active interface language. Priority: must-have
  > Socratic: Counter-argument considered: always generate in English and let the player translate. Resolution: kept; a Polish-speaking player reading a Polish UI expects Polish advice — the locale must reach the LLM prompt, not stop at the frontend.

## Non-Functional Requirements

- Any AI operation (text parsing, game plan generation) provides continuous visible progress feedback to the player; the interface does not freeze or go silent.
- AI text parsing completes within 5 seconds as perceived by the player (from submit to structured preview appearing).
- No match record belonging to one player is accessible by a different player.
- The product is fully usable on the latest two major versions of Chrome, Firefox, Safari, and Edge (desktop). Mobile browser support is explicitly out of scope for MVP.
- The supported interface languages are Polish and English; the language set is fixed for MVP (additional languages are out of scope). User-supplied content (opponent names, match notes) is never machine-translated — only interface chrome and AI-generated game-plan output are localized.

## Business Logic

The app analyses a player's match history against a specific opponent — including overall sets score and individual set scores — and recommends tactical adjustments for the next match against that opponent.

A match record carries: opponent name, overall sets score (e.g. 3-1), individual set scores (e.g. 11-9, 7-11, 11-8, 11-6), match date, and optional player notes (free text, e.g. "missed a lot of drop shots", "opponent was too fast for me"). These inputs are the signal the recommendation engine consumes. The player provides them either via natural language text (AI-parsed) or a structured form. The output — a tactical game plan — is surfaced on demand when the player selects a specific opponent and requests it. The quality of the recommendation compounds as more matches vs. that opponent accumulate; thin data produces a thin but still valid recommendation (the confidence is managed by labelling, not suppression).

## Access Control

Multi-user web app with per-player accounts. Sign-up and sign-in via email and password; third-party sign-in is optional and not required for MVP. Flat user model — every account is a player; no admin or coach role in MVP. An unauthenticated user reaching a protected page is redirected to the sign-in page. One player's match history is never visible to another player.

## Non-Goals

- **No mobile app:** MVP is web-only. A mobile-native experience is explicitly deferred; the web app targets desktop browsers only.
- **No match result sharing between players:** All match history is private to the owning player. No social features, no shared leaderboards, no head-to-head visibility between accounts.
- **No advanced algorithmic analysis:** Tactical recommendations are AI-generated, not produced by a custom-built statistical or scoring model. No custom ML model, no proprietary scoring algorithm, no statistical engine is built or integrated for MVP.

## Open Questions

No open questions. The shape session completed with `quality_check_status: accepted` — all 6 greenfield gate elements were present and resolved during discovery.
