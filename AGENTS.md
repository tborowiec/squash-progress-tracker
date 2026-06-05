# Repository Guidelines

Squash Progress Tracker is a Java 21 / Spring Boot 4.0.6 web app where squash players log match results via natural language (AI parses to a structured preview), confirm the data, and request AI-generated game plans for upcoming matches. Spring Security handles auth; AI integration (Spring AI or Anthropic Java SDK) must be added manually after scaffold.

## Hard Rules

- Never write to `context/archive/` — archived changes are immutable; use `/10x-new` to open a new change.
- Track project implementation progress both in the repository and in GitHub Issues
- Use the `frontend-design` skill when building frontend
- Always show the AI parse result to the player for confirmation before saving any match record. No silent mis-save.
- Enforce the auth boundary on every data-access query — one player's match history must never be readable by another.
- Label every AI-generated game plan as AI-generated advice, not factual analysis.

## Project Structure

Standard Maven layout: source in `src/main/java/org/borowiec/squashprogresstracker/`, tests mirror that path under `src/test/java/`. Spring config lives in `src/main/resources/application.properties`. The `context/` tree holds 10x workflow files (`foundation/`, `changes/`, `archive/`) — do not edit `context/archive/`.

See `@context/foundation/prd.md` for user stories and acceptance criteria; `@context/foundation/tech-stack.md` for stack rationale.

## Build, Test, and Development Commands

- Run dev server: `./mvnw spring-boot:run`
- Run all tests: `./mvnw test`
- Run one test class: `./mvnw test -Dtest=ClassName`
- Package: `./mvnw package`

Use `./mvnw` (wrapper) rather than a locally installed `mvn` so the Maven version stays pinned.

## Coding Style & Naming Conventions

Java 21. Package root is `org.borowiec.squashprogresstracker`. No linter or formatter is configured yet.

Use `var` for all local variable declarations where the type is inferred from the right-hand side.

All REST endpoints should be available under paths prefixed with `/api`.

Database table names are plural `snake_case` (e.g. `users`, `matches`). This also sidesteps reserved-word collisions — `user` is reserved in PostgreSQL, `users` is not. Map entities to the table explicitly with `@Table(name = "...")`.

## Testing Guidelines

Test classes are named `<ClassName>Tests.java`. Run with `./mvnw test`.

## GitHub Issues & Project Board

Issues are tracked on the **Squash MVP** project board (Projects v2, owner `tborowiec`). `gh issue create` does **not** add an issue to the board on its own — always pass `--project "Squash MVP"` so it lands there automatically:

```
gh issue create --title "..." --body "..." --project "Squash MVP" --label "..."
```

New board items have an empty **Status**; set it to `Todo` (the board's other columns are `In Progress` and `Done`). The Status field is a project field, not a label, so it's set via the project API:

```
gh project item-edit --id <item-id> --project-id <project-id> \
  --field-id <status-field-id> --single-select-option-id <Todo-option-id>
```

Resolve those ids with `gh project item-add 1 --owner tborowiec --url <issue-url> --format json` (returns the item id) and `gh project field-list 1 --owner tborowiec --format json` (returns the Status field id and its option ids).

## Commit Guidelines

Lowercase descriptive phrases (e.g. `add match logging endpoint`, `wire spring security auth`). No conventional-commit prefixes in use. CI (GitHub Actions, auto-deploy to Render on merge to `main`) is planned but not yet wired.
