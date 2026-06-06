#!/usr/bin/env bash
# PostToolUse hook: format + lint a single frontend file with Biome.
#
# Runs `biome check --write` on the .ts/.tsx/.js/.jsx/.css/.json file the agent
# just wrote/edited: applies formatting and *safe* lint autofixes in place, then
# surfaces any remaining lint diagnostics (the ones Biome won't fix automatically)
# back into the agent's context so it can correct them on the next turn. Biome is a
# single Rust binary — ~15ms on this repo — so it's fast enough for a per-edit hook
# (see CLAUDE.md "Keep per-edit hooks fast").
#
# Reads the standard Claude Code PostToolUse JSON envelope on stdin and pulls
# .tool_input.file_path from it. Non-blocking by design: any problem (missing
# binary, parse error mid-edit) exits 0 so the agent loop is never interrupted —
# the commit-time lefthook gate (biome check + tsc) is the real enforcement layer.
#
# Per-edit applies SAFE fixes only (never --unsafe): unsafe fixes can change
# behaviour, so those stay a human/agent decision surfaced via the feedback below.

set -euo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
BIOME="$ROOT/frontend/node_modules/.bin/biome"

FILE="$(jq -r '.tool_input.file_path // empty')"

# Only act on frontend files Biome handles. Scoped to the frontend/ tree so root
# config files aren't touched; biome.json itself further narrows to src/** (a file
# outside that is a no-op for Biome, but skipping here avoids spurious feedback).
case "$FILE" in
  */frontend/*) ;;
  *) exit 0 ;;
esac
case "$FILE" in
  *.ts|*.tsx|*.js|*.jsx|*.mjs|*.cjs|*.css|*.json|*.jsonc) ;;
  *) exit 0 ;;
esac

[ -f "$FILE" ] || exit 0

if [ ! -x "$BIOME" ]; then
  echo "format-frontend hook: biome not found at $BIOME — run 'npm ci' in frontend/" >&2
  exit 0
fi

before="$(sha256sum "$FILE" | cut -d' ' -f1)"

# Apply formatting + safe lint fixes in place. Biome discovers frontend/biome.json
# by walking up from the file. Don't fail the hook if it errors (syntax error
# mid-edit): the gate will catch it later.
"$BIOME" check --write "$FILE" >/tmp/format-frontend.out 2>&1 || true

after="$(sha256sum "$FILE" | cut -d' ' -f1)"

# Collect lint diagnostics that remain AFTER safe fixes — these need a manual fix
# (e.g. an a11y or correctness rule with no safe autofix). Capped so the feedback
# stays well under Claude Code's 10k-char additionalContext limit.
remaining="$("$BIOME" check "$FILE" 2>&1 || true)"
diag_summary=""
if printf '%s' "$remaining" | grep -qE "Found [0-9]+ error"; then
  # Keep only the rule-header lines (file:line lint/...) and the "×" message lines;
  # drop Biome's source-context and box-drawing decoration. sed trims the trailing
  # "━━━" rule so lines stay short — avoids cutting a multibyte char mid-byte.
  diag_summary="$(printf '%s' "$remaining" \
    | grep -E "(lint/[a-z]|^[[:space:]]*×)" \
    | sed -E 's/[[:space:]]*━+.*$//; s/[[:space:]]+$//' \
    | head -25)"
fi

# Build feedback only when something is worth telling the agent: the file changed
# (its in-memory view is stale) and/or unfixable lint errors remain.
msg=""
if [ "$before" != "$after" ]; then
  msg="Auto-formatted $FILE with Biome (formatting + safe lint fixes applied). The on-disk content changed; re-read this file before editing it again."
fi
if [ -n "$diag_summary" ]; then
  msg="${msg:+$msg

}Biome reports lint errors in $FILE that need a manual fix (they block the pre-commit gate):
$diag_summary"
fi

if [ -n "$msg" ]; then
  jq -n --arg m "$msg" '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $m
    }
  }'
fi

exit 0
