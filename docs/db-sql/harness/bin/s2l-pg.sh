#!/usr/bin/env bash
H="$(cd "$(dirname "$0")/.." && pwd)"; cd "$H"; L="$H/../results/logs/s2l.log"
D="--dialect postgres"
bin/db.sh start postgres >>$L 2>&1
echo "[$(date +%T)] gen" >>$L; bin/bench gen $D --scenario S2L >>$L 2>&1 || echo "FAILED gen" >>$L
for v in main v1; do echo "[$(date +%T)] bench $v" >>$L
  bin/bench bench $D --variant $v --worktree /home/zamo/git/outbox-wt-$v --scenario S2L --prepare >>$L 2>&1 || echo "FAILED $v" >>$L; df -h / | tail -1 >>$L; done
echo "[$(date +%T)] DONE" >>$L
