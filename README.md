# squash-progress-tracker
Project developed during 10xDevs course

## Code formatting hooks

Java code is formatted with [palantir-java-format](https://github.com/palantir/palantir-java-format)
(Palantir 4-space style; sorts imports and removes unused ones). Spotless in `pom.xml` is
the source of truth — run `./mvnw spotless:apply` to format, `./mvnw spotless:check` to verify.

Two hooks run it automatically. The required binaries are gitignored, so after cloning run the
install scripts once:

```bash
.tools/install-formatter.sh    # per-edit hook: formats each .java file on save (Claude Code)
.tools/install-lefthook.sh     # pre-commit hook: runs spotless:check on staged .java files
```

- **Per-edit** (`.claude/hooks/format-java.sh`) — auto-formats a `.java` file the moment the
  agent writes/edits it (~20ms via the native binary).
- **Pre-commit** (`lefthook.yml`) — blocks a commit whose staged Java files aren't formatted;
  fix with `./mvnw spotless:apply`, then re-stage. The script installs the git hook into
  `.git/hooks/`, so this step must be re-run on every fresh clone.
