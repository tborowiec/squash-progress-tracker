---
change_id: container-smoke-e2e-tests
title: "Phase 5: container smoke + critical-flow e2e (test rollout)"
status: archived
created: 2026-06-07
updated: 2026-06-07
archived_at: 2026-06-07T20:12:43Z
---

## Notes

Phase 5 of the phased test rollout (`context/foundation/test-plan.md` §3).
Goal: build, boot, and HTTP-smoke the Docker image, then one browser
happy-path (login → log match → history) against the running app.

Covers risk #6 (build/deploy parity — the *running* artifact, not just a
green `mvn test`), and exercises #4/#5 at the deployed layer. Two layers:

- **Container smoke** (deterministic, no browser): build the image, run it
  against a throwaway Postgres, assert `/actuator/health` + SPA root + a
  gated-route redirect over HTTP. Watch the deploy-mapping seams —
  `DATABASE_URL` → `SPRING_DATASOURCE_*`, `$PORT` binding.
- **Critical-flow e2e** (Playwright, Module 3 Lesson 4 wires the runner):
  a single happy-path against the running image. Keep it to one path —
  breadth belongs in the cheaper component/integration layers.
