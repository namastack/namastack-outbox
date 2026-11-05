#!/usr/bin/env bash
# Regenerate catalog/<variant>/<dialect>/<id>.sql from a worktree's real code (all dialects).
# usage: bin/extract-catalog.sh <variant> <worktree>
set -euo pipefail
H="$(cd "$(dirname "$0")/.." && pwd)"
"$H/bin/variant-build.sh" "$1" "$2"
"$H/bin/repo-bench" "$1" extract-catalog
