#!/usr/bin/env bash
# Like G2-iter1/eq.sh plus per-key digest verdict: PASS (exact ordered md5) | PASS-PERKEY (order across keys differs, every key's
# row sequence identical) | FAIL-ORDER (same rows, per-key order differs) | FAIL-ROWS. Usage: eq2.sh <container> <refdir> <tag> <qfile> [k=v ...]
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
elif [[ "$(sort "$ref" | md5sum)" != "$(sort "$out" | md5sum)" ]]; then v=FAIL-ROWS
else
  pk() { awk -F'|' '{print $3"\t"$0}' "$1" | awk -F'\t' '{h[$1]=h[$1] $2 "\n"} END{for(k in h) print k}' | wc -l >/dev/null
         python3 -I -c 'import sys,hashlib,collections
g=collections.OrderedDict()
for l in open(sys.argv[1]): g.setdefault(l.split("|")[2],[]).append(l)
print(hashlib.md5("".join(k+hashlib.md5("".join(v).encode()).hexdigest() for k,v in sorted(g.items())).encode()).hexdigest())' "$1"; }
  if [[ "$(pk "$ref")" == "$(pk "$out")" ]]; then v=PASS-PERKEY; else v=FAIL-ORDER; fi
fi
echo "$v	rows=$(wc -l < "$ref")/$(wc -l < "$out")	md5=$m1/$m2	$TAG	$(basename "$(dirname "$F")")/$key"
[[ $v == PASS || $v == PASS-PERKEY ]] || diff "$ref" "$out" | cut -c1-160 | head -6
rm -f "$out"
