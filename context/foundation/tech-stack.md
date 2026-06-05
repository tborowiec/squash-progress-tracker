---
starter_id: spring
package_manager: maven
project_name: squash-progress-tracker
hints:
  language_family: java
  team_size: solo
  deployment_target: render
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
---

## Why this stack

Spring Boot is the vetted recommended default for a Java web-app and clears all four agent-friendly quality gates — typed (Java generics and Spring's strong typing), convention-based (DI, autoconfiguration, and prescribed file layout), popular in Java training data, and well-documented with version-pinned reference docs. The 3-week after-hours timeline favors a verified bootstrapper confidence, meaning scaffolding runs end-to-end without manual steps. Spring Security covers auth (FR-001, FR-002) first-class. The one gap is AI/LLM: FR-003 and FR-010 require AI text parsing and game-plan generation; no Java starter in the registry ships with AI bundled, so an LLM integration was added manually after scaffolding. The **provider was chosen during F-02 (`llm-client`, research 2026-06-03 / issue #2): Google Gemini** (`gemini-2.5-flash`) accessed through its **OpenAI-compatible endpoint** behind a thin `LlmClient` adapter — the OpenAI wire format keeps the provider swappable (Groq/Mistral/OpenRouter via a base-URL override). Artifacts stay provider-agnostic via the placeholder `LLM_API_KEY` / `LLM_BASE_URL` env vars (see `application.properties`). Privacy note: Gemini's free tier trains on prompts with no opt-out — use it for synthetic-data dev only and route real user match data to a paid/no-training tier. Deployment targets Render — chosen in `context/foundation/infrastructure.md` (2026-05-30) over the original Fly.io default after a cost/familiarity/single-region analysis (phased free → paid for both the web service and Postgres); CI runs on GitHub Actions with auto-deploy on merge to main.
