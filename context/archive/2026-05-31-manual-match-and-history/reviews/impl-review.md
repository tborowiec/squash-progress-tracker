<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Manual Match Logging & History (S-01)

- **Plan**: context/changes/manual-match-and-history/plan.md
- **Scope**: All 4 phases (full plan)
- **Date**: 2026-06-01
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | WARNING |
| Pattern Consistency | PASS |
| Success Criteria | FAIL |

Context: the backend vertical is strong — the ownership boundary (the project's hard rule) is enforced on every match read and write with a dedicated cross-player integration test; the `Match`/`MatchSet` entities agree exactly with `V2` under `ddl-auto=validate`; DTO/record/error/test patterns mirror the user domain faithfully; no SQL/HQL injection, no XSS, N+1 avoided via `@EntityGraph` under `open-in-view=false`. The findings cluster on the SPA serving/build seam, not the domain logic.

## Findings

### F1 — `./mvnw test` is red after a package build (order-dependent test)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Success Criteria
- **Location**: src/test/java/org/borowiec/squashprogresstracker/security/SecurityFilterChainTests.java:41-45
- **Detail**: `rootEndpointIsPublic` asserts `GET /` → 404 with the comment "Static file not present in test classpath (built by Vite), so 404 is expected." But `frontend-maven-plugin` emits `index.html` into `target/classes/static/` during the build. Reproduced: `./mvnw clean test` → PASS (static absent, `/` = 404); after `./mvnw package`, `./mvnw test` → FAIL (`/` = 200, SPA served by `WelcomePageHandlerMapping`). The current working tree fails this test (1 failure of 24). Production behavior — SPA served at `/` — is correct and intended; the test's assumption is the defect. Criterion 3.2 ("test still green") passed at commit time on a clean CI checkout but is fragile to build order.
- **Fix**: Assert intent, not absence — expect `/` to be permitted (not 401) regardless of body, or serve a tiny test `index.html` and assert 200. Drop the "static not present" assumption; `package` guarantees it IS present.
  - Strength: Makes the test deterministic regardless of whether the frontend was built; aligns the assertion with the intended SPA-serving behavior.
  - Tradeoff: Minor — a few-line test edit.
  - Confidence: HIGH — root cause reproduced both ways (clean vs post-package).
  - Blind spot: CI currently runs from clean, so it passes there today and masks the fragility.
- **Decision**: FIXED — added stub `src/test/resources/static/index.html` and changed the assertion to `status().isOk()` (SecurityFilterChainTests.java:42-47); `/` now deterministically serves the SPA shell (200) regardless of whether the Vite build ran. Verified via `./mvnw clean test -Dtest=SecurityFilterChainTests` (3/3).

### F2 — CSRF token bootstrap on first POST is timing-dependent

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability)
- **Location**: frontend/src/api/client.ts:10-21 ; frontend/src/contexts/AuthContext.tsx:16-21
- **Detail**: The request interceptor reads the `XSRF-TOKEN` cookie and sets `X-XSRF-TOKEN` only if the cookie is already present. That cookie is materialized by `CsrfCookieFilter` on the first GET. On login/register the first user action is a POST — it works only because `AuthProvider` fires `GET /api/auth/me` on mount, whose response sets the cookie. If the user submits before `me()` resolves (or the cookie is cleared), the POST sends no token → Spring 403, surfaced as a generic "Login failed". The plan's "Critical Implementation Details" called for an explicit session-bootstrap before any mutating call; the implementation relies on a side effect instead. CSRF protection still holds — this is a reliability/UX gap, not a security hole.
- **Fix**: Gate the first mutating call on the `me()` bootstrap completing (await it before enabling submit), or retry a 403 once after refreshing the token.
  - Strength: Removes the race so the first login/register POST always carries a token; matches the plan's explicit-bootstrap intent.
  - Tradeoff: Small frontend change; slight coupling of submit-enable to bootstrap state.
  - Confidence: MED — happy path works today; the failure is a timing edge.
  - Blind spot: Haven't measured how often `me()` resolves after a fast user submit in practice.
- **Decision**: FIXED — client.ts interceptor now awaits a deduped `ensureCsrfToken()` bootstrap GET (tolerating 401) before attaching `X-XSRF-TOKEN` to any mutating request, eliminating the race and self-healing a cleared cookie. `tsc --noEmit` clean.

### F3 — SPA route list is duplicated across SpaController and SecurityConfig

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture
- **Location**: src/main/java/org/borowiec/squashprogresstracker/SpaController.java:6-12 ; src/main/java/org/borowiec/squashprogresstracker/security/SecurityConfig.java:34
- **Detail**: The plan specified a generic "non-API, non-asset GET → forward:/index.html" fallback. The implementation hardcodes an explicit allow-list (`{"/login","/register","/history","/matches/**"}`) in BOTH `SpaController` (forward) and `SecurityConfig` (permitAll). Correct and complete for today's routes and safer than a catch-all (can't shadow `/api` or `/actuator`). But any new client-side route must be added in two places or a deep-link refresh 404s/redirects — a quiet footgun for S-02/S-04 work.
- **Fix A ⭐ Recommended**: Keep the explicit list but make it one source of truth — a shared constant array consumed by both the controller mapping and the security matcher.
  - Strength: Preserves the allow-list safety (no `/api` shadow) while removing two-place drift.
  - Tradeoff: Minor indirection; small refactor now.
  - Confidence: HIGH — both call sites live in the same module.
  - Blind spot: None significant.
- **Fix B**: Switch to the planned generic fallback (forward all GETs that aren't `/api/**`, `/actuator/**`, or a real asset).
  - Strength: Matches the plan; zero maintenance as routes grow.
  - Tradeoff: Must carefully exclude API/actuator/asset paths to avoid swallowing them — the exact risk the explicit list avoided.
  - Confidence: MED — needs careful matcher ordering plus a test.
  - Blind spot: Asset-path detection for arbitrary Vite output names.
- **Decision**: FIXED via Fix A — extracted `SpaRoutes.CLIENT_ROUTES` as the single source of truth; replaced `SpaController` (literal `@GetMapping`) with `SpaForwardingConfig` (a `WebMvcConfigurer` that loops the array into view-controller forwards); `SecurityConfig` now permits `SpaRoutes.CLIENT_ROUTES`. Compiles; SecurityFilterChainTests 3/3.

### F4 — Dockerfile changed despite plan's "unchanged required"

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: Dockerfile:8 (commit 85322e9)
- **Detail**: The plan asserted three times that no Dockerfile change was needed (build stage already runs `./mvnw clean package`). That premise was wrong: the original Dockerfile only `COPY src/ src/`, so `frontend-maven-plugin` had no `frontend/` to build inside Docker. Commit 85322e9 correctly adds `COPY frontend/ frontend/` plus a `.dockerignore` for `node_modules`/`dist`. The fix is right; the plan's assumption was the defect.
- **Fix**: None needed — change is correct. Optionally annotate the plan that the Dockerfile required a one-line copy.
- **Decision**: ACCEPTED-AS-RULE: "Verify the Docker build context covers all build inputs, not just src/" (appended to lessons.md). Also annotated plan.md with a post-implementation addendum. No code fix needed — the Dockerfile change already landed in 85322e9.

### F5 — frontend-maven-plugin executions all bound to prepare-package

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: pom.xml:117-139
- **Detail**: Plan allowed `generate-resources` OR `prepare-package`; all three goals (install-node, npm ci, npm build) are on `prepare-package`. Works for `package`, but `mvn compile`/`test` never builds the SPA — the root mechanism that lets F1's stale-vs-fresh `static/` divergence happen. Benign on its own; relevant as F1's mechanism.
- **Fix**: None required. If you want assets present for test runs, move the build to `generate-resources` (or document the split).
- **Decision**: SKIPPED — benign; F1's downstream effect already neutralized by the test-classpath stub `index.html`. pom.xml left as-is.
