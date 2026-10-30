# Query templates for MySQL/MariaDB phase 3. P = partition list (0..63), NOW fixed.
NOW = "2026-10-01 12:00:00"
P = ",".join(str(i) for i in range(64))

def cur_keys_f():
    return f"""SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN ({P}) AND o.status='NEW' AND o.next_retry_at <= '{NOW}'
AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))
GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC"""

def cur_keys_n():
    return f"""SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN ({P}) AND o.status='NEW' AND o.next_retry_at <= '{NOW}'
GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC"""

def new_keys_f(mode, lim):
    outer = "o.completed_at IS NULL" if mode == "C" else "1=1"
    inc = "older.completed_at IS NULL" if mode == "C" else "older.status IN ('NEW','FAILED')"
    l = f"LIMIT {lim}" if lim else ""
    return f"""SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN ({P}) AND o.status='NEW' AND o.next_retry_at <= '{NOW}' AND {outer}
AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND {inc}
 AND older.created_at<=o.created_at AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))
ORDER BY o.created_at ASC, o.id ASC {l}"""

def new_keys_n(mode, lim):
    outer = "o.completed_at IS NULL" if mode == "C" else "1=1"
    inc = "earlier.completed_at IS NULL AND" if mode == "C" else ""
    l = f"LIMIT {lim}" if lim else ""
    return f"""SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN ({P}) AND o.status='NEW' AND o.next_retry_at <= '{NOW}' AND {outer}
AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key=o.record_key AND {inc} earlier.status='NEW' AND earlier.next_retry_at <= '{NOW}'
 AND earlier.created_at<=o.created_at AND (earlier.created_at<o.created_at OR (earlier.created_at=o.created_at AND earlier.id<o.id)))
ORDER BY o.created_at ASC, o.id ASC {l}"""

def cur_oldest_due():
    return f"""SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN ({P}) AND o.status='NEW' AND o.next_retry_at <= '{NOW}'
AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))"""

def new_oldest_due(mode):
    inc = "older.completed_at IS NULL" if mode == "C" else "older.status IN ('NEW','FAILED')"
    return f"""SELECT (SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN ({P}) AND o.status='NEW' AND o.next_retry_at <= '{NOW}'
AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND {inc}
 AND older.created_at<=o.created_at AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))
ORDER BY o.next_retry_at ASC LIMIT 1)"""

def cur_oldest_created():
    return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({P}) AND status='NEW'"
def new_oldest_created():
    return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({P}) AND status='NEW' AND completed_at IS NULL"

def cur_pages(keys):
    k = ",".join("'%s'" % x for x in keys)
    return f"SELECT * FROM outbox_record WHERE record_key IN ({k}) AND status='NEW' ORDER BY record_key, created_at, id"
def in_pages(keys, mode):
    k = ",".join("'%s'" % x for x in keys)
    if mode == "C":
        return f"SELECT * FROM outbox_record WHERE record_key IN ({k}) AND status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id"
    return f"SELECT * FROM outbox_record WHERE record_key IN ({k}) AND status='NEW' ORDER BY record_key, created_at, id"
def lat_pages(keys, mode):
    k = " UNION ALL ".join("SELECT '%s' AS k" % x for x in keys)
    k = "VALUES " + ",".join("ROW('%s')" % x for x in keys)
    if mode == "C":
        inner = "r.record_key=k.k AND r.status='NEW' AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id"
    else:
        inner = "r.record_key=k.k AND r.status='NEW' ORDER BY r.created_at, r.id"
    return f"SELECT o.* FROM (SELECT column_0 AS k FROM ({k}) v) k CROSS JOIN LATERAL (SELECT r.* FROM outbox_record r WHERE {inner}) o"

CUT = "2026-09-01 14:00:00"   # roughly the 10% completed_at percentile; recomputed by driver
def cur_cleanup(cut): return f"SELECT * FROM outbox_record WHERE status='COMPLETED' AND completed_at < '{cut}' ORDER BY completed_at"
def new_cleanup(cut, lim=1000): return cur_cleanup(cut) + f" LIMIT {lim}"
def cur_cleanup_ids(cut): return f"SELECT id FROM outbox_record WHERE status='COMPLETED' AND completed_at < '{cut}' ORDER BY completed_at"
def new_cleanup_ids(cut, lim=1000): return cur_cleanup_ids(cut) + f" LIMIT {lim}"
def cur_failed_before(): return f"SELECT * FROM outbox_record WHERE status='FAILED' AND next_retry_at < '{NOW}' ORDER BY next_retry_at"
def new_failed_before(lim=1000): return cur_failed_before() + f" LIMIT {lim}"
