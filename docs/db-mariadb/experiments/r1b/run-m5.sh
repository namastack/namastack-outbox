#!/usr/bin/env bash
# Part 3 (M5): pages forms on the b-base schema; labels S8-S5, S5 (keys from keys_strict) and S4-b100 (--batch 100, keys file s4-pages-keys-b100.txt).
set -u
H=/home/azamojski/namastack-outbox/docs/db-mariadb/harness; X=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1b
XR=/home/azamojski/namastack-outbox/docs/db-mariadb/experiments/r1; R=/home/azamojski/namastack-outbox/docs/db-mariadb/results/r1b
OPT=/home/azamojski/outbox-wt-mdb-opt; LOG=$R/logs/m5.log
cd "$H"; source bin/lane.sh 1 >/dev/null || exit 1
log(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
FORMS="pa-00-base pa-01-latejoin pa-02-semi-in pa-06-json-table pa-08-values-cte pa-09-union-per-key"
va(){ echo "--variant m5-$1 --worktree $OPT --catalog-variant mdb-opt --ddl $X/b-base/ddl.sql --queries $X/m5-$1/queries"; }
run(){ log "RUN $* (load $(cut -d' ' -f1-3 /proc/loadavg))"; "$@" >>"$LOG" 2>&1 || { log "FAILED: $*"; echo "FAILED m5: $*" >> $R/failures.txt; }; }
bin/bench gen --dialect mariadb --scenario S5 >>"$LOG" 2>&1
run bin/bench prepare --dialect mariadb $(va pa-00-base) --scenario S5 --label S5 --results $R
for f in $FORMS; do
  run bin/bench bench --dialect mariadb $(va $f) --scenario S5 --label S8-S5 --owned mod4 --results $R --groups HOT --only pages --query-limit-s 60
  run bin/bench bench --dialect mariadb $(va $f) --scenario S5 --label S5 --results $R --groups HOT --only pages --query-limit-s 10
done
bin/bench gen --dialect mariadb --scenario S4 >>"$LOG" 2>&1
run bin/bench prepare --dialect mariadb $(va pa-00-base) --scenario S4 --label S4-b100 --results $R
docker exec -i outbox-bench-mariadb-L1 mariadb -uroot -pbench -N -B run -e "SET time_zone='+00:00'; SELECT record_key FROM outbox_record WHERE status='NEW' AND completed_at IS NULL AND next_retry_at <= '2026-01-01 12:00:00' ORDER BY created_at, id LIMIT 3000" | awk '!s[$0]++' | head -100 > $XR/s4-pages-keys-b100.txt
log "b100 keys: $(wc -l < $XR/s4-pages-keys-b100.txt); first 10 equal to s4-pages-keys.txt: $(head -10 $XR/s4-pages-keys-b100.txt | diff -q - $XR/s4-pages-keys.txt >/dev/null && echo yes || echo NO)"
for f in $FORMS; do
  run bin/bench bench --dialect mariadb $(va $f) --scenario S4 --label S4-b100 --batch 100 --pages-keys-file $XR/s4-pages-keys-b100.txt --results $R --groups HOT --only pages --query-limit-s 10
done
bin/bench dropruns --dialect mariadb >>"$LOG" 2>&1
log "M5 DONE"
