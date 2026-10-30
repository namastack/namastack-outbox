#!/bin/bash
# write cost: insert 50k NEW rows, complete them (update), delete. usage: write.sh <label>
D=$(dirname $0)
$D/sq.sh <<SQL | grep -v "^$" | sed "s/^/$1|/"
set serveroutput on timing off feedback off
alter session set time_zone='UTC';
DELETE FROM outbox_record WHERE id LIKE 'w-%'; COMMIT;
declare t0 number;
begin
 t0:=dbms_utility.get_time;
 INSERT INTO outbox_record (id,status,record_key,record_type,payload,context,created_at,completed_at,failure_count,failure_reason,next_retry_at,partition_no,handler_id)
  SELECT 'w-'||id,'NEW',record_key,record_type,payload,context,TIMESTAMP '2026-10-02 00:00:00 +00:00'+numtodsinterval(mod(ora_hash(id),50000),'second'),NULL,0,NULL,TIMESTAMP '2026-10-02 00:00:00 +00:00',partition_no,handler_id FROM outbox_record WHERE status='COMPLETED' AND rownum<=50000;
 commit; dbms_output.put_line('insert50k_ms '||(dbms_utility.get_time-t0)*10);
 t0:=dbms_utility.get_time;
 UPDATE outbox_record SET status='COMPLETED', completed_at=TIMESTAMP '2026-10-02 01:00:00 +00:00' WHERE id LIKE 'w-%';
 commit; dbms_output.put_line('update50k_ms '||(dbms_utility.get_time-t0)*10);
 t0:=dbms_utility.get_time;
 DELETE FROM outbox_record WHERE id LIKE 'w-%';
 commit; dbms_output.put_line('delete50k_ms '||(dbms_utility.get_time-t0)*10);
end;
/
SQL
