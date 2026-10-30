# T-SQL query templates, SQL Server phase 3. P = owned partitions 0..63.
NOW = "2026-10-01 12:00:00"
PL = list(range(64))
P = ",".join(str(i) for i in PL)
def prm(sql_body, extra_decl="", extra_vals=""):
    """wrap a body using @p0..@p63,@st,@now,@n into sp_executesql (the way the JDBC driver parameterizes)"""
    decl = ",".join(f"@p{i} int" for i in PL) + ",@st varchar(20),@now datetime2(6),@n int" + extra_decl
    vals = ",".join(f"@p{i}={i}" for i in PL) + f",@st='NEW',@now='{NOW}',@n=100" + extra_vals
    return f"EXEC sp_executesql N'{sql_body.replace(chr(39), chr(39)*2)}', N'{decl}', {vals}"
PH = ",".join(f"@p{i}" for i in PL)

def keys_cur(f, par=False):
    inner = """AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))""" if f else ""
    pl, st, now = (PH, "@st", "@now") if par else (P, "'NEW'", f"'{NOW}'")
    b = f"SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN ({pl}) AND o.status={st} AND o.next_retry_at<={now} {inner} GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC"
    return prm(b) if par else b

def keys_new(f, lim, form="top", par=False, filt=True):
    # lim: int or 0 (no limit). form: top | fetch
    pl, st, now = (PH, "@st", "@now") if par else (P, "'NEW'", f"'{NOW}'")
    n = "@n" if par else str(lim)
    outer_c = "AND o.completed_at IS NULL" if filt else ""
    if f:
        probe = """AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND older.created_at<=o.created_at AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))"""
    else:
        probe = """AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key=o.record_key AND earlier.completed_at IS NULL AND earlier.status='NEW' AND earlier.next_retry_at<=""" + now + """
 AND earlier.created_at<=o.created_at AND (earlier.created_at<o.created_at OR (earlier.created_at=o.created_at AND earlier.id<o.id)))"""
    top = f"TOP ({n}) " if (lim and form == "top") else ""
    fetch = f" OFFSET 0 ROWS FETCH NEXT {n} ROWS ONLY" if (lim and form == "fetch") else ""
    b = f"SELECT {top}o.record_key FROM outbox_record o WHERE o.partition_no IN ({pl}) AND o.status={st} AND o.next_retry_at<={now} {outer_c} {probe} ORDER BY o.created_at ASC, o.id ASC{fetch}"
    return prm(b) if par else b

def oldest_due_cur(par=False):
    pl, st, now = (PH, "@st", "@now") if par else (P, "'NEW'", f"'{NOW}'")
    b = f"""SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN ({pl}) AND o.status={st} AND o.next_retry_at<={now}
AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))"""
    return prm(b) if par else b
def oldest_due_new(par=False):
    pl, st, now = (PH, "@st", "@now") if par else (P, "'NEW'", f"'{NOW}'")
    b = f"""SELECT (SELECT TOP (1) o.next_retry_at FROM outbox_record o WHERE o.partition_no IN ({pl}) AND o.status={st} AND o.next_retry_at<={now} AND o.completed_at IS NULL
AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key=o.record_key AND older.completed_at IS NULL
 AND older.created_at<=o.created_at AND (older.created_at<o.created_at OR (older.created_at=o.created_at AND older.id<o.id)))
ORDER BY o.next_retry_at ASC)"""
    return prm(b) if par else b

def oldest_created_cur(): return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({P}) AND status='NEW'"
def oldest_created_new(): return f"SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN ({P}) AND status='NEW' AND completed_at IS NULL"
def oldest_created_top1(): return f"SELECT TOP (1) created_at FROM outbox_record WHERE partition_no IN ({P}) AND status='NEW' AND completed_at IS NULL ORDER BY created_at"

def kl(keys): return ",".join("'%s'" % x for x in keys)
def pages_cur(keys): return f"SELECT * FROM outbox_record WHERE record_key IN ({kl(keys)}) AND status='NEW' ORDER BY record_key, created_at, id"
def pages_in(keys): return f"SELECT * FROM outbox_record WHERE record_key IN ({kl(keys)}) AND status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id"
def pages_apply(keys):
    v = ",".join("('%s')" % x for x in keys)
    return f"SELECT o.* FROM (VALUES {v}) k(k) CROSS APPLY (SELECT r.* FROM outbox_record r WHERE r.record_key=k.k AND r.status='NEW' AND r.completed_at IS NULL) o"
def pages_apply_ord(keys):
    v = ",".join("('%s')" % x for x in keys)
    return f"SELECT o.* FROM (VALUES {v}) k(k) CROSS APPLY (SELECT TOP (100000) r.* FROM outbox_record r WHERE r.record_key=k.k AND r.status='NEW' AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id) o"
def pages_in_par(keys):
    ph = ",".join(f"@k{i}" for i in range(len(keys)))
    decl = ",".join(f"@k{i} varchar(255)" for i in range(len(keys)))
    vals = ",".join(f"@k{i}='{k}'" for i, k in enumerate(keys))
    b = f"SELECT * FROM outbox_record WHERE record_key IN ({ph}) AND status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id"
    b = b.replace(chr(39), chr(39) * 2)
    return f"EXEC sp_executesql N'{b}', N'{decl}', {vals}".replace(",@k", ",\n@k")   # sqlcmd chokes on very long input lines

def cleanup_cur(cut): return f"SELECT * FROM outbox_record WHERE status='COMPLETED' AND completed_at < '{cut}' ORDER BY completed_at"
def cleanup_new(cut): return f"SELECT TOP (1000) * FROM outbox_record WHERE status='COMPLETED' AND completed_at < '{cut}' ORDER BY completed_at"
def cleanup_ids_cur(cut): return f"SELECT id FROM outbox_record WHERE status='COMPLETED' AND completed_at < '{cut}' ORDER BY completed_at"
def cleanup_ids_new(cut): return f"SELECT TOP (1000) id FROM outbox_record WHERE status='COMPLETED' AND completed_at < '{cut}' ORDER BY completed_at"
def failed_cur(): return f"SELECT * FROM outbox_record WHERE status='FAILED' AND next_retry_at < '{NOW}' ORDER BY next_retry_at"
def failed_new(): return f"SELECT TOP (1000) * FROM outbox_record WHERE status='FAILED' AND next_retry_at < '{NOW}' ORDER BY next_retry_at"
