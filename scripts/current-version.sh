#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$({
    sed -nE \
        's/^[[:space:]]*version = "([0-9]+\.[0-9]+\.[0-9]+)" \+ if \(!isRelease\) "-SNAPSHOT" else ""$/\1/p' \
        "$repository_root/build.gradle.kts"
} | head -n 2)"

if [[ -z "$version" || "$version" == *$'\n'* ]]; then
    echo "Could not determine exactly one release version from build.gradle.kts" >&2
    exit 1
fi

printf '%s\n' "$version"
