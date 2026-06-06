#!/usr/bin/env bash
# PostToolUse hook: format a single Java file with palantir-java-format.
#
# Reformats (Palantir 4-space style), sorts imports, and removes unused imports
# on the file the agent just wrote/edited. Uses the native binary so it runs in
# ~20ms — fast enough for a per-edit hook (see CLAUDE.md "Keep per-edit hooks fast").
#
# Reads the standard Claude Code PostToolUse JSON envelope on stdin and pulls
# .tool_input.file_path from it. Non-blocking by design: any problem (missing
# binary, syntax error mid-edit) exits 0 so the agent loop is never interrupted —
# the commit-time Spotless gate is the real enforcement layer.

set -euo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
FORMATTER="$ROOT/.tools/palantir-java-format"

FILE="$(jq -r '.tool_input.file_path // empty')"

# Only act on Java source files.
case "$FILE" in
  *.java) ;;
  *) exit 0 ;;
esac

[ -f "$FILE" ] || exit 0

if [ ! -x "$FORMATTER" ]; then
  echo "format-java hook: formatter not found at $FORMATTER — run .tools/install-formatter.sh" >&2
  exit 0
fi

before="$(sha256sum "$FILE" | cut -d' ' -f1)"

# --palantir = 4-space Palantir style; --replace edits in place.
# Default behaviour already sorts imports and removes unused ones.
# --skip-reflowing-long-strings: the native CLI reflows over-long string literals
# into "+"-concatenated pieces, but the Spotless palantirJavaFormat step (the
# commit-gate source of truth) does not. Skipping it keeps the two in parity, e.g.
# on long @Query strings.
if ! "$FORMATTER" --palantir --skip-reflowing-long-strings --replace "$FILE" 2>/tmp/format-java.err; then
  echo "format-java hook: could not format $FILE (likely a syntax error mid-edit); skipping." >&2
  cat /tmp/format-java.err >&2 || true
  exit 0
fi

# When the formatter removes the file's last import, its CLI leaves a double
# blank line after the package statement; the commit-gate Spotless step collapses
# it to one. Normalize blank lines to exactly one immediately after `package ...;`
# so per-edit output stays byte-identical to `./mvnw spotless:check`.
#
# Scoped to the header on purpose: a whole-file squeeze (e.g. `cat -s`) would also
# collapse blank lines that are real content inside multi-line text blocks. awk
# only touches the run of blanks between the package line and the next non-blank
# line; everything else (imports, the entire class body and its strings) is copied
# verbatim.
tmp="$(mktemp)"
awk '
  body { print; next }                          # past the header: copy verbatim
  /^package / { print; inpkg=1; next }          # the package statement
  inpkg && /^[[:space:]]*$/ { next }            # drop blank lines right after it
  inpkg { print ""; print; body=1; inpkg=0; next }  # first non-blank: one blank + line
  { print }                                     # no package line yet: copy verbatim
' "$FILE" > "$tmp"
cat "$tmp" > "$FILE"
rm -f "$tmp"

after="$(sha256sum "$FILE" | cut -d' ' -f1)"

# Only tell the agent when the file actually changed, so it knows its in-memory
# view is stale and re-reads before the next edit.
if [ "$before" != "$after" ]; then
  jq -n --arg f "$FILE" '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: ("Auto-formatted " + $f + " with palantir-java-format (reformatted, imports sorted, unused removed). The on-disk content changed; re-read this file before editing it again.")
    }
  }'
fi

exit 0
