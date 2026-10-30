import subprocess, sys, hashlib, json, os, re
D = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, D)
from queries import *
SC, IDX = sys.argv[1], sys.argv[2]      # scenario A|B ; index set base|F|C
FULL = len(sys.argv) > 3                 # include extra variants
def sh(sql, tmo=900):
    r = subprocess.run([f"{D}/sq.sh"], input=sql, capture_output=True, text=True, timeout=tmo)
    return r.stdout, r.stderr
def err(o): 
    m = re.search(r"Msg \d+.*", o); return m.group(0)[:200] if m else None
def timed(q, runs=4):
    # single-run timings first (SYSDATETIME tick is ~4 ms on Linux); if fast, time a 20-rep loop and divide
    s = "SET NOCOUNT ON;\nDECLARE @t datetime2(7);\n" + "".join(f"SET @t=SYSDATETIME();\n{q};\nSELECT 'T', DATEDIFF_BIG(microsecond,@t,SYSDATETIME())/1000.0;\n" for _ in range(runs))
    o, e = sh(s)
    if err(o + e): return None, err(o + e)
    t = [float(l.split("|")[1]) for l in o.splitlines() if l.startswith("T|")]
    if sorted(t[1:])[1] < 60:
        R = 20 if len(o) < 200000 else 5
        s = "SET NOCOUNT ON;\nDECLARE @t datetime2(7), @i int;\n" + "".join(f"SET @i=0; SET @t=SYSDATETIME();\nWHILE @i<{R} BEGIN\n{q};\nSET @i+=1; END\nSELECT 'T', DATEDIFF_BIG(microsecond,@t,SYSDATETIME())/1000.0/{R};\n" for _ in range(4))
        o, e = sh(s); t = [float(l.split("|")[1]) for l in o.splitlines() if l.startswith("T|")]
    return t, None
def rows(q):
    o, e = sh("SET NOCOUNT ON;\n" + q + ";")
    if err(o + e): return None
    return o
def h(o):
    L = [l for l in o.splitlines() if l.strip()][2:] if o else []   # drop header+underline
    return hashlib.md5("\n".join(L).encode()).hexdigest()[:8] + "/set:" + hashlib.md5("\n".join(sorted(L)).encode()).hexdigest()[:8] + f" n={len(L)}"
def med(t): return sorted(t[1:])[len(t[1:]) // 2]
os.makedirs(D + "/plans", exist_ok=True)
def run(name, q, explain=True):
    t, e = timed(q)
    if e: print(f"{name}: ERROR {e}"); return
    print(f"{name}: med={med(t):.2f}ms all={[round(x,2) for x in t]}", flush=True)
    if explain:
        o, e2 = sh("SET NOCOUNT ON;\nSET STATISTICS IO ON;\nSET STATISTICS PROFILE ON;\n" + q + ";")
        # keep only plan lines (StmtText column) + IO line
        keep = [l for l in o.splitlines() if "|" in l and ("|PhysicalOp" in l or re.search(r"\|(Index|Clustered|Table|Nested|Sort|Top|Stream|Hash|Compute|Filter|Constant|Merge|Concat|Parallelism|Segment|Sequence|Assert|Key|Lazy|Row|Adaptive)", l)) or l.startswith("Table '")]
        open(f"{D}/plans/{SC}-{IDX}-{name}.txt", "w").write("\n".join(l[:400] for l in (o + e2).splitlines() if l.startswith("Table '") or re.search(r"\|--|^\s*\|--|StmtText", l) or "|--" in l))
print("== scenario", SC, "index set", IDX)
refp = f"{D}/ref-{SC}.json"
if IDX == "base":
    run("cur_keys_f", keys_cur(True)); run("cur_keys_n", keys_cur(False))
    run("cur_keys_f_param", keys_cur(True, True))
    run("cur_oldest_due", oldest_due_cur()); run("cur_oldest_due_param", oldest_due_cur(True))
    run("cur_oldest_created", oldest_created_cur())
    ks = [l for l in rows(keys_cur(True)).splitlines()[2:] if l.strip()]
    ks = ks + [f'order-{i}' for i in range(1, 2000) if f'order-{i}' not in set(ks)]   # pad to 1000 keys (A has <1000 due keys at this scale)
    for kn in (100, 1000): run(f"cur_pages{kn}", pages_cur(ks[:kn]), explain=(kn == 100))
    cut = rows("SELECT completed_at FROM outbox_record WHERE status='COMPLETED' ORDER BY completed_at OFFSET 19876 ROWS FETCH NEXT 1 ROWS ONLY").splitlines()[2].strip()
    print("cut", cut)
    run("cur_cleanup", cleanup_cur(cut)); run("cur_cleanup_ids", cleanup_ids_cur(cut)); run("cur_failed_before", failed_cur())
    ref = {"keys_f": h(rows(keys_cur(True))), "keys_n": h(rows(keys_cur(False))), "keys_f_list": rows(keys_cur(True)),
           "oldest_due": rows(oldest_due_cur()).splitlines()[2].strip(), "oldest_created": rows(oldest_created_cur()).splitlines()[2].strip()}
    json.dump({"ref": ref, "cut": cut}, open(refp, "w"))
else:
    R = json.load(open(refp)); cut = R["cut"]; ref = R["ref"]
    for form in (("top", "fetch") if FULL else ("top",)):
        run(f"new_keys_f_{form}100", keys_new(True, 100, form)); run(f"new_keys_n_{form}100", keys_new(False, 100, form))
    HINT = "outbox_record earlier WITH (INDEX(idx_outbox_record_record_key_completed_created))"
    run("new_keys_n_top100_probehint", keys_new(False, 100).replace("outbox_record earlier", HINT)); run("new_keys_n_top100_probehint_param", keys_new(False, 100, "top", True).replace("outbox_record earlier", HINT))
    print("equiv keys_n probehint", h(rows(keys_new(False, 0).replace("outbox_record earlier", HINT))), "ref", ref["keys_n"])
    run("new_keys_f_top100_param", keys_new(True, 100, "top", True)); run("new_keys_f_fetch100_param", keys_new(True, 100, "fetch", True))
    if FULL: run("new_keys_f_top100_nofilt", keys_new(True, 100, "top", False, filt=False))
    for nm, q, rf in (("keys_f", keys_new(True, 0), ref["keys_f"]), ("keys_n", keys_new(False, 0), ref["keys_n"])):
        print("equiv", nm, h(rows(q)), "ref", rf)
    # sequence equality of the key order vs current
    cur_list = [l for l in ref["keys_f_list"].splitlines()[2:] if l.strip()]
    new_list = [l for l in rows(keys_new(True, 0)).splitlines()[2:] if l.strip()]
    cw = [l.split("|") for l in rows(keys_cur(True).replace("SELECT o.record_key", "SELECT o.record_key, MIN(o.created_at)")).splitlines()[2:] if "|" in l]
    nw = [l.split("|") for l in rows(keys_new(True, 0).replace("SELECT o.record_key", "SELECT o.record_key, o.created_at")).splitlines()[2:] if "|" in l]
    print("keys_f created_at sequence equal (order equal up to ties):", [x[1] for x in cw] == [x[1] for x in nw], len(cw), len(nw))
    print("keys_f same set:", sorted(cur_list) == sorted(new_list), " same order:", cur_list == new_list, len(cur_list), len(new_list))
    run("new_oldest_due", oldest_due_new()); run("new_oldest_due_param", oldest_due_new(True))
    print("equiv oldest_due", rows(oldest_due_new()).splitlines()[2].strip(), "ref", ref["oldest_due"])
    run("new_oldest_created", oldest_created_new()); run("new_oldest_created_top1", oldest_created_top1())
    print("equiv oldest_created", rows(oldest_created_new()).splitlines()[2].strip(), "ref", ref["oldest_created"])
    ks = list(cur_list); ss = set(ks); ks = ks + [f'order-{i}' for i in range(1, 2000) if f'order-{i}' not in ss]
    import collections
    def pk(txt):
        d = collections.defaultdict(list)
        for l in [x for x in txt.splitlines()[2:] if x.strip() and "|" in x]: f = l.split("|"); d[f[2]].append(f[0])
        return d
    for kn in (100, 1000):
        keys = ks[:kn]; cur = rows(pages_cur(keys))
        run(f"in_pages{kn}", pages_in(keys), explain=(kn == 100))
        print(f"equiv in_pages{kn}", h(rows(pages_in(keys))), "cur", h(cur))
        run(f"apply_pages{kn}", pages_apply(keys), explain=(kn == 100))
        print(f"equiv apply_pages{kn} per-key order equal:", pk(rows(pages_apply(keys))) == pk(cur))
        run(f"applyord_pages{kn}", pages_apply_ord(keys), explain=(kn == 100))
        print(f"equiv applyord_pages{kn} per-key order equal:", pk(rows(pages_apply_ord(keys))) == pk(cur))
        run(f"in_pages{kn}_param", pages_in_par(keys), explain=False)
    run("new_cleanup", cleanup_new(cut)); run("new_cleanup_ids", cleanup_ids_new(cut)); run("new_failed_before", failed_new())
