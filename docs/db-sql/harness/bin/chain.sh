#!/usr/bin/env bash
# usage: DIALECT=x [FIRST=S2] REST="S4 S5 S7" bin/chain.sh  -> FIRST resumes on the existing snapshot (SKIPGEN), REST with fresh gen
H="$(cd "$(dirname "$0")/.." && pwd)"
[ -n "$FIRST" ] && SKIPGEN=1 SCENARIOS="$FIRST" "$H/bin/matrix-sql.sh"
SCENARIOS="$REST" "$H/bin/matrix-sql.sh"
