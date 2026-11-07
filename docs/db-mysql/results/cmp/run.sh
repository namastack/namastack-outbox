#!/usr/bin/env bash
cd /home/zamo/git/namastack-outbox/docs/db-mysql/harness
export LANE=1 VARIANTS="my-main=/home/zamo/git/outbox-wt-my-main my-opt=/home/zamo/git/outbox-wt-my-opt"
CELLS=(bench:*:S1
 bench:*:S2 bench:*:S8-S2 bench:*:S16-S2 writecost:*:S2-wc churn:*:S9 concurrency:*:S10-S2-p1 concurrency:*:S10-S2-p4 concurrency:*:S10-S2-p1-unthr concurrency:*:S10-S2-p4-unthr
 bench:*:S4 bench:*:S8-S4 bench:*:S16-S4 bench:*:S4-b100 writecost:*:S4-wc concurrency:*:S10-S4-p4 concurrency:*:S10-S4-p4-unthr
 bench:*:S5 bench:*:S8-S5 bench:*:S6 bench:*:S8-S6 bench:*:S7 bench:*:S8-S7)
case "$1" in
 p1) RUN=cmp-p1 ORDER=forward RESULTS=../results/cmp/p1 bin/run-cells.sh "${CELLS[@]}";;
 restart) RUN=cmp-restart ORDER=forward RESULTS=../results/cmp/p1 bin/run-cells.sh restart:-:-;;
 p2) RUN=cmp-p2 ORDER=reverse RESULTS=../results/cmp/p2 bin/run-cells.sh "${CELLS[@]}";;
 equiv) RUN=cmp-equiv RESULTS=../results/cmp/equiv bin/run-cells.sh equiv:my-main+my-opt:S1 equiv:my-main+my-opt:S2 equiv:my-main+my-opt:S5 equiv:my-main+my-opt:S7;;
 all) "$0" p1; "$0" restart; "$0" p2; "$0" equiv;;
esac
