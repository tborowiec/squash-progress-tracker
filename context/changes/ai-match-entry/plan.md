# AI Match Entry Implementation Plan

## Overview

Implement natural-language match entry (FR-003/004/005, US-01): a signed-in player types a free-text description of a just-played match, the backend parses it into a structured preview via the LLM, the result pre-fills the existing match form (which serves as the editable preview), and the player confirms by saving through the existing match-creation path. This closes the core product loop — manual entry, history, and AI game plans already exist.

## Current State Analysis

The LLM and match-persistence capabilities this feature needs already exist; only the parse glue and the AI entry UI are missing.

- **Structured LLM output is available.** `LlmClient.generateStructured(LlmRequest, Class<T>)` (`src/main/java/org/borowiec/squashprogresstracker/llm/client/LlmClient.java:11`) sends `response_format: json_schema` with `strict: true` and deserializes into a Java record (`OpenAiCompatLlmClient.java:50`). The provider is resolved: Gemini `gemini-2.5-flash` via the OpenAI-compat endpoint (`src/main/resources/application.properties`).
- **Schema derivation has hard limits.** `JsonSchemaFactory` (`JsonSchemaFactory.java:40`) supports only `String`, integer/number, boolean, nested **records**, and `Collection<T>`, and marks **every** record component `required`. It does **not** support `LocalDate` — any unsupported type throws `IllegalArgumentException`. The parse-result DTO must therefore carry the date as a `String`.
- **The save path is complete and owner-scoped.** `CreateMatchRequest` (`match/dto/CreateMatchRequest.java`) → `MatchService.create` (`MatchService.java:23`) stamps `currentUser.currentUserId()`. `MatchController` exposes `POST /api/matches`, `GET /api/matches`, `GET /api/matches/opponents` (`MatchController.java`).
- **The manual form is a ready-made editable preview.** `LogMatchPage.tsx` already renders opponent/date/notes/sets with validation, a live scoreline, and the save call. It lives at route `/matches/new` (`App.tsx:19`).
- **Error handling is wired.** `LlmException` → 503 "AI service is temporarily unavailable" in `ApiExceptionHandler.java:24`; `MethodArgumentNotValidException` → 400 with field errors. CSRF/auth on `/api/**` POST already works — the axios `client` (`frontend/src/api/client.ts`) materializes the `XSRF-TOKEN` cookie before mutating requests, and `createMatch` proves the path.
- **Routing needs no security change.** `/matches/**` is already in `SpaRoutes.CLIENT_ROUTES` (`SpaRoutes.java:20`) and `/api/**` is `authenticated()` by default (`SecurityConfig.java:35`).
- **A live-LLM test precedent exists.** `LlmClientLiveSmokeTest` (`src/test/java/.../llm/client/LlmClientLiveSmokeTest.java`) is guarded by `@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")` so it auto-skips in CI. Integration tests mock the LLM with `@MockitoBean` (`GamePlanApiIntegrationTests.java:43`).

## Desired End State

A signed-in player on the match-entry page can toggle to an "AI" mode, type something like *"beat Kowalski 3:1 (11:5, 6:11, 11:2, 11:1) on May 5th, struggled in the second set"*, click Parse, see a spinner, and within ~5s have the structured form below pre-filled (opponent, ISO date, per-set scores, notes). They can edit any field, and saving uses the existing create path. If the AI couldn't fill key fields, a warning banner draws their attention. An AI-parse disclaimer makes clear the preview must be reviewed before saving.

Verify by: typing a natural-language match in AI mode, parsing, confirming the form fills correctly, editing a field, saving, and seeing the match in history. Backend verified by `./mvnw test` (mocked) and the env-guarded live test with a real key.

### Key Discoveries:

- `generateStructured` already does strict JSON-schema structured output — no new LLM plumbing (`OpenAiCompatLlmClient.java:50`).
- `JsonSchemaFactory` rejects `LocalDate` and forces all fields required (`JsonSchemaFactory.java:40,33-37`) — parse DTO uses `String` date and tolerates empty strings for unparsed fields.
- The existing form at `/matches/new` is reused as the editable preview — no separate preview component (`LogMatchPage.tsx`).
- Live LLM test pattern: `@EnabledIfEnvironmentVariable` (`LlmClientLiveSmokeTest.java:25`); mock pattern: `@MockitoBean` (`GamePlanApiIntegrationTests.java:43`).

## What We're NOT Doing

- No SSE/streaming for parse — it is a single synchronous `generateStructured` call with a spinner (≤5s NFR).
- No separate read-only preview component — the existing form is the preview/edit surface.
- No server-side fuzzy matching of opponent names — consistency is nudged by passing existing names to the LLM as hints only.
- No LLM-reported confidence field — "empty key field" is the only uncertainty signal surfaced.
- No new route, no `SpaRoutes`/`SecurityConfig` change — AI entry lives on the existing `/matches/new` page.
- No data-model/migration change — the `matches` schema and save path are unchanged.
- No multi-match parsing — one text input yields one match preview.

## Implementation Approach

Phase 1 adds a backend parse endpoint that wraps `generateStructured`: a record DTO the schema factory can derive, a prompt builder that injects today's date and the player's existing opponent names, and a service that returns a best-effort structured preview. Phase 2 adds the frontend: an AI/Manual toggle on the existing page, an AI text view that POSTs to the parse endpoint and maps the result into the existing form fields, plus a warning banner and disclaimer. The form's existing validation and save path handle confirmation.

## Critical Implementation Details

- **Date resolution.** The prompt must include today's date (the request thread's `LocalDate.now()`) so the LLM can resolve relative phrasing ("yesterday", "May 5th") to an ISO `YYYY-MM-DD` string. When no date is stated, instruct the LLM to default to today — this matches the manual form's default and the "just played a match" common case. The form pre-fills from this string; the user can still correct it.
- **Schema constraint is load-bearing.** Every field in the parse-result record is emitted as `required` by `JsonSchemaFactory`, so the LLM always returns all fields. Unparsed values must be representable as empty (`""` for opponent/notes/date, an empty set list) rather than omitted — the prompt instructs best-effort fill with empties, and the frontend flags empties rather than the backend rejecting them.

## Phase 1: Backend parse endpoint

### Overview

Add a `POST /api/matches/parse` endpoint that takes raw text and returns a structured, best-effort match preview produced by the LLM, scoped to the authenticated player.

### Changes Required:

#### 1. Parse-result DTO

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/dto/MatchParseResult.java` (new)

**Intent**: The structured shape the LLM fills and the endpoint returns. Must be a record whose components are all schema-derivable by `JsonSchemaFactory`, so the date is a `String` and sets are a nested record list.

**Contract**: `record MatchParseResult(String opponentName, String matchDate, String notes, List<ParsedSet> sets)` with nested `record ParsedSet(Integer playerScore, Integer opponentScore)`. `matchDate` is an ISO `YYYY-MM-DD` string (may be empty if truly unresolvable). All fields always present (schema marks them required); unparsed string fields come back `""`, unparsed sets come back as an empty list. This is the JSON contract the frontend maps into the form.

#### 2. Parse request DTO

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/dto/ParseMatchRequest.java` (new)

**Intent**: Wrap the raw natural-language text from the client with a bound on size.

**Contract**: `record ParseMatchRequest(@NotBlank @Size(max = 2000) String text)`.

#### 3. Prompt builder

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchParsePromptBuilder.java` (new)

**Intent**: Build the `LlmRequest` (system + user messages) that instructs the model to extract a squash match into the `MatchParseResult` shape, resolve dates relative to a supplied "today", default an unstated date to today, fill unknown fields with empties (best-effort, never invent scores/opponents), and snap the opponent name to one of the player's existing opponents when it clearly refers to the same person.

**Contract**: `LlmRequest build(String text, LocalDate today, List<String> knownOpponents)`. System message states the parsing role and the empty-field/no-invention rules; user message embeds the date, the known-opponent hint list, and the raw text. Mirrors the message-construction style of `GamePlanPromptBuilder` (`GamePlanPromptBuilder.java:23`). No `response_format` is set here — `generateStructured` adds the schema from `MatchParseResult`.

#### 4. Parse service

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchParseService.java` (new)

**Intent**: Resolve the current player's known opponent names, build the prompt with today's date, call `llmClient.generateStructured(request, MatchParseResult.class)`, and return the result. No persistence — parsing is read-only/stateless aside from the opponent-name lookup.

**Contract**: `MatchParseResult parse(String text)`. Reads known opponents via `matchRepository.findDistinctOpponentNamesByUserId(currentUser.currentUserId())` (same call `MatchService.listOpponents` uses, `MatchService.java:53`), passes `LocalDate.now()` to the builder. Propagates `LlmException` unchanged so `ApiExceptionHandler` maps it to 503.

#### 5. Parse endpoint

**File**: `src/main/java/org/borowiec/squashprogresstracker/match/MatchController.java`

**Intent**: Expose the parse operation alongside the existing match endpoints.

**Contract**: `@PostMapping("/parse") public MatchParseResult parse(@Valid @RequestBody ParseMatchRequest request)` delegating to `matchParseService.parse(request.text())`. Returns 200 with the structured preview. Auth and CSRF inherited from existing config; validation failures and `LlmException` handled by the existing `ApiExceptionHandler`.

### Success Criteria:

#### Automated Verification:

- All tests pass: `./mvnw test`
- `MatchParseService` unit test (mocked `LlmClient`) covers: happy path maps text→`MatchParseResult`; empty/partial fields returned as empties; relative-date prompt includes today's date and known opponents; `LlmException` from the client propagates
- Controller slice test: `POST /api/matches/parse` requires authentication (401 when anonymous) and returns 400 on blank text
- Env-guarded live integration test (`@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")`) parses a representative sentence into a non-null `MatchParseResult` with a plausible opponent and at least one set; auto-skips in CI
- Package builds: `./mvnw package`

#### Manual Verification:

- With a real `LLM_API_KEY`, `POST /api/matches/parse` with the US-01 example sentence returns correct opponent, ISO date, four sets, and notes
- Relative date ("yesterday") resolves to the correct ISO date
- A sentence omitting the date defaults `matchDate` to today

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Frontend AI entry

### Overview

Add an AI/Manual toggle to the match-entry page; in AI mode the player types free text, parses it, and the result pre-fills the existing form (the editable preview) for review and confirmation.

### Changes Required:

#### 1. Parse API client

**File**: `frontend/src/api/matches.ts`

**Intent**: Add a typed `parseMatch` call to the parse endpoint, plus the result interface. Uses the shared `client` so CSRF is handled automatically.

**Contract**: `interface MatchParseResult { opponentName: string; matchDate: string; notes: string; sets: { playerScore: number; opponentScore: number }[] }` and `parseMatch(text: string): Promise<MatchParseResult>` → `client.post('/api/matches/parse', { text })`.

#### 2. AI/Manual toggle + AI view on the match-entry page

**File**: `frontend/src/pages/LogMatchPage.tsx`

**Intent**: Add a mode toggle (AI ↔ Manual). The AI view shows a natural-language textarea + Parse button with a spinner/busy state; on success it maps the parsed result into the existing form state (opponent, date, notes, set rows as strings) and switches to the form view so the player reviews/edits and saves via the existing path. Manual mode is the current form unchanged.

**Contract**: New local state for `mode: 'ai' | 'manual'`, the AI text, and a parsing/busy flag. Parsed `sets` map into the existing `SetRow[]` shape (numbers → strings; clamp to the form's max of 5 rows). The existing `handleSubmit`/save path is the confirm action — no new save logic. Reuse the existing `s` style objects and `NavHeader`. On parse error, show the existing error-banner style with the 503 message.

#### 3. Empty-field warning banner + AI disclaimer

**File**: `frontend/src/pages/LogMatchPage.tsx`

**Intent**: After a parse, if key fields came back empty (opponent blank or no sets), show a warning banner telling the player to check those fields. Show an "AI-parsed — review before saving" disclaimer in AI mode.

**Contract**: Banner renders when `opponentName === ''` or `sets` is empty after a parse, using a caution style consistent with the game-plan caveat banner (`GamePlanPage.tsx:25`). Disclaimer is static helper text near the AI textarea. No backend dependency — derived from the mapped form state.

### Success Criteria:

#### Automated Verification:

- Frontend builds: `cd frontend && npm run build`
- Backend test suite still green (full build wires frontend): `./mvnw test`

#### Manual Verification:

- Toggling to AI mode shows the text box; toggling back shows the manual form unchanged
- Parsing the US-01 example sentence pre-fills opponent, date, all four sets, and notes; the live scoreline reflects the parsed sets
- Editing a pre-filled field then saving creates the match and lands on history (existing flow)
- A vague input that yields an empty opponent or no sets shows the warning banner; the player can complete the fields and save
- A parse while the LLM is unavailable shows the "AI service is temporarily unavailable" error and the form remains usable for manual entry
- The AI-parsed disclaimer is visible in AI mode

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- `MatchParseService` with a mocked `LlmClient` (`@MockitoBean` / Mockito): happy-path mapping, empty/partial results, prompt includes today + known opponents, `LlmException` propagation.
- `MatchParsePromptBuilder`: the built `LlmRequest` embeds the supplied date, the known-opponent hints, and the raw text (mirrors `GamePlanPromptBuilderTests`).

### Integration Tests:

- Controller slice (`@SpringBootTest` + MockMvc, LLM mocked): auth required (401 anonymous), 400 on blank text, 200 with a stubbed `MatchParseResult`.
- Live LLM test (env-guarded, auto-skips in CI): real parse of a representative sentence returns a plausible structured result.

### Manual Testing Steps:

1. Sign in, go to `/matches/new`, toggle to AI mode.
2. Type the US-01 example sentence; click Parse; confirm the spinner then the pre-filled form.
3. Edit one field, save, confirm the match appears in history.
4. Parse "lost to Smith 1-3 yesterday"; confirm relative date resolves and opponent snaps to an existing "Smith" if present.
5. Parse a vague input; confirm the warning banner appears for empty key fields.
6. Simulate LLM down (bad key); confirm the 503 error message and that manual entry still works.

## Performance Considerations

The parse call is a single synchronous LLM round-trip; the NFR budget is ≤5s perceived. The spinner provides continuous progress feedback. The `llm.timeout` (30s default) bounds the worst case; no DB connection is held across the call (the only DB access is the quick known-opponents lookup before the LLM call).

## Migration Notes

None — no schema or data changes. The matches table and save path are unchanged.

## References

- Structured LLM client: `src/main/java/org/borowiec/squashprogresstracker/llm/client/OpenAiCompatLlmClient.java:50`
- Schema constraints: `src/main/java/org/borowiec/squashprogresstracker/llm/client/JsonSchemaFactory.java`
- Existing save path: `src/main/java/org/borowiec/squashprogresstracker/match/MatchService.java:23`
- Prompt-builder pattern: `src/main/java/org/borowiec/squashprogresstracker/match/gameplan/GamePlanPromptBuilder.java`
- Live LLM test pattern: `src/test/java/org/borowiec/squashprogresstracker/llm/client/LlmClientLiveSmokeTest.java:25`
- Form to reuse as preview: `frontend/src/pages/LogMatchPage.tsx`
- PRD user story: `context/foundation/prd.md` (US-01, FR-003/004/005)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend parse endpoint

#### Automated

- [x] 1.1 All tests pass: `./mvnw test`
- [x] 1.2 `MatchParseService` unit test (mocked LlmClient): happy path, empty/partial fields, prompt includes today + known opponents, `LlmException` propagation
- [x] 1.3 Controller slice: `POST /api/matches/parse` requires auth (401 anonymous), 400 on blank text
- [x] 1.4 Env-guarded live integration test parses a representative sentence; auto-skips in CI
- [x] 1.5 Package builds: `./mvnw package`

#### Manual

- [x] 1.6 Parse of US-01 example sentence returns correct opponent, ISO date, four sets, notes
- [x] 1.7 Relative date ("yesterday") resolves to the correct ISO date
- [x] 1.8 Sentence omitting the date defaults `matchDate` to today

### Phase 2: Frontend AI entry

#### Automated

- [ ] 2.1 Frontend builds: `cd frontend && npm run build`
- [ ] 2.2 Backend test suite still green: `./mvnw test`

#### Manual

- [ ] 2.3 AI/Manual toggle switches views; manual form unchanged
- [ ] 2.4 Parsing the example sentence pre-fills opponent, date, all sets, notes; live scoreline updates
- [ ] 2.5 Editing a pre-filled field then saving creates the match and lands on history
- [ ] 2.6 Vague input with empty opponent/no sets shows the warning banner; can be completed and saved
- [ ] 2.7 Parse while LLM unavailable shows the 503 error; form remains usable for manual entry
- [ ] 2.8 AI-parsed disclaimer visible in AI mode
