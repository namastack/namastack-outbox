#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/namastack-release-test.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT

fail() {
    echo "$1" >&2
    exit 1
}

assert_equals() {
    local expected="$1"
    local actual="$2"
    local description="$3"
    [[ "$actual" == "$expected" ]] || fail "$description: expected $expected, got $actual"
}

assert_contains() {
    local expected="$1"
    local file="$2"
    grep --fixed-strings --quiet "$expected" "$file" || fail "$file does not contain: $expected"
}

gradle_version() {
    "$repository_root/gradlew" \
        -p "$repository_root" \
        properties \
        --property version \
        --no-daemon \
        --console=plain \
        -q \
        "$@" | sed -n 's/^version: //p'
}

base_version="$("$repository_root/scripts/current-version.sh")"
IFS=. read -r major minor patch <<< "$base_version"
next_minor=$((10#$minor + 1))

while grep --fixed-strings --quiet "\"$major.$next_minor.x\"" "$repository_root/namastack-outbox-docs/versions.json"; do
    next_minor=$((next_minor + 1))
done

minor_version="$major.$next_minor.0"
patch_version="$major.$next_minor.1"
release_line="$major.$next_minor.x"
previous_release_line="$(
    node -e \
        'const fs = require("node:fs"); console.log(JSON.parse(fs.readFileSync(process.argv[1], "utf8"))[0]);' \
        "$repository_root/namastack-outbox-docs/versions.json"
)"

assert_equals "$base_version-SNAPSHOT" "$(gradle_version)" "snapshot version"
assert_equals "$base_version" "$(gradle_version -Prelease=true)" "stable release version"
assert_equals "$base_version-RC1" \
    "$(gradle_version -Prelease=true -PreleaseSuffix=-RC1)" \
    "prerelease version"

if "$repository_root/gradlew" \
    -p "$repository_root" \
    properties \
    --property version \
    -PreleaseSuffix=-RC1 \
    --no-daemon \
    --console=plain \
    -q >"$test_root/invalid-suffix.log" 2>&1; then
    fail "Gradle accepted a prerelease suffix without -Prelease=true"
fi

rsync -a \
    --exclude='.git' \
    --exclude='.gradle' \
    --exclude='build' \
    --exclude='node_modules' \
    "$repository_root/" "$test_root/repository/"
ln -s "$repository_root/namastack-outbox-docs/node_modules" \
    "$test_root/repository/namastack-outbox-docs/node_modules"

node "$test_root/repository/scripts/prepare-release.mjs" "$minor_version"

assert_equals "$minor_version" \
    "$("$test_root/repository/scripts/current-version.sh")" \
    "prepared minor version"
assert_contains \
    "namastackOutbox = \"$minor_version-SNAPSHOT\"" \
    "$test_root/repository/namastack-outbox-examples/gradle/libs.versions.toml"
assert_contains \
    "version = \"$minor_version-SNAPSHOT\"" \
    "$test_root/repository/namastack-outbox-performance-test/build.gradle.kts"
assert_contains \
    "{\"source\": \"/outbox/$release_line/:path*\", \"destination\": \"/docs/:path*\"" \
    "$test_root/repository/namastack-outbox-docs/vercel.json"
assert_contains \
    "{\"source\": \"/outbox/$previous_release_line/:path*\", \"destination\": \"/docs/$previous_release_line/:path*\"" \
    "$test_root/repository/namastack-outbox-docs/vercel.json"
assert_contains \
    "| $release_line" \
    "$test_root/repository/SECURITY.md"

node -e \
    'const fs = require("node:fs"); const versions = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); if (versions[0] !== process.argv[2]) process.exit(1);' \
    "$test_root/repository/namastack-outbox-docs/versions.json" \
    "$release_line"

cp "$test_root/repository/namastack-outbox-docs/versions.json" "$test_root/versions-after-minor.json"
cp "$test_root/repository/namastack-outbox-docs/vercel.json" "$test_root/vercel-after-minor.json"
cp "$test_root/repository/SECURITY.md" "$test_root/security-after-minor.md"

node "$test_root/repository/scripts/prepare-release.mjs" "$patch_version"

assert_equals "$patch_version" \
    "$("$test_root/repository/scripts/current-version.sh")" \
    "prepared patch version"
cmp "$test_root/versions-after-minor.json" \
    "$test_root/repository/namastack-outbox-docs/versions.json"
cmp "$test_root/vercel-after-minor.json" \
    "$test_root/repository/namastack-outbox-docs/vercel.json"
cmp "$test_root/security-after-minor.md" \
    "$test_root/repository/SECURITY.md"

(
    cd "$test_root/repository/namastack-outbox-docs"
    npm run typecheck
    npm run build
)

"$repository_root/gradlew" \
    -p "$repository_root" \
    :namastack-outbox-bom:generatePomFileForMavenPublication \
    -Prelease=true \
    -PreleaseSuffix=-RC1 \
    --no-daemon \
    --console=plain

assert_contains \
    "<version>$base_version-RC1</version>" \
    "$repository_root/namastack-outbox-bom/build/publications/maven/pom-default.xml"

echo "Release automation dry run passed"
