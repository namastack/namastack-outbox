#!/usr/bin/env bash
# Equivalence for G2: runs a query file (explain='') and compares its ordered output with a reference
# output captured on the control state (original SQL, baseline indexes) of the same data.
# Usage: eq.sh <container> <refdir> <tag> <qfile> [k=v ...]
#   refdir/<base>-<vars>.out is created on first use (call with tag=ref on the control state).
# Prints PASS | FAIL-ORDER (same rows) | FAIL-ROWS, rows, md5.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"
C="$1"; REF="$2"; TAG="$3"; F="$(realpath "$4")"; shift 4
key="$(basename "$F" .sql)$(printf -- '-%s' "$@")"; mkdir -p "$REF"
REM="/harness/eq-$$"; docker exec "$C" mkdir -p "$REM"
docker cp -q "$F" "$C:$REM/q.sql"; docker cp -q "$DB/harness/queries/_params.sql" "$C:$REM/_params.sql"
out="$(mktemp)"
{ echo '\set ON_ERROR_STOP on'; for kv in "$@"; do echo "\\set ${kv%%=*} ${kv#*=}"; done
  echo '\if :{?generic}'; echo '\else'; echo '\set generic 0'; echo '\endif'
  echo "\\set explain ''"; echo "\\i $REM/q.sql"; } \
 | docker exec -i "$C" psql -U postgres -X -q -A -t -P pager=off 2>&1 | grep -v -E '^(PREPARE|DEALLOCATE|SET)$' > "$out"
docker exec "$C" rm -rf "$REM"
ref="$REF/$key.out"
if [[ "$TAG" == ref ]]; then mv "$out" "$ref"; echo "REF	rows=$(wc -l < "$ref")	$key"; exit 0; fi
m1=$(md5sum < "$ref" | cut -c1-12); m2=$(md5sum < "$out" | cut -c1-12)
if [[ $m1 == "$m2" ]]; then v=PASS
elif [[ "$(sort "$ref" | md5sum)" == "$(sort "$out" | md5sum)" ]]; then v=FAIL-ORDER
else v=FAIL-ROWS; fi
echo "$v	rows=$(wc -l < "$ref")/$(wc -l < "$out")	md5=$m1/$m2	$TAG	$(basename "$(dirname "$F")")/$key"
[[ $v == PASS ]] || diff "$ref" "$out" | cut -c1-160 | head -6
rm -f "$out"
