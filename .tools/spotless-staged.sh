#!/usr/bin/env bash
# Run Spotless' check goal against only the given Java files (the staged ones,
# passed by lefthook as space-separated, repo-relative paths).
#
# Spotless' -DspotlessFiles takes a comma-separated list of regexes and matches
# each FULLY against the ABSOLUTE file path. So a bare repo-relative path never
# matches. For every path we therefore (1) escape regex metacharacters and
# (2) prepend ".*" so it matches regardless of the absolute prefix, then join the
# results with commas.
#
# Invoked by lefthook.yml (pre-commit). Exits 0 with no args so an all-non-Java
# commit is a no-op.

set -euo pipefail

[ "$#" -eq 0 ] && exit 0

regexes=""
for f in "$@"; do
  escaped="$(printf '%s' "$f" | sed 's/[.[\*^$()+?{|]/\\&/g')"
  regexes="${regexes:+$regexes,}.*$escaped"
done

exec ./mvnw -q spotless:check "-DspotlessFiles=$regexes"
