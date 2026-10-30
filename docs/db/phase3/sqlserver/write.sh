#!/bin/bash
# write cost: insert 50k NEW rows, complete them (update), delete. usage: write.sh <label>
D=$(dirname $0)
$D/sq.sh <<SQL | sed "s/^/$1|/"
SET NOCOUNT ON;
DELETE FROM outbox_record WHERE id LIKE 'w-%';
DECLARE @t datetime2(7);
SET @t=SYSDATETIME();
INSERT INTO outbox_record SELECT TOP 50000 CONCAT('w-',id),'NEW',record_key,record_type,payload,context,DATEADD(second,ABS(CHECKSUM(id))%50000,'2026-10-02'),NULL,0,NULL,'2026-10-02',partition_no,handler_id FROM outbox_record WHERE status='COMPLETED';
SELECT 'insert50k_ms', DATEDIFF_BIG(microsecond,@t,SYSDATETIME())/1000.0;
SET @t=SYSDATETIME();
UPDATE outbox_record SET status='COMPLETED', completed_at='2026-10-02 01:00:00' WHERE id LIKE 'w-%';
SELECT 'update50k_ms', DATEDIFF_BIG(microsecond,@t,SYSDATETIME())/1000.0;
SET @t=SYSDATETIME();
DELETE FROM outbox_record WHERE id LIKE 'w-%';
SELECT 'delete50k_ms', DATEDIFF_BIG(microsecond,@t,SYSDATETIME())/1000.0;
SQL
