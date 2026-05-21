---
bootstrapped_at: 2026-05-21T00:00:00Z
starter_id: spring
starter_name: Spring Boot
project_name: squash-progress-tracker
language_family: java
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

```yaml
starter_id: spring
package_manager: maven
project_name: squash-progress-tracker
hints:
  language_family: java
  team_size: solo
  deployment_target: fly
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: true
  has_background_jobs: false
```

**Why this stack**: Spring Boot is the vetted recommended default for a Java web-app and clears all four agent-friendly quality gates — typed (Java generics and Spring's strong typing), convention-based (DI, autoconfiguration, and prescribed file layout), popular in Java training data, and well-documented with version-pinned reference docs. The 3-week after-hours timeline favors a verified bootstrapper confidence, meaning scaffolding runs end-to-end without manual steps. Spring Security covers auth (FR-001, FR-002) first-class. The one gap is AI/LLM: FR-003 and FR-010 require AI text parsing and game-plan generation; no Java starter in the registry ships with AI bundled, so Spring AI or a direct Anthropic/OpenAI Java SDK dependency will need to be added manually after scaffolding. Deployment targets Fly.io (the card's first default; subject to change after scaffolding); CI runs on GitHub Actions with auto-deploy on merge to main.

## Pre-scaffold verification

| Signal      | Value    | Severity | Notes                                                         |
| ----------- | -------- | -------- | ------------------------------------------------------------- |
| npm package | not run  | n/a      | java language_family — no npm CLI in cmd_template             |
| GitHub repo | not run  | n/a      | docs_url (https://docs.spring.io/spring-boot/) is not a GitHub URL |

No recency signal available for this starter. The Spring Boot framework is maintained by VMware/Broadcom; check https://spring.io/projects/spring-boot for release status.

## Scaffold log

**Resolved invocation**: `curl -s https://start.spring.io/starter.tgz -d dependencies=web,devtools -d type=maven-project -d javaVersion=21 -d groupId=com.example -d artifactId=squash-progress-tracker | tar -xzf - -C .bootstrap-scaffold`
**Strategy**: subdir-then-move
**Exit code**: 0
**Files moved**: 10
**Conflicts (.scaffold siblings)**: none
**.gitignore handling**: append-merged (2 lines de-duped: `.idea`, `*.iml`; Spring-specific lines appended under `# from spring` separator)
**.bootstrap-scaffold cleanup**: deleted

Files moved from `.bootstrap-scaffold/`:

| File | Action |
| ---- | ------ |
| `.gitattributes` | moved silently |
| `.gitignore` | append-merged into existing cwd `.gitignore` |
| `HELP.md` | moved silently |
| `mvnw` | moved silently |
| `mvnw.cmd` | moved silently |
| `.mvn/wrapper/maven-wrapper.properties` | moved silently |
| `pom.xml` | moved silently |
| `src/main/java/com/example/squash_progress_tracker/SquashProgressTrackerApplication.java` | moved silently |
| `src/main/resources/application.properties` | moved silently |
| `src/test/java/com/example/squash_progress_tracker/SquashProgressTrackerApplicationTests.java` | moved silently |

Existing cwd files preserved (no conflict with scaffold — scaffold did not ship these): `CLAUDE.md`, `README.md`, `init-notes.md`, `squash-progress-tracker.iml`, `context/`.

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for java
**Recommended external tool**: OWASP Dependency-Check (`mvn org.owasp:dependency-check-maven:check`) or Snyk (`snyk test`) are the common choices for Maven projects. Either can be added as a Maven plugin for ongoing dependency scanning.

## Hints recorded but not acted on

| Hint | Value |
| ---- | ----- |
| bootstrapper_confidence | verified |
| quality_override | false |
| path_taken | standard |
| self_check_answers | null |
| team_size | solo |
| deployment_target | fly |
| ci_provider | github-actions |
| ci_default_flow | auto-deploy-on-merge |
| has_auth | true |
| has_payments | false |
| has_realtime | false |
| has_ai | true |
| has_background_jobs | false |

Notable for future action: `has_auth: true` and `has_ai: true` were not acted on by v1 bootstrapper. The hand-off notes Spring Security for auth and Spring AI / Anthropic SDK for AI — both require manual dependency additions to `pom.xml` after scaffolding. The future M1L4 skill (Memory Architecture) is the intended destination for compensating actions on these flags.

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- Review `HELP.md` — the Spring Boot starter ships useful references there.
- Add `spring-boot-starter-security` to `pom.xml` for auth (FR-001, FR-002).
- Add Spring AI or the Anthropic Java SDK to `pom.xml` for the AI feature (FR-003, FR-010).
- Configure Fly.io deployment (`fly.toml`, Dockerfile) when ready to deploy.
- Run `mvn verify` to confirm the scaffold compiles and the generated test passes.
