#!/usr/bin/env bash
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for s in A B D C E; do "$D/run-scenario.sh" S1 $s eq; done
"$D/run-misc.sh" edge
for s in A B D; do "$D/run-scenario.sh" S2 $s eq; done
"$D/run-misc.sh" vm
echo ALLDONE
