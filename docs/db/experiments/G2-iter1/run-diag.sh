#!/usr/bin/env bash
# Diagnostic (A, B): runtime of the ordered Index Scan plans the planner does not pick in custom mode.
# enable_bitmapscan=off and random_page_cost=1.1, custom + generic, for H12 (H02 index) and H10 (replace).
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; DB="$(cd "$D/../.." && pwd)"; HS="$DB/harness"; Q="$D/qdirs"
C=outbox-db-H12; RE="$HS/run-explain.sh"; source "$D/states.sh"; trap 'docker rm -f -v $C >/dev/null 2>&1' EXIT
diag() { # label qdir
  for g in "" --generic; do
    "$RE" -c $C -s $1 -q $2 $g --set enable_bitmapscan=off --tag nobitmap findIncompleteRecordPages
    "$RE" -c $C -s $1 -q $2 $g --set random_page_cost=1.1 --tag rpc11 findIncompleteRecordPages
  done
}
for S in "$@"; do
  docker rm -f -v $C >/dev/null 2>&1; "$HS/start-postgres.sh" H12 $S >/dev/null || exit 1
  diag G2ctl-$S "$Q/base"; diag H11-$S "$Q/H11"
  state_H02; diag H12-$S "$Q/H12"
  state_H10add; state_H10; diag H10-$S "$Q/base"
  docker rm -f -v $C >/dev/null
done
