# Render Deployment Integration Plan — Squash Progress Tracker

## Context

`context/foundation/infrastructure.md` (2026-05-30) selected **Render** as the MVP deployment
platform (runner-up Railway, over the original Fly.io hint) for a Java 21 / Spring Boot 4.0.6
app, using a phased free → paid rollout for both the web service and managed Postgres.

The repo today is a **bare Spring Boot scaffold**: `spring-boot-starter-webmvc` + devtools only.
There is **no** Dockerfile, no `render.yaml`, no Postgres driver, no JPA, no Actuator, no
datasource config, and no `context/deployment/` directory. So "integrate with Render" means
building every deploy artifact from scratch and wiring the database connection correctly.

**Decisions locked with the user (2026-05-30):**
- **Scope:** Web service **+ managed Postgres now** (DB provisioned and wired this deploy).
- **Region:** **Frankfurt** (EU — lowest latency for likely EU users; AI provider still undecided,
  leaning Gemini, so user proximity is the safer default — re-evaluate when the provider/region locks).
- **Deploy trigger:** **Manual first** (confirm the build is green via CLI/dashboard), then enable
  GitHub auto-deploy on push to `main`.

**Goal / definition of done for THIS plan:** the bare scaffold builds into a container and deploys
to Render in Frankfurt with a co-located managed Postgres, reachable at a **public `onrender.com`
URL**, where the Actuator health check confirms both that **the app is up** and that **the DB
connection works**. Auto-deploy on push to `main` is wired as the final step. Everything past that —
upgrading off the free tier, full persistence, the AI flow — is **explicitly out of scope** and
captured under [Future work](#future-work--out-of-scope-for-this-plan) so it isn't forgotten.

**Why the DB is wired but persistence is not:** the app has no persistence code yet, so this deploy
adds only `spring-boot-starter-jdbc` (not full JPA) + the Postgres driver. That gives a Hikari
`DataSource` and lets Actuator's `db` health indicator run `SELECT 1` — proving the wiring works
**without** forcing a hard startup-crash if the DB is briefly unreachable, and without inventing
schema/entities prematurely. Full JPA + Flyway migrations + the per-player auth boundary are a
**future change** (see [Future work](#future-work--out-of-scope-for-this-plan)).

---

## ⚠️ Manual prerequisites — do these by hand BEFORE we run any deploy

These are account/secret/external-integration steps the agent should NOT (and in some cases cannot)
perform. Tick each before Phase 2.

- [ ] **GitHub:** confirm this repo is pushed to a GitHub remote (Render deploys from a connected
      Git repo, not a local folder). `git remote -v` should show a GitHub URL on `main`.
- [ ] **Render account + workspace:** sign in at <https://dashboard.render.com> and confirm/create
      the workspace this project will live in.
- [ ] **Connect GitHub to Render:** in the Render dashboard, authorize the Render GitHub app for
      this repository (Account Settings → GitHub). Without this, blueprint launch can't see the repo.
- [ ] **Install the Render CLI** and authenticate interactively:
      `brew install render` (macOS) or download the Linux binary from
      <https://github.com/render-oss/cli/releases>, then `render login` (opens a browser).
- [ ] **Create a workspace API key** for non-interactive/CLI/agent use:
      Dashboard → Account Settings → API Keys → create one, scoped to this workspace; then
      `export RENDER_API_KEY=...` in your shell profile. *(Render API keys are workspace-level, not
      per-service — keep this key out of the repo; it lives in an env var only.)*
- [ ] **Calendar reminder (for later):** the free Postgres 30-day deletion clock starts the moment
      the DB is created in Phase 2, so set a reminder for **~day 20** now — acting on it is
      [Future work](#future-work--out-of-scope-for-this-plan), but the reminder has to be set at deploy time to be useful.
- [ ] *(Deferred, not needed for this deploy)* the AI provider key (`LLM_API_KEY`) — the AI is not
      wired yet, so no provider secret is required to ship this milestone. Leave it out for now.

---

## Phase 1 — Make the app deployable (code & artifacts, committed to repo)

All of these are agent-doable edits, verified locally before any cloud action.

- [ ] **`pom.xml`** — add three managed dependencies (versions inherited from the Boot 4.0.6 parent):
  - `org.springframework.boot:spring-boot-starter-actuator` (health checks)
  - `org.springframework.boot:spring-boot-starter-jdbc` (DataSource autoconfig — lighter than
    `data-jpa`; upgrade to `data-jpa` in the future persistence change)
  - `org.postgresql:postgresql` (runtime scope, JDBC driver)
- [ ] **`src/main/resources/application.properties`** — add:
  ```properties
  server.port=${PORT:8080}

  # DB wiring — built from discrete parts injected by render.yaml's fromDatabase.
  # NEVER point spring.datasource.url at Render's raw connectionString (postgres://...) — it is
  # not a JDBC URL and fails at startup. This is the #1 Spring-on-Render gotcha.
  spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
  spring.datasource.username=${DB_USER}
  spring.datasource.password=${DB_PASSWORD}

  # Actuator: expose health; include DB indicator so /actuator/health proves DB connectivity.
  management.endpoints.web.exposure.include=health
  management.endpoint.health.show-details=never
  ```
- [ ] **`Dockerfile`** (multi-stage, root of repo) — pinned Temurin 21, dependency-layer caching:
  ```dockerfile
  # ---- build stage ----
  FROM maven:3.9-eclipse-temurin-21 AS build
  WORKDIR /app
  COPY .mvn/ .mvn/
  COPY mvnw pom.xml ./
  RUN ./mvnw -q -B dependency:go-offline       # cache deps in their own layer
  COPY src/ src/
  RUN ./mvnw -q -B clean package -DskipTests

  # ---- runtime stage ----
  FROM eclipse-temurin:21-jre               # pin by digest in hardening (see edge cases)
  WORKDIR /app
  COPY --from=build /app/target/squash-progress-tracker-0.0.1-SNAPSHOT.jar app.jar
  EXPOSE 8080
  ENTRYPOINT ["java","-jar","app.jar"]
  ```
  *(JVM heap tuning is set as a Render env var, not baked into the image — see render.yaml — so it's
  tunable without a rebuild.)*
- [ ] **`.dockerignore`** — exclude `target/`, `.git/`, `.idea/`, `*.iml`, `context/`, `.mvn/wrapper/*.jar`
      noise to keep build context small and avoid leaking the local build.
- [ ] **`render.yaml`** (blueprint, root of repo):
  ```yaml
  services:
    - type: web
      name: squash-progress-tracker
      runtime: docker
      dockerfilePath: ./Dockerfile
      plan: free                 # free is fine for this plan; upgrading is Future work
      region: frankfurt
      branch: main
      autoDeployTrigger: off     # manual first; flip to "commit" in Phase 3
      healthCheckPath: /actuator/health
      envVars:
        - key: JAVA_TOOL_OPTIONS
          value: -XX:MaxRAMPercentage=75   # stop JVM OOM-kill on 512MB free/starter
        - key: DB_HOST
          fromDatabase: { name: squash-db, property: host }
        - key: DB_PORT
          fromDatabase: { name: squash-db, property: port }
        - key: DB_NAME
          fromDatabase: { name: squash-db, property: database }
        - key: DB_USER
          fromDatabase: { name: squash-db, property: user }
        - key: DB_PASSWORD
          fromDatabase: { name: squash-db, property: password }

  databases:
    - name: squash-db
      plan: free                 # free is fine for this plan; upgrading is Future work
      region: frankfurt          # MUST match the web service region (co-location)
      postgresMajorVersion: "17"

  previews:
    generation: automatic        # per-PR preview env; pinned to free instance type
  ```
- [ ] **Local verification (before any cloud step)** — see Verification section below.

**Edge case to confirm during implementation:** the exact free-plan name for Postgres in blueprints
(`plan: free`) and that `host`/`port` are valid `fromDatabase` properties on the current spec. The
blueprint docs list `connectionString`, `database`, `user`, `password` explicitly and treat
`host`/`port` as available connection properties. **Fallback if `host`/`port` are rejected at
blueprint validation:** inject a single `SPRING_DATASOURCE_URL` env var by hand in the dashboard
(built from the DB's Info tab) instead of the five discrete vars — keep `username`/`password` as
separate vars regardless.

---

## Phase 2 — First deploy → public link + confirmed app & DB (manual, human-initiated)

**This is the milestone the plan is built around: a public URL where the app is up and the DB
connection is verified.**

- [ ] Commit Phase 1 artifacts and push to `main` (the blueprint must exist on the connected branch).
- [ ] Launch the blueprint: Render dashboard → **New → Blueprint** → pick this repo, **or**
      `render blueprint launch` from the CLI. This creates the web service (free) **and** the
      Postgres DB (free) in Frankfurt in one shot.
- [ ] Confirm in the dashboard that the five `DB_*` env vars resolved (non-empty) from `squash-db`.
- [ ] Watch the build + deploy logs: `render logs --resources <web-service-id> --tail`
      (or the dashboard log stream). Build pulls Temurin, runs `mvnw package`, starts the JAR.
- [ ] **Done when:** the service is live at its public `https://<service>.onrender.com` URL and
      `/actuator/health` returns `{"status":"UP"}` with `db: UP` — a single check that proves both
      the app booted and the DB connection works end-to-end (see Verification).
- [ ] If it fails: the most likely culprits are the DATABASE_URL mapping (check `DB_*` resolved),
      OOM on boot (check logs for `Killed` / exit 137 → confirm `JAVA_TOOL_OPTIONS` applied), or a
      health-check timeout during JVM warm-up (raise the grace period / confirm path). See edge cases.

---

## Phase 3 — Enable auto-deploy (final step of this plan)

- [ ] Flip `autoDeployTrigger: off` → `commit` in `render.yaml`, commit, push, re-sync the blueprint
      (or toggle Auto-Deploy = Yes in the dashboard). From here, every merge to `main` ships —
      matching the planned GitHub Actions CI flow noted in `AGENTS.md`.
- [ ] Sanity-check that a trivial push to `main` triggers a deploy and goes green.

**At this point the plan is complete:** the app is publicly reachable, app + DB are confirmed
working, and merges to `main` redeploy automatically. The remaining sections below (Edge cases,
Verification, Artifacts) support executing this plan; only the explicitly-labelled
[Future work](#future-work--out-of-scope-for-this-plan) section is out of scope.

---

## Edge cases & extra support (external-integration hardening)

| Concern | Handling in this plan |
|---|---|
| **`DATABASE_URL` is `postgres://`, not JDBC** | Never used directly. We inject discrete `DB_HOST/PORT/NAME/USER/PASSWORD` via `fromDatabase` and build `jdbc:postgresql://...` in `application.properties`. Fallback: set one `SPRING_DATASOURCE_URL` by hand. |
| **JVM OOM-kill on 512 MB** | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` as a Render env var (tunable without rebuild). Watch for exit code 137 in logs. |
| **Health check false-fails during warm-up** | Actuator + `healthCheckPath: /actuator/health`; if the JVM is slow to warm, raise Render's health-check grace period rather than removing the check. |
| **Render CLI is interactive (TUI) by default** | For agent/CI use, always pass `-o json --confirm` and rely on `RENDER_API_KEY` — never assume an interactive prompt will be answered. |
| **Base-image / TLS drift** | Pin the runtime image by digest (`eclipse-temurin:21-jre@sha256:...`) once the first green build identifies a known-good digest; multi-stage caching keeps rebuilds fast. |
| **Local dev needs Postgres** | `spring-boot-starter-jdbc` won't crash boot if the DB is down (Hikari connects lazily), but `/actuator/health` will report `db: DOWN`. For local testing run `docker run -e POSTGRES_PASSWORD=pass -p 5432:5432 postgres:17` and point the `DB_*` vars at it. |
| **`spring-boot-devtools` in the image** | Harmless — devtools auto-disables when run from a packaged fat JAR; no action needed. |
| **Region co-location** | Both `web` and `databases` set `region: frankfurt`; mismatched regions add app↔DB RTT. |
| **Free-tier limits (cold start, DB deletion)** | Acceptable for this plan's scope (solo dev / verification on a fresh DB). They become real before sharing the link or storing real data — handled in [Future work](#future-work--out-of-scope-for-this-plan), not here. |

---

## Verification

**Local (Phase 1, before any cloud step):**
1. `./mvnw -q clean package -DskipTests` — confirms the new deps resolve and the JAR builds.
2. `docker build -t squash:local .` then start a throwaway DB:
   `docker run -d --name pg -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=squash -p 5432:5432 postgres:17`
3. `docker run --rm -p 8080:8080 -e DB_HOST=host.docker.internal -e DB_PORT=5432 -e DB_NAME=squash -e DB_USER=postgres -e DB_PASSWORD=pass squash:local`
4. `curl localhost:8080/actuator/health` → expect `{"status":"UP"}` (with `db` UP internally).

**Post-deploy (Phase 2 — the definition of done):**
1. `render deploys list --resources <web-service-id> -o json` → latest deploy `status: live`.
2. `curl https://<service>.onrender.com/actuator/health` → `{"status":"UP"}` — confirms boot **and**
   DB connectivity in one check, at the public URL.
3. `render logs --resources <web-service-id> --tail` → no OOM (`137`), no datasource errors,
   `Started SquashProgressTrackerApplication` present.

**Rollback drill (optional confidence check):** `render rollback <web-service-id>` (or dashboard)
reverts to the prior deploy in seconds — code only, not data.

---

## Artifacts produced

- `Dockerfile`, `.dockerignore`, `render.yaml` (repo root)
- Edits to `pom.xml`, `src/main/resources/application.properties`
- `context/deployment/deploy-plan.md` — this approved plan persisted as the deploy audit trail.

---

## Future work — out of scope for this plan

These are **not** part of shipping this milestone. They are recorded here so they aren't lost, and
should each be opened as a separate change when their trigger arrives.

### Phased free → paid upgrades (human-only billing actions)

Triggered later, when the app moves from "verified working" toward real use. The free-tier clock
starts at the Phase 2 deploy (hence the prerequisite calendar reminder), but acting on these is future:

- **Upgrade the web service to Starter ($7)** *before sharing the link with a real user.* The free
  web service spins down after 15 min idle; waking it stacks Render's ~1-min spin-up on top of
  Spring Boot cold start → >1 min first-request latency, fatal for "click to try".
- **Upgrade Postgres to a paid plan ($7, backups + PITR)** *before day 25 and before any real match
  history is saved.* Free Postgres is **deleted 30 days after creation** (14-day grace, then the data
  is gone — not paused). Never demo to real users on the free DB.

### Persistence + product groundwork (separate future change)

- Swap `spring-boot-starter-jdbc` → `spring-boot-starter-data-jpa`, add **Flyway** with
  **expand-then-contract (backward-compatible) migrations only** (a deploy rollback restores code,
  not schema — back up the DB before any destructive migration).
- Implement the match-history entities, Spring Security, and the **per-player auth boundary**
  (AGENTS.md hard rule: one player's history must never be readable by another).
- The AI parse/preview-confirm flow and game-plan generation (needs the deferred `LLM_API_KEY`).

## Sources

- [Render Blueprint YAML Reference](https://render.com/docs/blueprint-spec)
- [Render — Deploy for Free / free-tier limits](https://render.com/docs/free)
- [Render Changelog — free Postgres expires after 30 days](https://render.com/changelog/free-postgresql-instances-now-expire-after-30-days-previously-90)
- [The Render CLI](https://render.com/docs/cli) · [CLI Reference](https://render.com/docs/cli-reference)
- [Deploying Spring Boot and PostgreSQL on Render](https://medium.com/@alamgir.ahosain/deploying-spring-boot-and-postgresql-to-render-9b4d597bbaaf)
