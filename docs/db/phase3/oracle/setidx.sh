#!/bin/bash
# usage: setidx.sh base|C|S|Pc|Pm   -- drops all candidate indexes, creates the requested set (base = original DDL indexes)
D=$(dirname $0)
$D/sq.sh <<SQL >/dev/null 2>&1
DROP INDEX idx_outbox_rec_incomplete;
DROP INDEX idx_outbox_rec_incomplete_fb;
DROP INDEX idx_outbox_rec_status_created;
DROP INDEX idx_outbox_rec_key_cr;
SQL
case $1 in
 base) echo "CREATE INDEX idx_outbox_rec_key_cr ON outbox_record (record_key, created_at);" | $D/sq.sh;;
 C) cat $D/idx-C.sql | grep -v "DROP INDEX idx_outbox_rec_key_cr" | $D/sq.sh;;
 S) cat $D/idx-S.sql | $D/sq.sh;;
 Pc) $D/sq.sh < $D/idx-Pc.sql;;
 Pm) $D/sq.sh < $D/idx-Pm.sql;;
esac | grep -i "error\|ORA-" 
echo "exec dbms_stats.gather_table_stats(user,'OUTBOX_RECORD',cascade=>true,method_opt=>'FOR ALL COLUMNS SIZE AUTO');" | $D/sq.sh >/dev/null
$D/sq.sh <<< "select index_name from user_indexes where table_name='OUTBOX_RECORD' order by 1;" | tr '\n' ' '; echo
