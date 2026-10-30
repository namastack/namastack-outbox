#!/bin/bash
# usage: seed.sh A|B  -- 200k rows (10k keys x 20); A=1200 NEW (0.6%), B=40000 NEW (20%)
S=$1
if [ "$S" = A ]; then NEWK=400; NEWN=18; FN=17; else NEWK=5000; NEWN=13; FN=12; fi
DIR=$(dirname "$0")
$DIR/sq.sh <<SQL
set timing on
alter session set time_zone='UTC';
TRUNCATE TABLE outbox_record;
INSERT /*+ APPEND */ INTO outbox_record (id,status,record_key,record_type,payload,context,created_at,completed_at,failure_count,failure_reason,next_retry_at,partition_no,handler_id)
WITH d AS (SELECT level k FROM dual CONNECT BY level<=10000),
nn AS (SELECT level n FROM dual CONNECT BY level<=20),
q AS (SELECT n,k,TIMESTAMP '2026-09-01 12:00:00 +00:00' + numtodsinterval(floor((n*10000+k)/4),'second') ts FROM nn CROSS JOIN d)
SELECT rawtohex(standard_hash(k||'-'||n,'MD5')),
  CASE WHEN k<=$NEWK AND n>=$NEWN THEN 'NEW' WHEN k<=40 AND n=$FN THEN 'FAILED' ELSE 'COMPLETED' END,
  'order-'||k,'com.example.OrderEvent',TO_NCLOB(RPAD('x',300,'x')),TO_NCLOB('{}'),ts,
  CASE WHEN (k<=$NEWK AND n>=$NEWN) OR (k<=40 AND n=$FN) THEN NULL ELSE ts+numtodsinterval(1,'second') END,
  CASE WHEN k<=40 AND n=$FN THEN 5 ELSE 0 END, NULL,
  CASE WHEN k<=20 AND n>=$NEWN THEN TIMESTAMP '2026-10-01 13:00:00 +00:00' ELSE ts END,
  ora_hash('order-'||k,255), 'com.example.Handler#handle'
FROM q;
COMMIT;
exec dbms_stats.gather_table_stats(user,'OUTBOX_RECORD',cascade=>true,method_opt=>'FOR ALL COLUMNS SIZE AUTO');
select status,count(*) from outbox_record group by status;
SQL
