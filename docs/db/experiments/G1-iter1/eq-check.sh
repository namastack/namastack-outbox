#!/usr/bin/env bash
# Equivalence check original vs rewritten query file on one container (section 5.5), with tie analysis.
# Usage: eq-check.sh <container> <orig.sql> <rewr.sql> [--head N] [k=v ...]
#   k=v       psql vars for both runs (parts=256, generic=1, ...)
#   --head N  compare the first N rows of the original with the rewritten output (H04 small LIMIT)
# Rewritten runs additionally get batch=100000000 unless --head N (then batch=N) or batch is given.
# Verdicts: PASS (identical ordered output) | PASS-TIES (same rows; order identical except among keys
#   whose sort value MIN(created_at) of due NEW rows ties) | FAIL-ORDER | FAIL-ROWS
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"
C="$1"; O="$(realpath "$2")"; R="$(realpath "$3")"; shift 3
HEAD=""; VARS=(); RB=""
while [[ $# -gt 0 ]]; do
  case "$1" in --head) HEAD="$2"; shift 2 ;; batch=*) RB="${1#batch=}"; shift ;; *) VARS+=("$1"); shift ;; esac
done
[[ -n "$RB" ]] || RB="${HEAD:-100000000}"
T="$(mktemp -d)"; REM="/harness/eq-$$"
trap 'rm -rf "$T"; docker exec "$C" rm -rf "$REM" >/dev/null 2>&1 || true' EXIT
docker exec "$C" mkdir -p "$REM/o" "$REM/r"
docker cp -q "$O" "$C:$REM/o/q.sql"; docker cp -q "$R" "$C:$REM/r/q.sql"
docker cp -q "$DB/harness/queries/_params.sql" "$C:$REM/o/_params.sql"; docker cp -q "$DB/harness/queries/_params.sql" "$C:$REM/r/_params.sql"
run() { # dir out extra...
  local d=$1 out=$2; shift 2
  { echo '\set ON_ERROR_STOP on'; for kv in "${VARS[@]}" "$@"; do echo "\\set ${kv%%=*} ${kv#*=}"; done
    echo "\\set explain ''"; echo "\\i $REM/$d/q.sql"; } \
  | docker exec -i "$C" psql -U postgres -X -q -A -t -P pager=off 2>&1 | grep -v -E '^(PREPARE|DEALLOCATE|SET)$' > "$out"
}
run o "$T/o"; run r "$T/r" "batch=$RB"
[[ -n "$HEAD" ]] && { head -n "$HEAD" "$T/o" > "$T/o2"; mv "$T/o2" "$T/o"; }
docker exec "$C" psql -U postgres -X -q -A -t -F'|' -c "SELECT record_key, min(created_at) FROM outbox_record, bench_now b WHERE status='NEW' AND next_retry_at <= b.now GROUP BY 1" > "$T/map"
desc="$(basename "$(dirname "$R")")/$(basename "$R" .sql) ${VARS[*]:-} rbatch=$RB${HEAD:+ head=$HEAD}"
python3 -I - "$T/o" "$T/r" "$T/map" "$desc" <<'PY'
import sys
o=open(sys.argv[1]).read().splitlines(); r=open(sys.argv[2]).read().splitlines()
m=dict(l.split('|',1) for l in open(sys.argv[3]).read().splitlines() if '|' in l)
desc=sys.argv[4]
if o==r: v="PASS"
elif sorted(o)==sorted(r):
    so=[m.get(k) for k in o]; sr=[m.get(k) for k in r]
    v = "PASS-TIES" if so==sr and None not in so else "FAIL-ORDER"
    if v=="PASS-TIES":
        diffpos=sum(1 for a,b in zip(o,r) if a!=b); v+=f" ({diffpos} positions differ, all within equal MIN(created_at) groups)"
else:
    # head-N with a tie group at the boundary: same sort values, membership differs only in last tie group
    so=[m.get(k) for k in o]; sr=[m.get(k) for k in r]
    if len(o)==len(r) and so==sr and None not in so and set(o)-set(r) <= {k for k in o if m[k]==so[-1]}:
        v="PASS-TIES (boundary tie group membership differs)"
    else: v="FAIL-ROWS"
print(f"{v}\trows={len(o)}/{len(r)}\t{desc}")
if v.startswith("FAIL"):
    import difflib; print("\n".join(list(difflib.unified_diff(o,r,lineterm='',n=0))[:12]))
PY
