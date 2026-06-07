# Phase 5: Container Smoke + Critical-Flow E2E — Plan Brief

> Full plan: `context/changes/container-smoke-e2e-tests/plan.md`
> Research: `context/changes/container-smoke-e2e-tests/research.md`

## What & Why

Close the last gap in the phased test rollout (`test-plan.md` §3, risk #6 — *build/deploy parity*). The multi-stage Docker build (frontend-maven-plugin builds the SPA → packages the jar) can diverge from local: `mvn test` is green but the deployed artifact is broken or missing the frontend. We add a **container-smoke harness** that builds and runs the *image* and HTTP-asserts the running app, plus the **CI jobs** that gate on it and on the already-written browser e2e.

## Starting Point

The Docker image builds correctly, Flyway self-migrates a fresh Postgres on boot, and the Playwright runner + critical-flow spec (`log-match.spec.ts`) already landed in commit `159dc37`. What's missing: nothing today runs the app *as a container* (`run-local.sh` runs it on the host via `spring-boot:run`), and CI has no container-smoke or e2e job.

## Desired End State

A developer can run `./smoke-container.sh` locally to build, boot, assert, and tear down the image. Every PR runs a `container-smoke` job (image built once, HTTP-asserted) and an `e2e` job (same image, browser happy-path); `ci-success` gates on both. The test plan's two factual errors are corrected.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Harness host | Bash script (`docker build`/`run` + curl) | Mirrors what Render does; identical local + CI; reuses `run-local.sh` idiom | Plan |
| Local-runnable? | Yes — one script for local + CI, with cleanup sibling | A red CI smoke must be reproducible on a laptop — the whole point of #6 | Plan |
| Image reuse across jobs | Build once in smoke, share via `docker save` + artifact | Halves CI wall-clock; e2e provably runs the *same* artifact the smoke approved | Plan |
| Test-plan errors | Correct `test-plan.md` in-place | Foundation doc that drove this phase stays truthful for the next reader | Research |
| E2e CI shape | Gate the existing spec as-is (parse mocked at browser edge) | Deterministic in CI; real Gemini path stays out of CI per §7 | Research |
| DB wiring | Inject discrete `DB_HOST/PORT/NAME/USER/PASSWORD` | App composes JDBC from five vars; there is no `DATABASE_URL` | Research |
| Smoke gate assertion | `401` on `GET /api/auth/me` | Client routes return `200 index.html`; 401 is the only curl-observable gate | Research |

## Scope

**In scope:**
- `smoke-container.sh` + `stop-smoke-container.sh` (build, boot vs throwaway Postgres on a shared network, poll health, assert `health 200 UP` / `/ 200 index.html` / `/api/auth/me 401`, teardown).
- `ci.yml`: `container-smoke` job (build once + share image artifact), `e2e` job (`needs: container-smoke`, loads image, runs `npm run e2e`), extend `ci-success.needs`.
- Correct `test-plan.md` (§3/§5/§6.6) wording.

**Out of scope:**
- Rewriting the existing Playwright spec/runner (already landed & verified).
- Any live-LLM e2e variant or server-side LLM bypass (determinism stays at the HTTP edge).
- Broadening e2e beyond the single login → log match → history path.
- Touching `render.yaml` / `Dockerfile` (already correct).

## Architecture / Approach

`smoke-container.sh` creates a user-defined docker network, starts `postgres:17` on it, `docker build`s the app image, runs it on the same network (DB reached **by container name**, not `host.docker.internal` — the Linux trap), polls `/actuator/health` until UP with a bounded timeout, runs three curl assertions, and always tears down via a trap. In CI the `container-smoke` job runs the harness then `docker save`s the image as an artifact; the `e2e` job `docker load`s that exact image, boots it, points Playwright at it via `E2E_BASE_URL`, and runs the spec. `ci-success` grows to require both.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Container-smoke harness | `smoke-container.sh` + cleanup, local + CI-ready | Linux container networking (`host.docker.internal` doesn't resolve); flaky boot wait if not health-polled |
| 2. CI wiring | `container-smoke` + `e2e` jobs, image shared once, aggregator extended | Artifact share mechanics; e2e browser install / job wall-clock |
| 3. Correct test-plan.md | Foundation doc states the real facts | Low — prose edit, grep-verified |

**Prerequisites:** Docker available locally and on the CI runner (GitHub-hosted `ubuntu-latest` has it); the existing Playwright spec (already present).
**Estimated effort:** ~1–2 sessions across 3 phases (harness is the bulk; CI is wiring; doc fix is minutes).

## Open Risks & Assumptions

- The Linux `host.docker.internal` trap (`lessons.md`) — mitigated by a shared network + container-name DB host; must be gotten right in Phase 1.
- Boot timing varies (Flyway + Spring startup) — mitigated by bounded health-polling, never fixed sleeps.
- CI wall-clock from the image build — mitigated by build-once + artifact share.

## Success Criteria (Summary)

- `./smoke-container.sh` exits `0` locally with all three HTTP assertions green and clean teardown.
- A PR shows `container-smoke` and `e2e` as green required checks; a broken image/spec blocks `ci-success`.
- `grep "DATABASE_URL\|gated redirect" context/foundation/test-plan.md` returns nothing.
