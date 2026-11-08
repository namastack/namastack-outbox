#!/usr/bin/env bash
# purges MySQL binary logs when free disk < 16 GB (emergency only; the driver purges after every cell)
while pgrep -f confirm-run.sh >/dev/null; do
  f=$(df -BG --output=avail / | tail -1 | tr -dc 0-9)
  if [ "$f" -lt 16 ]; then docker exec outbox-bench-mysql-L1 mysql -uroot -pbench -e "PURGE BINARY LOGS BEFORE NOW()" >/dev/null 2>&1; echo "$(date) purged at ${f}G" >> /home/azamojski/namastack-outbox/docs/db-mariadb/mysql-check/results-confirm/logs/disk-watch.log; fi
  sleep 20
done
