#!/bin/bash
# write cost: insert 50k NEW rows, then complete them (update), then delete. usage: write.sh <container> <client> <label>
C=$1; CL=$2; L=$3
docker exec -i $C $CL -uroot -pp3 outbox -B -N 2>/dev/null <<SQL | sed "s/^/$L\t/"
DELETE FROM outbox_record WHERE id LIKE 'w-%';
SET @t=SYSDATE(6);
INSERT INTO outbox_record SELECT concat('w-',id),'NEW',record_key,record_type,payload,context,'2026-10-02 00:00:00'+interval (crc32(id)%50000) second,NULL,0,NULL,'2026-10-02 00:00:00',partition_no,handler_id FROM outbox_record WHERE status='COMPLETED' LIMIT 50000;
SELECT 'insert50k_ms', TIMESTAMPDIFF(MICROSECOND,@t,SYSDATE(6))/1000;
SET @t=SYSDATE(6);
UPDATE outbox_record SET status='COMPLETED', completed_at='2026-10-02 01:00:00' WHERE id LIKE 'w-%';
SELECT 'update50k_ms', TIMESTAMPDIFF(MICROSECOND,@t,SYSDATE(6))/1000;
SET @t=SYSDATE(6);
DELETE FROM outbox_record WHERE id LIKE 'w-%';
SELECT 'delete50k_ms', TIMESTAMPDIFF(MICROSECOND,@t,SYSDATE(6))/1000;
SQL
