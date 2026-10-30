#!/bin/bash
# usage: seed.sh A|B   -- 200k rows (10k keys x 20); A = 1200 NEW (0.6%), B = 40000 NEW (20%)
S=$1
if [ "$S" = A ]; then NEWK=400; NEWN=18; FN=17; else NEWK=5000; NEWN=13; FN=12; fi
DIR=$(dirname "$0")
$DIR/sq.sh <<SQL
TRUNCATE TABLE outbox_record;
DECLARE @now datetime2(6)='2026-10-01 12:00:00';
WITH d AS (SELECT TOP 10000 ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS k FROM sys.all_objects a CROSS JOIN sys.all_objects b),
nn AS (SELECT TOP 20 ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n FROM sys.all_objects),
q AS (SELECT n,k,DATEADD(second,(n*10000+k)/4,CAST('2026-09-01 12:00:00' AS datetime2(6))) AS ts FROM nn CROSS JOIN d)
INSERT INTO outbox_record (id,status,record_key,record_type,payload,context,created_at,completed_at,failure_count,failure_reason,next_retry_at,partition_no,handler_id)
SELECT CONVERT(varchar(32),HASHBYTES('MD5',CONCAT(k,'-',n)),2),
  CASE WHEN k<=$NEWK AND n>=$NEWN THEN 'NEW' WHEN k<=40 AND n=$FN THEN 'FAILED' ELSE 'COMPLETED' END,
  CONCAT('order-',k),'com.example.OrderEvent',REPLICATE(CAST('x' AS nvarchar(max)),500),'{}',ts,
  CASE WHEN (k<=$NEWK AND n>=$NEWN) OR (k<=40 AND n=$FN) THEN NULL ELSE DATEADD(second,1,ts) END,
  CASE WHEN k<=40 AND n=$FN THEN 5 ELSE 0 END, NULL,
  CASE WHEN k<=20 AND n>=$NEWN THEN DATEADD(hour,1,@now) ELSE ts END,
  ABS(CAST(SUBSTRING(HASHBYTES('MD5',CONCAT('order-',k)),1,4) AS bigint) % 256), 'com.example.Handler#handle'
FROM q;
UPDATE STATISTICS outbox_record WITH FULLSCAN;
SELECT status,COUNT(*) FROM outbox_record GROUP BY status;
SQL
