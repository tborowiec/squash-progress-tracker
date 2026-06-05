# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-06-05 (added Phase 5: container smoke + critical-flow e2e)

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic visual diff that already catches
   the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the team
   is worried about X, and the failure would surface somewhere in <area>"
   carry the same weight as PRD lines or hot-spot data. This rollout's two
   sharpest risks (#2, #4) came from the interview, not the docs.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Hot-spot scope used for likelihood weighting: `src/main/java/org/borowiec/squashprogresstracker/`, `frontend/src/` (excluded `target/`, `node_modules/`, `context/`, docs, and the historical `com.example.*` → `org.borowiec.*` rename churn).

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | **Cross-player match access (IDOR).** A signed-in player reads, edits, or deletes *another* player's match by altering a record id — the check proves "logged in" but not "owns this record." | High | High | PRD §Guardrails ("one player's history never visible to another"); AGENTS hard rule ("enforce the auth boundary on every data-access query"); hot-spot `match/` (12 commits/30d); archived slice `edit-delete-match` adds get-one/update/delete-by-id |
| 2 | **Transient AI error surfaced as a dead-end.** A Gemini `503` ("overloaded / high demand") is shown to the player identically to a permanent failure ("AI unavailable") — even though the same request succeeds seconds later. The player abandons an action that would have worked. | High | High | Interview Q1 + lived incident (game-plan call hit a Gemini `503`, retry seconds later succeeded); PRD NFR (continuous progress, no silent freeze, <5s parse); hot-spot `llm/client/` (8 commits/30d) |
| 3 | **Silent mis-save from AI parse.** The saved record differs from the previewed record, or a path persists without the confirm gate — corrupting the player's history. | High | Medium | PRD §Guardrails ("no silent mis-save") + FR-004/FR-005, US-01; AGENTS hard rule (always confirm before save) |
| 4 | **Frontend route-guard regression.** A change to the client-side guard lets an unauthenticated user render a protected page, or bounces a valid session back to login. | Medium | High | Interview Q3 + Q4; hot-spot `frontend/src/pages/` (15 commits/30d), `frontend/src/` has zero automated tests; PRD §Access Control (redirect rule). The real boundary is server-side, so impact is capped at UX + defence-in-depth. |
| 5 | **Frontend ↔ backend contract drift.** The API client and backend DTOs diverge (field rename, score shape, error body); a healthy-looking UI silently fails to save or mis-renders. | Medium | High | Hot-spot `frontend/src/api/` (9 commits/30d) + `match/dto/` (7 commits/30d); no contract test exists; TS types do not assert the runtime contract |
| 6 | **Build/deploy parity failure.** The multi-stage Docker build (frontend-maven-plugin builds the SPA → packages the jar) diverges from local; `mvn test` is green but the deployed artifact is broken or missing the frontend. | High | Medium | Interview Q2 (already happened once); tech-stack.md (Render auto-deploy on merge to main, CI "planned but not yet wired"); `pom.xml` frontend-maven-plugin |

**Impact × Likelihood rubric.** Score both axes on a coarse High / Medium /
Low scale so two readers agree on the same row.

| Rating | Impact | Likelihood |
|--------|--------|------------|
| High   | user loses access, data, or money; failure is publicly visible | area changes weekly, or we have already been burned here |
| Medium | feature degrades, a workaround exists, only some users affected | touched occasionally, has been a source of bugs |
| Low    | cosmetic, easily reverted, no data effect | stable code, rarely touched |

Ordering: #1 and #2 are High × High → protect first. #3 is High × Medium. #4 and #5 are the frontend cluster (Medium impact, High likelihood — highest churn, zero coverage today). #6 is High × Medium but is a **quality-gate**, not a unit test.

**Abuse / security lens.** The product has auth and accepts user input, so the map carries an abuse row: **#1 (IDOR / ownership)** — it never surfaces from the happy path because the happy path excludes the attacker. #3 is input-trust adjacent. Resource abuse (LLM cost flooding) was considered and left below the top-6: low-traffic MVP, small trusted user base, no public sign-up surge expected — revisit if usage scales.

**Allowed Source citations:** PRD/roadmap/archive lines, Phase 2 interview question numbers, hot-spot **directories** with churn counts, tech-stack constraints. **Forbidden:** file/`file:line` references, function or symbol names, schemas, classes, modules — those belong in `context/changes/<change-id>/research.md`.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|------|-----------------------------|----------------|--------------------------------------|-----------------------|-----------------------|
| #1 | Player A's token requesting/editing/deleting Player B's match id is rejected (404/403) on **every** match endpoint, including list/filter | "logged-in ⇒ authorized" — authentication is not ownership | Where (and whether) ownership is enforced per query; the new get-one/update/delete paths from `edit-delete-match` | Backend integration (MockMvc + `spring-security-test` + Testcontainers) | Testing only the happy owner path; asserting `200` instead of a cross-tenant `404`/`403` |
| #2 | A `503`/timeout from the provider is mapped to a clean, *retryable-signalled* error — never a fake success, never an infinite spinner — within the configured timeout | "final status was 200 ⇒ it worked"; assuming a timeout exists and fires within budget | How the client distinguishes transient vs permanent failures today; how the timeout (`30s`) relates to the `<5s` parse NFR; how the UI consumes the failure | Backend unit/integration (stub transport returns `503`/slow); frontend error-state test after Phase 3 bootstrap | Asserting current behavior as "correct" when no transient/permanent distinction exists (oracle problem). **Do not add retry here** — that is a separate feature change (see §7) |
| #3 | The persisted record byte-equals the confirmed preview; no save path bypasses the confirm gate | "a preview was shown ⇒ the saved value matches it" | The parse → preview → confirm → persist wiring; the source of the saved payload (confirmed input vs re-parsed text) | Backend integration | Assertion copied from the parser's own output (tautological — green-lights current bugs) |
| #4 | An unauthenticated render of a protected route redirects to login; a valid session renders the route | "server-side auth ⇒ the frontend guard is irrelevant" (it is defence-in-depth + UX, and the thing manual clicking misses) | The `ProtectedRoute` / `AuthContext` redirect logic and router setup; how auth state is read | Frontend component test (Vitest + Testing Library + router memory history) — **requires runner bootstrap (Phase 3)** | Snapshot-without-meaning; mocking the guard you are testing |
| #5 | The API client and backend agree on field names, score shape, and error-body shape for match create/list/error | "it compiles ⇒ the shapes match"; TS types are not the runtime contract | The actual JSON contract for match create/list and the error body; where the client decodes it | Backend response-shape integration assertion + a frontend api-client test against a recorded fixture | Re-asserting the client's own TS types instead of the backend's real contract |
| #6 | CI **builds, boots, and HTTP-smokes** the real Docker image (not just `mvn test`, not just a successful build): a running container serves `/actuator/health`, serves the SPA root, and redirects a gated route. A thin browser happy-path (login → log a match → see it in history) confirms the full stack end-to-end against the running image. | "`mvn test` is green ⇒ deployable"; **"the image built ⇒ it runs"** (it may be missing the frontend, fail to bind `$PORT`, or mis-map `DATABASE_URL`) | What the Dockerfile build/run does vs. local; what `mvn package` triggers (frontend-maven-plugin); how `DATABASE_URL` is mapped to `SPRING_DATASOURCE_*`; the `$PORT` binding | Container smoke test (build + run image + HTTP assertions) + one Playwright critical-flow e2e (Module 3 Lesson 4 wires the runner) | Gating on a successful *build* while the container is never *run*; a flaky browser e2e that re-tests what the component/integration layers already cover (keep it to one happy-path) |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Ownership-boundary & no-mis-save (backend) | Prove cross-player access is rejected on every match endpoint and that confirmed == saved | #1, #3 | integration | not started | — |
| 2 | AI failure-path (backend) | Prove a transient/erroring provider surfaces a clean, retryable error (no fake success, no infinite spin) and advice-labelling holds | #2 | unit + integration | not started | — |
| 3 | Frontend runner bootstrap + guard/contract | Stand up the frontend test runner; prove the route guard is correct and the api-client matches the backend contract | #4, #5 | unit/component + contract | not started | — |
| 4 | Quality-gates wiring (CI) | Run both test suites + compile/typecheck in CI on every PR | cross-cutting | gates | not started | — |
| 5 | Container smoke + critical-flow e2e | Build, boot, and HTTP-smoke the Docker image; one browser happy-path (login → log match → history) against the running app | #6 (+ exercises #4/#5 at the deployed layer) | container smoke + e2e (browser) | not started | — |

**Status vocabulary** (fixed — parser literals): `not started` → `change opened` → `researched` → `planned` → `implementing` → `complete`.

Order rationale: Phases 1–2 attack the two highest-priority risks (#1, #2) at the cheapest layer, reusing the **existing backend suite** (no new infra). Phase 3 covers the hottest-churn, zero-coverage area (frontend) but is sequenced after the cheap backend wins because it must first **stand up a test runner** (cost). Phase 4 wires the suites into CI and depends on Phases 1–3 existing to gate on. Phase 5 is last because it has the highest infra cost (build + run the Docker image, plus a browser runner) and gives the most end-to-end signal — it proves the *deployed artifact* actually serves the app, closing the "works locally, breaks deployed" gap (#6) that a build-only gate misses. AI-native game-plan quality evaluation is deferred (see §4 and §7), not a rollout phase.

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|-------|------|---------|-------|
| backend unit + integration | JUnit 5 (Jupiter) + Spring Boot Test | Boot 4.0.6 | **`meaningful`** — ~15 test files across security/user/match/llm/gameplan |
| backend security tests | `spring-security-test` | (Boot-managed) | `@WithMockUser` / request-level auth assertions for the ownership boundary |
| backend integration DB | Testcontainers (PostgreSQL) | via `spring-boot-testcontainers` BOM | real Postgres for repository/API integration; matches prod engine |
| LLM provider (under test) | Gemini `gemini-2.5-flash` via OpenAI-compat endpoint | `timeout 30s` | `OpenAiCompatLlmClient`; stub the transport for #2 |
| frontend unit/component | none yet — see §3 Phase 3 | — | React 18.3 + Vite SPA; **0 test files, no runner** today |
| frontend api-client contract | none yet — see §3 Phase 3 | — | `frontend/src/api/` is hot churn with no contract test |
| container smoke | none yet — see §3 Phase 5 | — | build + run the Docker image in CI; HTTP-assert health + SPA root + gated redirect. Deterministic, no browser. Closes #6 (the running artifact, not just the build) |
| e2e (browser) | Playwright (none yet — see §3 Phase 5) | — | one critical-flow happy-path (login → log match → history) against the running image. Runner/config is Module 3 Lesson 4; keep it to a single path so it does not re-test the component/integration layers |
| live LLM smoke | `*LiveSmokeTest` (opt-in) | — | already present; opt-in only, **never gate CI on it** (non-deterministic, real key) |
| (optional) AI-native game-plan eval | deferred — LLM-as-judge | n/a | **When NOT to use:** do not gate CI on it; provider is locked but output is non-deterministic; deterministic schema + failure-path tests (#2, #3) give the cheaper signal. Revisit if game-plan quality becomes a tracked metric. checked: 2026-06-05 |

**Stack grounding tools (current session):**
- Docs: Context7 / framework docs MCP — **not available in current session**; relied on local `pom.xml` / `package.json` / `application.properties`; checked: 2026-06-05
- Search: `WebSearch` available (web search) — not invoked; stack facts came from local manifests; checked: 2026-06-05
- Runtime/browser: Playwright MCP — **not available in current session** (browser/e2e tooling is a Module 3 Lesson 4 concern); checked: 2026-06-05
- Provider/platform: `gh` CLI present (GitHub); no MCP. Relevant to Phase 4 (GitHub Actions gate) and existing issue tracking; checked: 2026-06-05

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required after §3 Phase <N>" means the gate is enforced once that rollout
phase lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|------|-------|-----------|---------|
| compile + typecheck (backend `javac`, frontend `tsc`) | local + CI | required after §3 Phase 4 | syntactic / type drift |
| backend unit + integration | local + CI | required after §3 Phase 1 | logic regressions, ownership-boundary breaks, mis-save |
| frontend unit/component | local + CI | required after §3 Phase 3 | route-guard regressions, contract drift |
| build + run + HTTP-smoke the Docker image | CI on PR | required after §3 Phase 5 | deploy parity (#6) — the *running* artifact: missing frontend, `$PORT` bind, `DATABASE_URL` mapping, startup/OOM failures that a build-only gate never exercises |
| critical-flow browser e2e | CI on PR | required after §3 Phase 5 | an end-to-end break in the login → log-match → history path against the running image |
| live LLM smoke | local, manual | never required (opt-in) | provider wiring sanity; excluded from CI by design (non-deterministic) |
| post-edit hook | local (agent loop) | recommended (Module 3 Lesson 3) | regressions at edit time |
| visual diff / multimodal review | CI on PR | optional | rendering regressions; not justified for this MVP's screens yet |

Every required gate maps to a rollout phase that wires it. The e2e/smoke gates are deliberately thin: most risks are caught more cheaply at the integration and component layers, so Phase 5 adds only what those layers cannot give — proof that the **deployed Docker artifact actually boots and serves** (#6) plus one full-stack happy-path. Resist growing the browser e2e suite beyond critical flows; breadth belongs in the cheaper layers.

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once
the relevant rollout phase ships; before that, it reads "TBD — see §3 Phase <N>."

### 6.1 Adding a backend unit test

- TBD — see §3 Phase 1/2. Reference today: `src/test/java/.../llm/client/OpenAiCompatLlmClientTests.java`, `src/test/java/.../match/MatchParsePromptBuilderTests.java`.
- **Naming**: `<ClassName>Tests.java` (per AGENTS.md). **Run**: `./mvnw test -Dtest=<ClassName>Tests`.

### 6.2 Adding a backend integration test (auth boundary / persistence)

- TBD — see §3 Phase 1 for the ownership-boundary (`#1`) and no-mis-save (`#3`) patterns. Reference today: `src/test/java/.../match/MatchApiIntegrationTests.java`, `.../security/SecurityFilterChainTests.java`, `.../user/AuthIntegrationTests.java`.
- **Mocking policy**: real Postgres via Testcontainers; assert request → response shape AND persisted side-effects. Mock only the external HTTP edge (the LLM transport).

### 6.3 Adding a frontend component test (route guard)

- TBD — see §3 Phase 3 (runner bootstrap: Vitest + Testing Library; route-guard pattern for `#4`).

### 6.4 Adding a test for a new match API endpoint (ownership)

- TBD — see §3 Phase 1. The canonical pattern: a second player's token must receive `404`/`403` for a resource it does not own, on get-one/update/delete/list-filter.

### 6.5 Adding a frontend api-client contract test

- TBD — see §3 Phase 3 (api-client vs backend DTO contract, `#5`).

### 6.6 Adding a container-smoke / e2e test

- TBD — see §3 Phase 5. Container smoke: build the image, run it against a throwaway Postgres, assert `/actuator/health` + SPA root + a gated redirect over HTTP. Browser e2e: one Playwright critical-flow happy-path against the running image (runner/config is Module 3 Lesson 4). Keep e2e to critical flows only — breadth belongs in §6.2/§6.3.

### 6.7 Per-rollout-phase notes

(Optional. After each phase lands, `/10x-implement` appends a 2–3 line note here capturing anything surprising the phase taught.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5) and scoping
decisions. Respect these unless the underlying assumption changes.

- **Exhaustive coverage of trivial DTOs / getters / setters.** The compiler already proves these; tests here are noise. Re-evaluate only if a DTO grows real logic (validation, derived fields). (Source: Phase 2 interview Q5.)
- **Live LLM-provider calls in CI.** The `*LiveSmokeTest` classes stay opt-in/manual — non-deterministic output and a real API key make them a bad CI gate. (Source: scoping; §4.)
- **AI game-plan *quality* (LLM-as-judge eval).** Deferred — deterministic schema + failure-path tests (#2, #3) give the cheaper signal; output quality is not a tracked metric yet. (Source: cost × signal; §4.)
- **Backend retry-with-backoff for transient `503`s.** This is a *feature*, not a test — out of this test rollout's scope. It belongs in a separate change (suggested id `transient-llm-retry`, open via `/10x-new`). This plan's #2 asserts only today's clean-error behavior; if retry ships, it brings its own tests. (Source: this session's scoping decision.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-06-05
- Stack versions last verified: 2026-06-05
- AI-native tool references last verified: 2026-06-05

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
