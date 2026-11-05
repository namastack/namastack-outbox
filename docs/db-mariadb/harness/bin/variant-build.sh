#!/usr/bin/env bash
# Build the module jars of a worktree (only its build/ dirs change) and record their paths.
# usage: bin/variant-build.sh <variant> <worktree>
set -euo pipefail
H="$(cd "$(dirname "$0")/.." && pwd)"
variant="$1"; wt="$2"
mkdir -p "$H/build/variants"
(cd "$wt" && ./gradlew --console=plain -q --init-script "$H/gradle/jdk25.init.gradle.kts" :namastack-outbox-api:jar :namastack-outbox-core:jar \
  :namastack-outbox-jdbc:jar :namastack-outbox-jpa:jar)
for m in api core jdbc jpa; do
  ls -t "$wt"/namastack-outbox-$m/build/libs/namastack-outbox-$m-*.jar | grep -v -- '-sources\|-javadoc\|-plain' | head -1
done | paste -sd: - > "$H/build/variants/$variant.jars"
echo "$variant: $(cat "$H/build/variants/$variant.jars")"
