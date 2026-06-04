# Lessons Learned

> Append-only register of recurring rules and patterns. Re-read at start by /10x-frame, /10x-research, /10x-plan, /10x-plan-review, /10x-implement, /10x-impl-review.

## On Linux, host.docker.internal does not resolve inside containers

- **Context**: Docker local verification step — any phase that runs containers locally to smoke-test the app before deploying
- **Problem**: On Linux (unlike macOS/Windows), `host.docker.internal` is not automatically injected into container DNS. The app container throws `java.net.UnknownHostException: host.docker.internal`, Hikari fails to connect, and `/actuator/health` reports `db: DOWN` with no obvious reason in the surface output.
- **Rule**: On Linux, never rely on `host.docker.internal` to reach a sibling container. Put both containers on a shared Docker network (`docker network create`) and reference the DB container by its `--name`. Alternatively, pass `--add-host=host.docker.internal:host-gateway` explicitly to each container that needs host resolution.
- **Applies to**: plan, implement

## Add node_modules/ to .gitignore before npm install

- **Context**: Any /10x-implement phase that scaffolds a new frontend project (Vite, CRA, Next.js, or any npm-based setup) and runs npm install or npm ci for the first time.
- **Problem**: If node_modules/ is not in .gitignore before npm install runs, all dependency files get staged and committed — thousands of files that must be cleaned up with a follow-up git rm -r --cached commit, polluting the git history.
- **Rule**: Before running npm install or npm ci in a new frontend project, always add node_modules/ and the build output dir (dist/, build/) to .gitignore. Run git status --porcelain after install and before any git add to catch unintended files.
- **Applies to**: implement

## Keep Squash MVP project board in sync during implementation

- **Context**: Any `/10x-implement` run on this project — on phase start and on change completion.
- **Problem**: The project board drifts out of sync with reality: issues stay "Todo" while work is in flight, or stay "In Progress" after merging, misleading anyone checking the board.
- **Rule**: When `/10x-implement` begins on a change, move the corresponding GitHub issue to "In Progress" on the Squash MVP project board (`gh project item-edit`). When all phases are complete and `change.md` is set to `implemented`, move it to "Done".
- **Applies to**: implement

## Never use fully qualified class names when an import suffices

- **Context**: Any test or production class where a type is referenced inline (e.g. `ArgumentCaptor.forClass(org.example.Foo.class)`, `m.role() == org.example.LlmRole.USER`, or a method parameter typed as `org.example.LlmRequest`).
- **Problem**: Fully qualified names were used in `MatchParseServiceTests` and `MatchParsePromptBuilderTests`, flagged in PR review. They make code harder to read and are inconsistent with the rest of the codebase which uses imports.
- **Rule**: Always add an `import` statement for any type referenced in code. Use the short name everywhere. Reserve fully qualified names only for genuine ambiguity (two types with the same simple name in scope).
- **Applies to**: implement, plan

## Verify the Docker build context covers all build inputs, not just src/

- **Context**: Any plan/implement phase that adds a new build input outside the standard source tree (e.g. a `frontend/` Vite project built by frontend-maven-plugin) while a multi-stage Dockerfile drives the build. Dockerfile:8.
- **Problem**: The plan asserted three times that no Dockerfile change was needed ("build stage already runs ./mvnw clean package"). The premise was wrong: the Dockerfile only did `COPY src/ src/`, so the frontend-maven-plugin had no `frontend/` to build inside the image and `mvnw package` failed in Docker. Required a follow-up fix (commit 85322e9) adding `COPY frontend/ frontend/` plus a `.dockerignore`.
- **Rule**: When adding a build input outside `src/`, audit the Dockerfile's `COPY` lines before claiming "no Dockerfile change needed" — the build *context*, not just the build *command*, must include every input the build consumes.
- **Applies to**: plan, implement
