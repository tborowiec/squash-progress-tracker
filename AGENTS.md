# Repository Guidelines

Squash Progress Tracker is a Java 21 / Spring Boot 4.0.6 web app where squash players log match results via natural language (AI parses to a structured preview), confirm the data, and request AI-generated game plans for upcoming matches. Spring Security handles auth; AI integration (Spring AI or Anthropic Java SDK) must be added manually after scaffold.

## Hard Rules

- Never write to `context/archive/` — archived changes are immutable; use `/10x-new` to open a new change.
- Always show the AI parse result to the player for confirmation before saving any match record. No silent mis-save.
- Enforce the auth boundary on every data-access query — one player's match history must never be readable by another.
- Label every AI-generated game plan as AI-generated advice, not factual analysis.

## Project Structure

Standard Maven layout: source in `src/main/java/com/example/squash_progress_tracker/`, tests mirror that path under `src/test/java/`. Spring config lives in `src/main/resources/application.properties`. The `context/` tree holds 10x workflow files (`foundation/`, `changes/`, `archive/`) — do not edit `context/archive/`.

See `@context/foundation/prd.md` for user stories and acceptance criteria; `@context/foundation/tech-stack.md` for stack rationale.

## Build, Test, and Development Commands

- Run dev server: `./mvnw spring-boot:run`
- Run all tests: `./mvnw test`
- Run one test class: `./mvnw test -Dtest=ClassName`
- Package: `./mvnw package`

Use `./mvnw` (wrapper) rather than a locally installed `mvn` so the Maven version stays pinned.

## Coding Style & Naming Conventions

Java 21, standard Spring Boot conventions. Class names are `PascalCase`; method and field names are `camelCase`. Package root is `com.example.squash_progress_tracker`. No linter or formatter is configured yet.

## Testing Guidelines

JUnit 5 via `spring-boot-starter-test`. Test classes live in `src/test/java/` mirroring the source package and are named `<ClassName>Tests.java`. Run with `./mvnw test`.

## Commit Guidelines

Lowercase descriptive phrases (e.g. `add match logging endpoint`, `wire spring security auth`). No conventional-commit prefixes in use. CI (GitHub Actions, auto-deploy to Fly.io on merge to `main`) is planned but not yet wired.
