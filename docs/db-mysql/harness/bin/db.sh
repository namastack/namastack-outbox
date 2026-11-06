#!/usr/bin/env bash
# Start/stop one dialect's container with fixed limits. usage: bin/db.sh start|stop|status|rm|wipe <dialect>
# dialects: postgres mysql mariadb sqlserver oracle (h2 is in-process: nothing to start)
# Settings changed from image defaults are listed in README.md ("Container settings").
# MySQL image: HARNESS_MYSQL_IMAGE (default mysql:8.4, the LTS line; the MySQL study pins the tag/id it uses in STATE.md).
# Lanes (parallel runs): HARNESS_LANE=<n> (n>=1) -> container outbox-bench-<d>-L<n>, volume outbox-bench-<d>-L<n>-data, port <base>+100*n.
#   HARNESS_CPUSET=<list> pins the container (docker --cpuset-cpus), e.g. "0-3". HARNESS_EXTRA_ARGS adds server options after the image
#   (e.g. "--innodb-buffer-pool-size=2G" for a sensitivity run; record it in the report). Starting a lane never stops other lanes;
#   lane 0 (unset) keeps the old behaviour of stopping every other harness container.
set -euo pipefail
cmd="${1:?cmd}"; d="${2:?dialect}"
CPUS="${HARNESS_CPUS:-4}"; MEM="${HARNESS_MEM:-4g}"
LANE="${HARNESS_LANE:-0}"; SFX=""; [ "$LANE" != 0 ] && SFX="-L$LANE"
name="outbox-bench-$d$SFX"; vol="outbox-bench-$d$SFX-data"
CPUSET_ARGS=(); [ -n "${HARNESS_CPUSET:-}" ] && CPUSET_ARGS=(--cpuset-cpus="$HARNESS_CPUSET")
read -r -a SERVER_ARGS <<< "${HARNESS_EXTRA_ARGS:-}"
case "$d" in
  postgres)  img=postgres:18-alpine;  port=15432; env=(-e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench)
             mnt=/var/lib/postgresql; extra=(--shm-size=512m) ;;
  mysql)     img="${HARNESS_MYSQL_IMAGE:-mysql:8.4}";         port=13306; env=(-e MYSQL_ROOT_PASSWORD=bench); mnt=/var/lib/mysql; extra=() ;;
  mariadb)   img=mariadb:12;          port=13307; env=(-e MARIADB_ROOT_PASSWORD=bench); mnt=/var/lib/mysql; extra=() ;;
  sqlserver) img=mcr.microsoft.com/mssql/server:2022-latest; port=11433
             env=(-e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD=Bench_pw1234 -e MSSQL_MEMORY_LIMIT_MB=3072); mnt=/var/opt/mssql; extra=() ;;
  oracle)    img=gvenzl/oracle-xe:18-slim-faststart; port=11521; env=(-e ORACLE_PASSWORD=bench); mnt=/opt/oracle/oradata; extra=() ;;
  h2) echo "h2 is in-process (data dir: harness/build/h2)"; exit 0 ;;
  *) echo "unknown dialect $d"; exit 1 ;;
esac
port=$((port + 100 * LANE))
case "$cmd" in
  start)
    if docker ps --format '{{.Names}}' | grep -qx "$name"; then echo "$name already running"; exit 0; fi
    # lane 0: one dialect at a time (disk, CPU); lanes >= 1 leave other containers running
    if [ "$LANE" = 0 ]; then
      for o in $(docker ps --format '{{.Names}}' | grep '^outbox-bench-' || true); do echo "stopping $o"; docker stop "$o" >/dev/null; done
    fi
    if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then docker start "$name" >/dev/null
    else docker run -d --name "$name" --cpus="$CPUS" "${CPUSET_ARGS[@]}" --memory="$MEM" -p "127.0.0.1:$port:$([ $d = postgres ] && echo 5432 || ([ $d = mysql ] && echo 3306) || ([ $d = mariadb ] && echo 3306) || ([ $d = sqlserver ] && echo 1433) || echo 1521)" \
         -v "$vol:$mnt" "${env[@]}" "${extra[@]}" "$img" "${SERVER_ARGS[@]}" >/dev/null; fi
    echo "waiting for $d ..."
    for i in $(seq 1 180); do
      case "$d" in
        postgres)  docker exec "$name" pg_isready -U bench -d bench >/dev/null 2>&1 && break ;;
        mysql)     docker exec "$name" mysqladmin ping -h 127.0.0.1 -pbench --silent >/dev/null 2>&1 && break ;;
        mariadb)   docker exec "$name" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1 && break ;;
        sqlserver) [ -n "$(docker logs "$name" 2>&1 | grep -m1 'SQL Server is now ready for client connections')" ] && break ;;
        oracle)    [ -n "$(docker logs "$name" 2>&1 | grep -m1 'DATABASE IS READY TO USE')" ] && break ;;
      esac; sleep 2
    done
    sleep 5   # first start: images run a temporary init server before the real one
    for i in $(seq 1 60); do
      case "$d" in
        postgres) docker exec "$name" pg_isready -h 127.0.0.1 -U bench -d bench >/dev/null 2>&1 && break ;;
        mysql)    docker exec "$name" mysqladmin ping -h 127.0.0.1 -pbench --silent >/dev/null 2>&1 && break ;;
        *) break ;;
      esac; sleep 2
    done
    echo "$name ready on 127.0.0.1:$port" ;;
  stop) docker stop "$name" >/dev/null 2>&1 && echo stopped "$name" || echo "$name not running" ;;
  status) docker ps -a --filter "name=$name" --format '{{.Names}} {{.Status}}' ;;
  rm) docker rm -f "$name" >/dev/null 2>&1 || true; echo "removed container $name (volume $vol kept)" ;;
  wipe) docker rm -f "$name" >/dev/null 2>&1 || true; docker volume rm "$vol" >/dev/null 2>&1 || true; echo "removed $name and volume $vol" ;;
esac
