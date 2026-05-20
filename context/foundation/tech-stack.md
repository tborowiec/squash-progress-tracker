---
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
---

## Why this stack

Spring Boot is the vetted recommended default for a Java web-app and clears all four agent-friendly quality gates — typed (Java generics and Spring's strong typing), convention-based (DI, autoconfiguration, and prescribed file layout), popular in Java training data, and well-documented with version-pinned reference docs. The 3-week after-hours timeline favors a verified bootstrapper confidence, meaning scaffolding runs end-to-end without manual steps. Spring Security covers auth (FR-001, FR-002) first-class. The one gap is AI/LLM: FR-003 and FR-010 require AI text parsing and game-plan generation; no Java starter in the registry ships with AI bundled, so Spring AI or a direct Anthropic/OpenAI Java SDK dependency will need to be added manually after scaffolding. Deployment targets Fly.io (the card's first default; subject to change after scaffolding); CI runs on GitHub Actions with auto-deploy on merge to main.
