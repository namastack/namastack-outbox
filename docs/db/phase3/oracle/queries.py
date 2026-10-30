# Oracle query templates. Render modes: 'sp' (sqlplus bind variables :x), 'pl' (PL/SQL static SQL, plain variable names), 'lit' (literals)
NOW = "2026-10-01 12:00:00"
PL = list(range(64))
class R:
    def __init__(s, mode): s.m = mode
    def b(s, name):
        if s.m == "lit":
            return {"st": "'NEW'", "now": f"TIMESTAMP '{NOW} +00:00'", "n": "100"}[name]
        if name == "now" and s.m == "sp": return "to_timestamp_tz(:nowc,'YYYY-MM-DD HH24:MI:SS TZH:TZM')"
        return (":" if s.m == "sp" else "") + name
    def parts(s):
        if s.m == "lit": return ",".join(str(i) for i in PL)
        return ",".join((":" if s.m == "sp" else "") + f"p{i}" for i in PL)

PROBE_F = """AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND older.created_at<=o.created_at AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))"""
PROBE_CUR = """AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))"""
def probe_n(r): return f"""AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key=o.record_key AND earlier.completed_at IS NULL AND earlier.status='NEW' AND earlier.next_retry_at<={r.b('now')}
 AND earlier.created_at<=o.created_at AND (earlier.created_at<o.created_at OR (earlier.created_at=o.created_at AND earlier.id<o.id)))"""

def keys_cur(r, f=True):
    inner = PROBE_CUR if f else ""
    return f"SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN ({r.parts()}) AND o.status={r.b('st')} AND o.next_retry_at<={r.b('now')} {inner} GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC"
def keys_new(r, f=True, lim=True, fetch="fetch", utc=True, comp=True, extra="", mode="plain"):
    probe = PROBE_F if f else probe_n(r)
    q = f"SELECT o.record_key{extra} FROM outbox_record o WHERE o.partition_no IN ({r.parts()}) AND o.status={r.b('st')} AND o.next_retry_at<={r.b('now')} {'AND o.completed_at IS NULL' if comp else ''} {probe} ORDER BY {'SYS_EXTRACT_UTC(o.created_at)' if utc else 'o.created_at'} ASC, o.id ASC"
    if mode == "C":   # completed_at-leading index: IS NULL and ordering must be spelled on the normalized (UTC) index column
        q = q.replace("o.completed_at IS NULL", "SYS_EXTRACT_UTC(o.completed_at) IS NULL").replace("ORDER BY SYS_EXTRACT_UTC(o.created_at)", "ORDER BY SYS_EXTRACT_UTC(o.completed_at), SYS_EXTRACT_UTC(o.created_at)")
    if lim and fetch == "fetch": q += f" FETCH FIRST {r.b('n')} ROWS ONLY"
    if lim and fetch == "rownum": q = f"SELECT record_key FROM ({q}) WHERE ROWNUM<={r.b('n')}"
    return q
# function-based "partial" index forms. E(x) = CASE WHEN completed_at IS NULL THEN x END ; timestamps are indexed as SYS_EXTRACT_UTC(..) so index order is usable
def E(x): 
    v = f"SYS_EXTRACT_UTC(o.{x})" if x in ("created_at", "next_retry_at") else f"o.{x}"
    return f"CASE WHEN o.completed_at IS NULL THEN {v} END"
def probe_fbi(): return f"""AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key={E('record_key')} AND older.completed_at IS NULL
 AND SYS_EXTRACT_UTC(older.created_at)<={E('created_at')} AND (SYS_EXTRACT_UTC(older.created_at)<{E('created_at')} OR (SYS_EXTRACT_UTC(older.created_at)={E('created_at')} AND older.id<{E('id')})))"""
def nowu(r): return f"SYS_EXTRACT_UTC({r.b('now')})"
def keys_fbi(r, cov=True, lim=True, extra=''):
    if cov:
        q = f"""SELECT {E('record_key')}{extra} FROM outbox_record o WHERE {E('partition_no')} IN ({r.parts()}) AND {E('status')}={r.b('st')} AND {E('next_retry_at')}<={nowu(r)}
{probe_fbi()} ORDER BY {E('created_at')}, {E('id')}"""
    else:
        q = f"""SELECT o.record_key{extra} FROM outbox_record o WHERE {E('created_at')} IS NOT NULL AND o.partition_no IN ({r.parts()}) AND o.status={r.b('st')} AND o.next_retry_at<={r.b('now')}
{PROBE_F} ORDER BY {E('created_at')}, {E('id')}"""
    if lim: q += f" FETCH FIRST {r.b('n')} ROWS ONLY"
    return q
def oldest_due_cur(r):
    return f"SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN ({r.parts()}) AND o.status={r.b('st')} AND o.next_retry_at<={r.b('now')} {PROBE_CUR}"
def oldest_due_new(r, mode="plain"):
    q = oldest_due_new0(r)
    q = q.replace("SELECT ", "SELECT /*+ FIRST_ROWS(1) */ ", 1)
    if mode == "S": q = q.replace("AND o.completed_at IS NULL", "")
    if mode == "C": q = q.replace("o.completed_at IS NULL", "SYS_EXTRACT_UTC(o.completed_at) IS NULL")
    return q
def oldest_due_new0(r):
    return f"SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN ({r.parts()}) AND o.status={r.b('st')} AND o.next_retry_at<={r.b('now')} AND o.completed_at IS NULL {PROBE_F} ORDER BY SYS_EXTRACT_UTC(o.next_retry_at) ASC FETCH FIRST 1 ROWS ONLY"
def oldest_due_fbi(r):
    return f"SELECT {E('next_retry_at')} FROM outbox_record o WHERE {E('partition_no')} IN ({r.parts()}) AND {E('status')}={r.b('st')} AND {E('next_retry_at')}<={nowu(r)} {probe_fbi()} ORDER BY {E('next_retry_at')} FETCH FIRST 1 ROWS ONLY"
def oldest_created_cur(): return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({','.join(map(str,PL))}) AND status='NEW'"
def oldest_created_new(mode='plain'):
    if mode == 'S': return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({','.join(map(str,PL))}) AND status='NEW'"
    if mode == 'C': return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({','.join(map(str,PL))}) AND status='NEW' AND SYS_EXTRACT_UTC(completed_at) IS NULL"
    return oldest_created_new0()
def oldest_created_new0(): return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({','.join(map(str,PL))}) AND status='NEW' AND completed_at IS NULL"
def oldest_created_fbi(): return f"SELECT MIN({E('created_at')}) FROM outbox_record o WHERE {E('partition_no')} IN ({','.join(map(str,PL))}) AND {E('status')}='NEW'"
def kl(keys): return ",\n".join("'%s'" % x for x in keys)
def pages_cur(keys): return f"SELECT * FROM outbox_record WHERE record_key IN ({kl(keys)}) AND status='NEW' ORDER BY record_key, created_at, id"
def pages_in(keys): return f"SELECT * FROM outbox_record WHERE record_key IN ({kl(keys)}) AND status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id"
def pages_lat(keys, src="odci"):
    if src == "odci": ks = f"SELECT column_value k FROM TABLE(sys.odcivarchar2list({kl(keys)}))"
    else: ks = " UNION ALL ".join(f"SELECT '{k}' k FROM dual" for k in keys)
    return f"SELECT o.* FROM ({ks}) k CROSS APPLY (SELECT r.* FROM outbox_record r WHERE r.record_key=k.k AND r.status='NEW' AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id) o"
def pages_lat2(keys):   # LATERAL without ORDER BY in the inline view (orders by key only)
    ks = f"SELECT column_value k FROM TABLE(sys.odcivarchar2list({kl(keys)}))"
    return f"SELECT o.* FROM ({ks}) k CROSS APPLY (SELECT r.* FROM outbox_record r WHERE r.record_key=k.k AND r.status='NEW' AND r.completed_at IS NULL) o"
def cleanup_cur(cut): return f"SELECT * FROM outbox_record WHERE status='COMPLETED' AND completed_at < TIMESTAMP '{cut}' ORDER BY completed_at"
def cleanup_new(cut): return cleanup_cur(cut) + " FETCH FIRST 1000 ROWS ONLY"
def cleanup_ids_cur(cut): return f"SELECT id FROM outbox_record WHERE status='COMPLETED' AND completed_at < TIMESTAMP '{cut}' ORDER BY completed_at"
def cleanup_ids_new(cut): return cleanup_ids_cur(cut) + " FETCH FIRST 1000 ROWS ONLY"
def failed_cur(): return f"SELECT * FROM outbox_record WHERE status='FAILED' AND next_retry_at < TIMESTAMP '{NOW} +00:00' ORDER BY next_retry_at"
def failed_new(): return failed_cur() + " FETCH FIRST 1000 ROWS ONLY"
