#!/usr/bin/env bash
# Download the palantir-java-format native binary used by the per-edit format hook
# (.claude/hooks/format-java.sh) and the developer who wants to format manually.
#
# The binary is ~88MB and platform-specific, so it is gitignored rather than
# committed. Run this once after cloning. The version is pinned to match the
# Spotless palantirJavaFormat version in pom.xml so per-edit and commit-time
# formatting produce identical output.

set -euo pipefail

VERSION="2.68.0"
DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/palantir-java-format"
BASE="https://repo1.maven.org/maven2/com/palantir/javaformat/palantir-java-format-native/${VERSION}"

os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
  Linux/x86_64)  classifier="nativeImage-linux-glibc_x86-64" ;;
  Linux/aarch64) classifier="nativeImage-linux-glibc_aarch64" ;;
  Darwin/arm64)  classifier="nativeImage-macos_aarch64" ;;
  *) echo "No prebuilt palantir-java-format native binary for $os/$arch." >&2
     echo "Use Spotless instead: ./mvnw spotless:apply" >&2
     exit 1 ;;
esac

file="palantir-java-format-native-${VERSION}-${classifier}.bin"
echo "Downloading $file ..."
curl -sSL "$BASE/$file" -o "$DEST"

echo "Verifying checksum ..."
expected="$(curl -sSL "$BASE/$file.sha256")"
actual="$(sha256sum "$DEST" | cut -d' ' -f1)"
if [ "$expected" != "$actual" ]; then
  echo "Checksum mismatch! expected=$expected actual=$actual" >&2
  rm -f "$DEST"
  exit 1
fi

chmod +x "$DEST"
echo "Installed palantir-java-format $VERSION -> $DEST"
