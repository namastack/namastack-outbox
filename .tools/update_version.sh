#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <NEW_VERSION>"
  exit 1
fi

NEW_VERSION="$1"
SNAPSHOT_VERSION="${NEW_VERSION}-SNAPSHOT"
DOCS_VERSION="${NEW_VERSION%.*}.x"

# Determine script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# 1. Update root build.gradle.kts
sed -i '' -E "s/(version = \")([0-9]+\.[0-9]+\.[0-9]+)(\" *\+ if \(!isRelease\) \"-SNAPSHOT\" else \"\")/\1${NEW_VERSION}\3/" build.gradle.kts

# 2. Update all io.namastack dependencies in examples to SNAPSHOT
find namastack-outbox-examples/ -type f -name "*.gradle.kts" | while read -r file; do
  # Only update io.namastack:namastack-outbox-* dependencies
  sed -i '' -E "s#(io\.namastack:namastack-outbox-[a-zA-Z0-9-]+:)[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?#\1${SNAPSHOT_VERSION}#g" "$file"
done

# 3. Update README.md for release references (not SNAPSHOT)
# Badge and release tag
sed -i '' -E "s/(version-)[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?(-blue)?(\\)]\\(https:\\/\\/github\\.com\\/namastack\\/namastack-outbox\\/releases\\/tag\\/v)[0-9]+\\.[0-9]+\\.[0-9]+/\\1${NEW_VERSION}\\3\\4${NEW_VERSION}/" README.md
# Gradle snippets and inline references
sed -i '' -E "s#(io\\.namastack:namastack-outbox-[a-zA-Z0-9-]+:)[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?#\\1${NEW_VERSION}#g" README.md

# Update Maven <version> in README.md for io.namastack artifacts
sed -i '' '/<dependency>/,/<\/dependency>/{
  /<groupId>io\.namastack<\/groupId>/,/<\/dependency>/ s|<version>[^<]*</version>|<version>1.7.0</version>|
}' README.md

# 4. Update Docusaurus docs version (use .x for bugfix)
cd namastack-outbox-docs
pnpm run docusaurus docs:version "$DOCS_VERSION"
cd ..

echo "Summary:"
echo "- Files changed: build.gradle.kts, README.md, all example build.gradle.kts files under namastack-outbox-examples/"
echo "- Release version used: ${NEW_VERSION}"
echo "- Snapshot version used in examples: ${SNAPSHOT_VERSION}"
echo "- Docs version used: ${DOCS_VERSION}"
