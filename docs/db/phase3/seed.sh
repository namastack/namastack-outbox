#!/bin/bash
# usage: seed.sh <container> <client> <A|B>   (client: mysql | mariadb)
C=$1; CL=$2; S=$3
if [ "$S" = A ]; then NEWK=2000; NEWN=18; FN=17; else NEWK=25000; NEWN=13; FN=12; fi
Q="docker exec -i $C $CL -uroot -pp3 outbox"
$Q <<SQL
TRUNCATE outbox_record;
SET @now='2026-10-01 12:00:00';
DROP TABLE IF EXISTS d; CREATE TABLE d(i INT PRIMARY KEY); INSERT INTO d VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
DROP TABLE IF EXISTS kk; CREATE TABLE kk(k INT PRIMARY KEY);
INSERT INTO kk SELECT a.i+10*b.i+100*c.i+1000*e.i+10000*f.i+1 FROM d a,d b,d c,d e,d f WHERE a.i+10*b.i+100*c.i+1000*e.i+10000*f.i<50000;
DROP TABLE IF EXISTS nn; CREATE TABLE nn(n INT PRIMARY KEY); INSERT INTO nn SELECT a.i+10*b.i+1 FROM d a,d b WHERE a.i+10*b.i+1<=20;
SET SESSION sql_log_bin=0;
INSERT INTO outbox_record
SELECT md5(concat(k,'-',n)),
  CASE WHEN k<=$NEWK AND n>=$NEWN THEN 'NEW' WHEN k<=200 AND n=$FN THEN 'FAILED' ELSE 'COMPLETED' END,
  concat('order-',k),'com.example.OrderEvent',repeat('x',500),'{}', ts,
  CASE WHEN (k<=$NEWK AND n>=$NEWN) OR (k<=200 AND n=$FN) THEN NULL ELSE ts+interval 1 second END,
  CASE WHEN k<=200 AND n=$FN THEN 5 ELSE 0 END, NULL,
  CASE WHEN k<=100 AND n>=$NEWN THEN @now+interval 1 hour ELSE ts END,
  crc32(concat('order-',k)) % 256, 'com.example.Handler#handle'
FROM (SELECT n,k,timestamp('2026-09-01 12:00:00') + interval floor((n*100000+k)/20) second AS ts FROM nn JOIN kk) q
ORDER BY n,k;
SQL
