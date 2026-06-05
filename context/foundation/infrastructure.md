---
project: "Squash Progress Tracker"
researched_at: 2026-05-30
recommended_platform: Render
runner_up: Railway
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM (container / Docker)
---

## Recommendation

**Deploy on Render**, using a phased free → paid rollout for both the web service and the database.

Render scores 5/5 against the agent-friendly criteria (first-class CLI, fully managed, `llms.txt` + `llms-full.txt` docs, deterministic deploy API, GA MCP server since Aug 2025) and matches every interview constraint: the developer is already familiar with it, its **flat, predictable pricing** is the right answer for a "minimize cost" priority (no usage-based bill-shock), and single-region is exactly what Render does. The one real cost — Render has no native Java runtime, so a Spring Boot Dockerfile must be hand-maintained — is a standard, well-trodden piece of work and was accepted knowingly. Railway is the runner-up (better build DX via Railpack auto-detect, but usage-based billing carries bill-shock risk and its MCP is still beta); Fly.io is third (best raw agent-friendliness and the original stack hint, but unfamiliar, with managed Postgres at ~3–4× the cost and edge strengths wasted on a single-region app).

> **Contract alignment (reconciled 2026-05-30):** this decision supersedes the original Fly.io hint. `context/foundation/tech-stack.md` (`deployment_target: render` + rationale prose) and the `AGENTS.md` Commit Guidelines line ("auto-deploy to Render on merge to main") have been updated to match. No divergence remains.

## Platform Comparison

Raw scoring against the five agent-friendly criteria (`references/agent-friendly-criteria.md`). The **ranking** below applies the interview weights (minimize cost; familiar with Railway/Render; single region; co-location undecided) on top of the raw scores — which is why Fly's 5/5 does not make it the leader.

| Platform | CLI-first | Managed/Serverless | Agent docs | Stable deploy API | MCP / Integration | Raw result |
|---|---|---|---|---|---|---|
| **Render** | Pass | Pass | Pass (`llms.txt` + `llms-full.txt`) | Pass | Pass (MCP GA, Aug 2025) | **5 / 5** |
| **Fly.io** | Pass | Pass | Pass | Pass | Pass (MCP GA) | 5 / 5 |
| Cloudflare | Pass | Pass | Pass (best-in-class `llms.txt`) | Pass | Pass | 5/5 raw, but **runtime-fit Fail** for a long-lived JVM |
| **Railway** | Pass | Pass | Pass (`.md` URLs) | Pass | Partial (MCP beta / WIP) | 4 Pass / 1 Partial |
| Vercel | — | — | — | — | — | **Dropped** — no JVM runtime |
| Netlify | — | — | — | — | — | **Dropped** — no JVM runtime |

**Per-platform notes:**

- **Render** — Long-running web services are the core model (no serverless time cap). Java ships via a multi-stage Dockerfile (no native Java env). First-class `render` CLI (v2) does `deploys create/list`, `logs` (live tail), `rollbacks`, `ssh`, `psql`, blueprint validation. Docs publish `render.com/docs/llms-full.txt` and `api-docs.render.com/llms.txt`. Official MCP server GA Aug 2025. Free tier exists but is a trap for a JVM (see risks).
- **Fly.io** — Best raw agent-friendliness: arbitrary Docker/JVM, mature `flyctl`, GA MCP (`fly mcp server`), markdown docs. Dropped to third on weights: no developer familiarity, managed Postgres is **$38/mo** (vs ~$7 elsewhere; the $2/mo unmanaged option shifts backup/HA burden onto a solo dev holding the only copy of users' match history), and its global-edge / scale-to-zero strengths are moot for a single-region, always-on JVM (whose cold starts make scale-to-zero unusable anyway).
- **Cloudflare** — Workers run a V8/WASM isolate, not a JVM. **Cloudflare Containers went GA 2026-04-13** and *can* run a Docker JVM image, but it is Worker-orchestrated with scale-to-zero cold starts — a poor fit for a long-lived JVM and only weeks-old GA. Scores 5/5 on raw criteria, fails on runtime suitability. Not shortlisted.
- **Railway** — Strong runner-up. Railpack auto-detects Maven + Java 21 (no Dockerfile needed), always-on by default, `railway up`/`redeploy`/`logs` CLI, `.md` docs. Two gaps: usage-based billing (RAM at $10/GB-month, no hard spend cap on Hobby → JVM bill-shock risk) and a self-described "work in progress" MCP server.
- **Vercel / Netlify** — Hard-filtered before scoring. Neither offers a Java runtime or general container hosting; both are static + short-lived serverless functions (Vercel 300s cap; Netlify 60s, JS/Go only). A persistent Spring Boot JVM server cannot run on either.

### Shortlisted Platforms

#### 1. Render (Recommended)

Wins on the combination that matters here: equal familiarity to Railway, **predictable flat pricing** (the cost-minimizer's real need is no surprise bills, not the absolute floor), GA MCP server, and — confirmed during research — `llms.txt`/`llms-full.txt` docs that let the agent reason over canonical docs directly. The DB story is clean and co-located: Render's own managed Postgres includes daily backups + PITR on paid, in the same region as the app (one bill, one region, low app↔DB RTT). The accepted cost is a hand-maintained Spring Boot Dockerfile.

#### 2. Railway

Best build experience for this exact stack — Railpack auto-detects a Maven Spring Boot project and defaults to Java 21, so there is no Dockerfile to write or maintain, and it is always-on by default and likely cheaper at genuinely low traffic. It lost the lead on the developer's stated top priority (cost *predictability*): usage-based RAM billing with no hard spend cap turns an uncapped JVM heap into bill-shock, and its MCP server is still beta. A strong fallback if the Dockerfile maintenance on Render proves more friction than the billing risk on Railway.

#### 3. Fly.io

The highest raw agent-friendliness score (5/5) and the platform the original tech-stack hint named. Third here because all three interview weights pull against it: no prior familiarity, managed Postgres ~3–4× the cost of the alternatives (with the cheap option offloading backup/HA risk onto a solo operator), and a global-edge architecture whose value evaporates for a single-region MVP. Keep it in mind only if alignment with the existing `deployment_target: fly` hint is judged more important than cost and familiarity.

## Anti-Bias Cross-Check: Render

### Devil's Advocate — Weaknesses

1. **No native Java runtime → you own a Dockerfile.** Spring Boot ships only via a hand-written multi-stage Dockerfile (Maven build + Temurin 21). It's a real artifact to maintain; a wrong base image or broken layer caching balloons build times on every after-hours push.
2. **The free tier is a trap for a JVM, not a gift.** The free web service spins down after 15 min idle; waking it stacks Render's ~1-min spin-up *on top of* Spring Boot's cold start, so the first request after idle can take well over a minute — effectively unusable for a shared link.
3. **Free Postgres self-destructs.** Free databases are **deleted 30 days after creation** (cut from 90 in May 2024), with a 14-day grace, then the DB *and its data* are gone. Prototype-and-forget = total loss of the only copy of users' match history.
4. **512 MB Starter is tight for a JVM.** Without `-XX:MaxRAMPercentage`/`-Xmx` tuning, Spring Boot OOM-kills on the Starter instance; the tuning burden is identical to Railway's — Render just surfaces the failure as an OOM rather than a higher bill.
5. **The phased free → paid plan has a hard cliff.** The chosen "start free, upgrade later" strategy only works if the upgrade actually happens *before* day 30 (DB) and *before* the first external user (web). Miss either trigger and the cheap phase ends in data loss or a 70-second cold start in front of a first impression.

### Pre-Mortem — How This Could Fail

The team chose Render for predictable pricing and shipped behind a hand-written Dockerfile. It worked — until a Spring Boot 4 point upgrade needed a newer base image, and the multi-stage Dockerfile nobody had touched in months started failing layer caching; every deploy now took nine minutes and after-hours momentum died waiting on builds. Worse, the MVP had been demoed on a *free* Postgres instance; in the rush nobody migrated it to paid, and on day 31 Render deleted the database — the founder's own test history and two early friends' real matches, gone, no backup because free DBs have none. Rebuilding trust with those first users cost more than the app ever saved. Meanwhile the free web service's spin-down meant every "come try it" link a friend opened on a quiet evening hung for 70 seconds before responding; three of five never came back. Render did exactly what it promised — the failures all lived in the gap between "free tier" and "what a JVM app with real users actually needs," a gap the flat-pricing story had quietly papered over.

### Unknown Unknowns

- **`DATABASE_URL` is not a JDBC URL.** Render injects a Postgres connection string in `postgres://user:pass@host/db` form; Spring Boot expects `jdbc:postgresql://host:5432/db` plus separate username/password. You must map/transform it (set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`) — pasting `DATABASE_URL` straight into `spring.datasource.url` fails at startup. This is the single most common Spring-on-Render gotcha.
- **Free Postgres deletion is a time bomb, not a suspension** — the data is removed, not paused. Any real data must be on paid Postgres before day 30.
- **Free web spin-down + JVM cold start compounds** to >1 min wake latency — fine for a hobby cron, fatal for a "click to try" flow. Always-on requires the paid tier.
- **Flat pricing hides the same memory tuning.** Render won't bill-shock you like a usage model, but the *engineering* (heap caps so 512 MB doesn't OOM) is identical — it just fails as an OOM-kill instead of a bigger invoice.
- **Region ↔ AI-provider API RTT eats the <5s parse NFR.** Render's region is fixed per service and non-edge; if the service region is far from the chosen AI provider's API region, network round-trips silently consume the latency budget the PRD's 5-second parse target depends on. (Provider resolved: **Google Gemini** via OpenAI-compat — keep the Render region EU/Frankfurt-aligned with Gemini's EU endpoints; see Out of Scope.)

## Operational Story

- **Preview deploys**: Render Preview Environments spin up a per-PR copy of the services in `render.yaml` (enable `previews` / `previewsEnabled`); each gets its own URL. Pin previews to the free instance type to avoid paying per PR. Treat preview URLs as non-public; don't point real OAuth/secrets at them.
- **Secrets**: Stored as Render environment variables / Env Groups (and secret files) in the dashboard or via the `render` CLI / blueprint — never committed. The app reads the AI provider's API key (`LLM_API_KEY`, kept generic to preserve provider-swappability; provider = Gemini, see Out of Scope) and the DB credentials (`SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`, mapped from Render's `DATABASE_URL`) from the environment. Rotation = set the new value in the dashboard and redeploy.
- **Rollback**: `render rollback` (or "Rollback" in the dashboard) reverts the service to a prior successful deploy in seconds. **Caveat:** a rollback restores *code*, not data — Flyway/Liquibase schema migrations are not undone by a deploy rollback. Backward-compatible (expand-then-contract) migrations only, and take a DB backup before any destructive migration.
- **Approval (human-only)**: upgrading the instance/DB plan (billing), creating or deleting a database, the free → paid Postgres migration, and rotating the AI-provider API key are panel-by-hand operations. The agent may, unattended: trigger a deploy, tail logs, list deploys, and execute a rollback.
- **Logs**: `render logs --resources <service-id> --tail` for live runtime/build logs (filterable), `render deploys list` for deploy history; the Render MCP server exposes the same read-only surface as structured tools if the agent makes many log/state queries.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Free Postgres deleted 30 days after creation (14-day grace) → total data loss | Unknown unknowns / Research finding | H (if unmanaged) | H | Calendar reminder at day ~20; upgrade to paid Postgres ($7, backups + PITR) before *any* real user data lands; never demo to real users on free DB. |
| Phased free → paid upgrade never happens (forgotten trigger) | Pre-mortem | M | H | Write the two triggers into `deploy-plan.md`: upgrade **web** before first external user; upgrade **DB** before day 25. Make them explicit go/no-go gates. |
| Free web service spin-down + JVM cold start → >1 min first-request latency | Devil's advocate | H (on free) | M | Free tier only for solo dev/demo; upgrade to Starter ($7) before sharing any link. |
| `DATABASE_URL` (postgres://) fed directly to Spring → startup failure | Unknown unknowns | M | H | Map to `SPRING_DATASOURCE_URL=jdbc:postgresql://...` + separate username/password env vars; verify locally against the same scheme. |
| JVM OOM-kill on 512 MB Starter | Devil's advocate / Pre-mortem | M | H | Set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`; add Actuator health check; monitor memory; size up one tier if sustained. |
| Dockerfile rot / build-time blowup on base-image or Spring Boot bump | Pre-mortem | M | M | Pin an exact base image tag (`eclipse-temurin:21-jre-<digest>`), multi-stage with dependency layer caching, commit to repo, let CI build it on every push. |
| Flyway/Liquibase migration not reversed by a deploy rollback | Research finding | M | H | Expand-then-contract (backward-compatible) migrations only; back up the DB before destructive changes; test migrations on a preview/branch first. |
| Service region ↔ AI-provider API RTT squeezes the <5s parse NFR | Pre-mortem / Unknown unknowns | M | M | Choose the Render region closest to the chosen AI provider's API region; keep the streaming/progress UI so perceived latency stays acceptable; measure end-to-end. |
| TLS/JDK behavior drift from an unpinned base image | Pre-mortem | L | M | Pin the exact base image digest; reproduce the production JDK locally before relying on TLS-sensitive SDK calls. |

## Getting Started

Specific to Java 21 / Spring Boot 4.0.6 / Maven on Render (validated against the pinned stack, not generic docs):

1. **Add a multi-stage Dockerfile** (Render has no native Java env): stage 1 `eclipse-temurin:21` (or `maven:3.9-eclipse-temurin-21`) runs `./mvnw -q clean package -DskipTests`; stage 2 `eclipse-temurin:21-jre` copies the fat JAR and runs `java -jar app.jar`. Set `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"` and bind to Render's port via `server.port=${PORT}` in `application.properties`.
2. **Add Actuator for health checks**: include `spring-boot-starter-actuator` and set Render's health check path to `/actuator/health` so deploys don't false-fail during the JVM warm-up.
3. **Add a `render.yaml` blueprint** defining a Docker web service (start on the **free** plan) and a Postgres database (start **free**), with preview environments enabled. Keep it in the repo so the agent can reason over the infra as code.
4. **Install the Render CLI and log in**: download the binary from the Render CLI releases (Linux) or `brew install render` (macOS), then `render login`.
5. **Wire env / DB credentials correctly**: do **not** point Spring at the raw `DATABASE_URL`. Set `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and the AI provider's API key (placeholder `LLM_API_KEY`, renamed once the provider is chosen) as Render env vars / an Env Group.
6. **Connect the GitHub repo for auto-deploy on push to `main`** (matches the planned CI flow), or deploy manually with `render deploys create`.
7. **Schedule the phased upgrades as gates** (record in `context/deployment/deploy-plan.md`): upgrade the **web service** to Starter ($7) before sharing a link with any real user; upgrade the **Postgres** to paid ($7, enable backups) before day 25 and before any real match history is saved. Both upgrades are human-approved billing actions.

## Out of Scope

The following were not evaluated in this research:
- **AI/LLM provider selection — RESOLVED 2026-06-03 (was deferred at research time).** The product needs an LLM for text parsing (FR-003) and game-plan generation (FR-010). The *provider* was chosen during F-02 (`llm-client`, GitHub issue #2): **Google Gemini** (`gemini-2.5-flash`) via its **OpenAI-compatible endpoint** behind a thin direct adapter (not Spring AI). This document was written provider-agnostic and stays so by design: it refers to a generic `LLM_API_KEY` / `LLM_BASE_URL` and "the AI-provider API", which keeps the provider swappable. Two infra-relevant points still hold: (1) the provider's API region affects the <5s parse NFR — Gemini EU/Vertex co-located with the Frankfurt Render region; (2) the API key is a human-rotated secret.
- Docker image configuration beyond the getting-started sketch (full hardening, distroless, native-image/GraalVM).
- CI/CD pipeline setup (the GitHub Actions auto-deploy flow is planned but designed elsewhere).
- Production-scale architecture (multi-region, HA, disaster recovery, autoscaling).
