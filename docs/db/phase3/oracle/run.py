import subprocess, sys, re, os, json, hashlib
D = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, D)
from queries import *
SC, IDX = sys.argv[1], sys.argv[2]
def sh(sql, tmo=1200):
    r = subprocess.run([f"{D}/sq.sh"], input=sql, capture_output=True, text=True, timeout=tmo); return r.stdout + r.stderr
HDR = "set pagesize 0 linesize 400 trimspool on long 100000 feedback only timing on\nalter session set time_zone='UTC';\nalter session set nls_timestamp_tz_format='YYYY-MM-DD HH24:MI:SS.FF6 TZH:TZM';\n"
def binds():
    s = "".join(f"variable p{i} number\nexec :p{i}:={i}\n" for i in PL)
    return s + f"variable st varchar2(20)\nvariable nowc varchar2(40)\nvariable n number\nexec :st:='NEW'; :nowc:='{NOW} +00:00'; :n:=100\n"
def err(o):
    m = re.search(r"(ORA|SP2)-\d+.*", o); return m.group(0) if m else None
def el(o): 
    return [int(a)*3600+int(b)*60+float(c) for a,b,c in re.findall(r"Elapsed: (\d+):(\d+):([\d.]+)", o)]
def plan(q, tag, uses_bind=True):
    qh = q.replace("SELECT /*+ ", "SELECT /*+ gather_plan_statistics ", 1) if q.startswith("SELECT /*+ ") else re.sub(r"^SELECT", "SELECT /*+ gather_plan_statistics */", q, count=1)
    o = sh(HDR.replace("feedback only", "feedback only") + (binds() if uses_bind else "") + qh + ";\nset feedback off timing off\nset termout on\nselect * from table(dbms_xplan.display_cursor(null,null,'ALLSTATS LAST +PEEKED_BINDS'));\n")
    i = o.find("SQL_ID"); o = o[i:] if i >= 0 else o
    o = re.sub(r"\n(Elapsed|PL/SQL procedure|Session altered)[^\n]*\n?", "\n", o); o = re.sub(r"\n{3,}", "\n\n", o)
    open(f"{D}/plans/{SC}-{IDX}-{tag}.txt", "w").write(o[:6000])
    return o
def loop_ms(name, q_pl, R=20, decl="", fetch=True):
    """timed in PL/SQL with real bind variables (PL/SQL variables), R reps, 3 passes, median ms/rep"""
    vars_ = "".join(f"p{i} number:={i}; " for i in PL) + f"st varchar2(20):='NEW'; now timestamp with time zone:=timestamp '{NOW} +00:00'; n number:=100; t0 number; res sys.odcinumberlist:=sys.odcinumberlist(); x varchar2(4000);"
    blk = f"""set serveroutput on feedback off timing off
declare {vars_}
begin
 for pass in 1..4 loop
  t0:=dbms_utility.get_time;
  for i in 1..{R} loop for r in ({q_pl}) loop null; end loop; end loop;
  dbms_output.put_line('T|'||(dbms_utility.get_time-t0)*10/{R});
 end loop;
end;
/
"""
    o = sh(HDR + blk)
    if err(o) or "PLS-" in o: print(f"{name}: ERROR {err(o) or o[:300]}"); return None
    t = [float(l.split("|")[1]) for l in o.splitlines() if l.startswith("T|")]
    m = sorted(t[1:])[1]
    print(f"{name}: med={m:.2f}ms all={t}", flush=True); return m
def timed_sp(name, q, uses_bind=False, n=4):
    o = sh(HDR + (binds() if uses_bind else "") + (q + ";\n") * n)
    if err(o): print(f"{name}: ERROR {err(o)}"); return None
    t = el(o)[-n:]; m = sorted(t[1:])[len(t[1:])//2] * 1000
    print(f"{name}: med={m:.0f}ms all={[round(x*1000) for x in t]}", flush=True); return m
def rows(q):
    o = sh("set pagesize 0 linesize 400 trimspool on feedback off timing off\nalter session set time_zone='UTC';\nalter session set nls_timestamp_tz_format='YYYY-MM-DD HH24:MI:SS.FF6 TZH:TZM';\nset long 100\nset termout on\n" + q + ";\n")
    return [l for l in o.splitlines() if l.strip() and not l.startswith("Session")]
def hh(L): return hashlib.md5("\n".join(L).encode()).hexdigest()[:8] + "/set:" + hashlib.md5("\n".join(sorted(L)).encode()).hexdigest()[:8] + f" n={len(L)}"
os.makedirs(D + "/plans", exist_ok=True)
refp = f"{D}/ref-{SC}.json"
LIT, SP, PLR = R("lit"), R("sp"), R("pl")

def hot(tag, qfn_args_list):
    pass
print("== scenario", SC, "index set", IDX)
if IDX == "base":
    loop_ms("cur_keys_f", keys_cur(PLR)); plan(keys_cur(SP), "cur_keys_f")
    loop_ms("cur_keys_n", keys_cur(PLR, False)); plan(keys_cur(SP, False), "cur_keys_n")
    loop_ms("cur_oldest_due", oldest_due_cur(PLR)); plan(oldest_due_cur(SP), "cur_oldest_due")
    timed_sp("cur_oldest_created", oldest_created_cur()); plan(oldest_created_cur(), "cur_oldest_created", False)
    kk = rows(keys_cur(LIT)); ks = list(kk) + [f'order-{i}' for i in range(1, 2000) if f'order-{i}' not in set(kk)]
    for kn in (100, 1000): timed_sp(f"cur_pages{kn}", pages_cur(ks[:kn]))
    plan(pages_cur(ks[:100]), "cur_pages100", False)
    cut = rows("SELECT to_char(completed_at,'YYYY-MM-DD HH24:MI:SS.FF6') FROM outbox_record WHERE status='COMPLETED' ORDER BY completed_at OFFSET 19876 ROWS FETCH NEXT 1 ROWS ONLY")[0].strip()
    print("cut", cut)
    timed_sp("cur_cleanup", cleanup_cur(cut)); timed_sp("cur_cleanup_ids", cleanup_ids_cur(cut)); timed_sp("cur_failed_before", failed_cur())
    plan(cleanup_cur(cut), "cur_cleanup", False)
    ref = {"keys_f": hh(rows(keys_cur(LIT))), "keys_f_list": rows(keys_cur(LIT)), "keys_n": hh(rows(keys_cur(LIT, False))),
           "oldest_due": rows(oldest_due_cur(LIT)), "oldest_created": rows(oldest_created_cur())}
    json.dump({"ref": ref, "cut": cut}, open(refp, "w"))
else:
    Rf = json.load(open(refp)); cut = Rf["cut"]; ref = Rf["ref"]
    if IDX.startswith("P"):    # function-based
        cov = IDX.startswith("Pc")
        loop_ms("new_keys_f_fetch100", keys_fbi(PLR, cov)); plan(keys_fbi(SP, cov), "new_keys_f_fetch100")
        print("equiv keys_f", hh(rows(keys_fbi(LIT, cov, lim=False))), "ref", ref["keys_f"])
        cwt = rows(keys_cur(LIT).replace("SELECT o.record_key", "SELECT o.record_key, MIN(o.created_at)", 1)); nwt = rows(keys_fbi(LIT, cov, lim=False, extra=", " + E("created_at") if cov else ", o.created_at"))
        print("keys_f created_at sequence equal (ties aside):", [l.split()[1:] for l in cwt] == [l.split()[1:] for l in nwt], len(cwt), len(nwt))
        loop_ms("new_oldest_due", oldest_due_new(PLR, "S")); plan(oldest_due_new(SP, "S"), "new_oldest_due")   # status/next_retry_at order via idx_outbox_rec_s_r
        print("equiv oldest_due", rows(oldest_due_new(LIT, "S")), "ref", ref["oldest_due"])
        timed_sp("new_oldest_created", oldest_created_fbi()); plan(oldest_created_fbi(), "new_oldest_created", False)
        print("equiv oldest_created", rows(oldest_created_fbi()), "ref", ref["oldest_created"])
    else:
        M = "C" if IDX == "C" else "S"; COMP = (M == "C")
        K = lambda rr, f=True, **kw: keys_new(rr, f, comp=COMP, mode=M, **kw)
        loop_ms("new_keys_f_fetch100", K(PLR)); plan(K(SP), "new_keys_f_fetch100")
        loop_ms("new_keys_f_rownum100", K(PLR, fetch="rownum")); plan(K(SP, fetch="rownum"), "new_keys_f_rownum100")
        loop_ms("new_keys_n_fetch100", K(PLR, False)); plan(K(SP, False), "new_keys_n_fetch100")
        timed_sp("new_keys_f_fetch100_literal", K(LIT))
        print("equiv keys_f", hh(rows(K(LIT, lim=False))), "ref", ref["keys_f"])
        print("equiv keys_n", hh(rows(K(LIT, False, lim=False))), "ref", ref["keys_n"])
        cw = ref["keys_f_list"]; nw = rows(K(LIT, lim=False))
        print("keys_f same set:", sorted(cw) == sorted(nw), " same order:", cw == nw, len(cw), len(nw))
        seq = lambda L: [l.split()[-3] + " " + l.split()[-2] for l in L]
        cwt = rows(keys_cur(LIT).replace("SELECT o.record_key", "SELECT o.record_key, MIN(o.created_at)", 1)); nwt = rows(K(LIT, lim=False, extra=", o.created_at"))
        print("keys_f created_at sequence equal (order equal up to ties):", [l.split()[1:] for l in cwt] == [l.split()[1:] for l in nwt], len(cwt), len(nwt))
        loop_ms("new_oldest_due", oldest_due_new(PLR, M)); plan(oldest_due_new(SP, M), "new_oldest_due")
        print("equiv oldest_due", rows(oldest_due_new(LIT, M)), "ref", ref["oldest_due"])
        timed_sp("new_oldest_created", oldest_created_new(M)); plan(oldest_created_new(M), "new_oldest_created", False)
        print("equiv oldest_created", rows(oldest_created_new(M)), "ref", ref["oldest_created"])
    if not IDX.startswith("P"):
        cur_list = ref["keys_f_list"]; ks = list(cur_list) + [f'order-{i}' for i in range(1, 2000) if f'order-{i}' not in set(cur_list)]
        for kn in (100, 1000):
            keys = ks[:kn]; cur = rows(pages_cur(keys))
            timed_sp(f"in_pages{kn}", pages_in(keys)); print(f"equiv in_pages{kn}", hh(rows(pages_in(keys))), "cur", hh(cur))
            timed_sp(f"lat_odci_pages{kn}", pages_lat(keys))
            timed_sp(f"lat2_odci_pages{kn}", pages_lat2(keys))
        plan(pages_in(ks[:100]), "in_pages100", False); plan(pages_lat(ks[:100]), "lat_odci_pages100", False)
        timed_sp("in_pages1001_expect_ORA-01795", pages_in(ks[:1001]))
        timed_sp("new_cleanup", cleanup_new(cut)); timed_sp("new_cleanup_ids", cleanup_ids_new(cut)); timed_sp("new_failed_before", failed_new())
        plan(cleanup_new(cut), "new_cleanup", False)
