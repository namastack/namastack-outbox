#!/bin/bash
# usage: start.sh mysql|mariadb
DB=$1; D=/home/zamo/git/namastack-outbox/namastack-outbox-jdbc/src/main/resources/schema/$DB/outbox-tables.sql
if [ $DB = mysql ]; then IMG=mysql:9.2; CL=mysql; else IMG=mariadb:12; CL=mariadb; fi
N=outbox-db-P3-$DB
docker rm -f -v $N 2>/dev/null
docker run -d --name $N -e MYSQL_ROOT_PASSWORD=p3 -e MYSQL_DATABASE=outbox -e MARIADB_ROOT_PASSWORD=p3 -e MARIADB_DATABASE=outbox --tmpfs /var/lib/mysql:rw,size=3200m $IMG --innodb-buffer-pool-size=1G >/dev/null
for i in $(seq 90); do docker exec $N $CL -uroot -pp3 -e 'select 1' outbox >/dev/null 2>&1 && break; sleep 2; done
docker exec -i $N $CL -uroot -pp3 outbox < $D && echo schema ok
