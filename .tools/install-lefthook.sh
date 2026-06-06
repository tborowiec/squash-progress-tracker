#!/usr/bin/env bash
# Download the lefthook binary (gitignored, ~15MB) and install the git hooks
# defined in lefthook.yml. Run once per clone. Pinned to match the version the
# repo was set up with.

set -euo pipefail

VERSION="2.1.9"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$DIR/lefthook"
ROOT="$(cd "$DIR/.." && pwd)"
BASE="https://github.com/evilmartians/lefthook/releases/download/v${VERSION}"

os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
  Linux/x86_64)  asset="lefthook_${VERSION}_Linux_x86_64" ;;
  Linux/aarch64) asset="lefthook_${VERSION}_Linux_arm64" ;;
  Darwin/arm64)  asset="lefthook_${VERSION}_MacOS_arm64" ;;
  Darwin/x86_64) asset="lefthook_${VERSION}_MacOS_x86_64" ;;
  *) echo "No prebuilt lefthook asset for $os/$arch — see https://github.com/evilmartians/lefthook/releases" >&2; exit 1 ;;
esac

echo "Downloading $asset ..."
curl -sSL "$BASE/$asset" -o "$DEST"

echo "Verifying checksum ..."
expected="$(curl -sSL "$BASE/lefthook_checksums.txt" | grep " $asset\$" | cut -d' ' -f1)"
actual="$(sha256sum "$DEST" | cut -d' ' -f1)"
if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
  echo "Checksum mismatch/missing! expected=$expected actual=$actual" >&2
  rm -f "$DEST"
  exit 1
fi

chmod +x "$DEST"
echo "Installing git hooks from lefthook.yml ..."
( cd "$ROOT" && "$DEST" install )
echo "Done. lefthook $VERSION installed; pre-commit will run spotless:check on staged Java files."
