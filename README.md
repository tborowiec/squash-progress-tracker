# squash-progress-tracker
Project developed during 10xDevs course

## Code quality hooks

Both the Java backend and the React/TypeScript frontend are formatted and linted automatically
at two layers: a per-edit Claude Code hook (auto-fixes the file the agent just touched) and a
lefthook pre-commit gate (blocks bad commits, also catching manual edits). After cloning, run the
install scripts once to fetch the gitignored binaries and install the git hooks:

```bash
.tools/install-formatter.sh    # Java per-edit formatter (palantir-java-format native binary)
.tools/install-lefthook.sh     # pre-commit git hooks (lefthook binary + .git/hooks/ install)
cd frontend && npm ci          # frontend toolchain incl. Biome (per-edit + commit gate)
```

### Java

Formatted with [palantir-java-format](https://github.com/palantir/palantir-java-format) (Palantir
4-space style; sorts imports, removes unused). Spotless in `pom.xml` is the source of truth —
`./mvnw spotless:apply` to format, `./mvnw spotless:check` to verify.

- **Per-edit** (`.claude/hooks/format-java.sh`) — auto-formats a `.java` file on Write/Edit (~20ms
  via the native binary).
- **Pre-commit** (`lefthook.yml` → `spotless-check`) — blocks a commit whose staged Java files
  aren't formatted; fix with `./mvnw spotless:apply`, then re-stage.

### Frontend

Formatted and linted with [Biome](https://biomejs.dev) — one Rust binary, config in
`frontend/biome.json`. From `frontend/`: `npm run format` (format + safe lint fixes),
`npm run lint` (check only), `npm run typecheck` (`tsc --noEmit`).

- **Per-edit** (`.claude/hooks/format-frontend.sh`) — on Write/Edit of a `.ts/.tsx/.css/...` file,
  applies formatting + safe lint fixes (~15ms) and surfaces any remaining lint errors to the agent.
- **Pre-commit** (`lefthook.yml` → `biome-check`, `frontend-typecheck`) — blocks a commit on
  formatting violations, lint errors, or type errors on staged frontend files; fix with
  `npm run format` (and hand-fix unfixable lint), then re-stage. Advisory lint warnings don't block.

The per-edit hooks and commit gates share the same config (Spotless / `biome.json`), so the two
layers never disagree. The lefthook installer writes into `.git/hooks/`, so re-run it on every
fresh clone.
