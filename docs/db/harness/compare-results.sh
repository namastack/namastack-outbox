#!/usr/bin/env bash
# Semantic-equivalence check: runs two query files (same format as harness/queries/*.sql, with the
# `:explain` prefix left empty) and compares their ordered output exactly.
#
# Usage: compare-results.sh <container> <original.sql> <rewritten.sql> [--var k=v]... [--generic]
#   e.g. compare-results.sh outbox-db-H07 queries/findRecordKeysInPartitions_filter.sql \
#          ../experiments/H07/findRecordKeysInPartitions_filter.sql --var parts=256
#
# Prints row counts, md5 over the ordered rows (psql unaligned output) and
#   PASS                              identical rows in identical order
#   FAIL (same rows, different order) multiset equal, order differs
#   FAIL                              different rows
# Exit code 0 on PASS, 1 otherwise. Files are run with explain='' (writes still roll back).
set -euo pipefail
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${1:?usage: compare-results.sh <container> <original.sql> <rewritten.sql> [--var k=v]... [--generic]}"
ORIG="$(realpath "$2")"
REWR="$(realpath "$3")"
shift 3
VARS=()
GENERIC=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --var) VARS+=("$2"); shift 2 ;;
    --generic) GENERIC=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

TMP="$(mktemp -d)"
REMOTE="/harness/cmp-$$"
trap 'rm -rf "$TMP"; docker exec "$CONTAINER" rm -rf "$REMOTE" >/dev/null 2>&1 || true' EXIT

run_one() { # <file> <tag>
  local file="$1" tag="$2" params
  params="$(dirname "$file")/_params.sql"
  [[ -f "$params" ]] || params="$HARNESS_DIR/queries/_params.sql"
  docker exec "$CONTAINER" mkdir -p "$REMOTE/$tag"
  docker cp -q "$file" "$CONTAINER:$REMOTE/$tag/q.sql"
  docker cp -q "$params" "$CONTAINER:$REMOTE/$tag/_params.sql"
  {
    echo "\\set ON_ERROR_STOP on"
    for kv in "${VARS[@]}"; do echo "\\set ${kv%%=*} ${kv#*=}"; done
    echo "\\set generic $GENERIC"
    echo "\\set explain ''"
    echo "\\i $REMOTE/$tag/q.sql"
  } | docker exec -i "$CONTAINER" psql -U postgres -d postgres -X -q -A -t -P pager=off > "$TMP/$tag.out"
}

run_one "$ORIG" orig
run_one "$REWR" rewr

n1=$(wc -l < "$TMP/orig.out"); n2=$(wc -l < "$TMP/rewr.out")
m1=$(md5sum < "$TMP/orig.out" | cut -c1-32); m2=$(md5sum < "$TMP/rewr.out" | cut -c1-32)
s1=$(sort "$TMP/orig.out" | md5sum | cut -c1-32); s2=$(sort "$TMP/rewr.out" | md5sum | cut -c1-32)
echo "original : rows=$n1 md5=$m1  ($(basename "$ORIG") ${VARS[*]:-})"
echo "rewritten: rows=$n2 md5=$m2  ($(basename "$REWR"))"
if [[ "$m1" == "$m2" ]]; then
  echo "PASS"
elif [[ "$s1" == "$s2" ]]; then
  echo "FAIL (same rows, different order)"; diff <(cat -n "$TMP/orig.out") <(cat -n "$TMP/rewr.out") | head -10; exit 1
else
  echo "FAIL"; diff "$TMP/orig.out" "$TMP/rewr.out" | head -10; exit 1
fi
