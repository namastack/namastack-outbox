import subprocess, sys, re, hashlib, json, os
sys.path.insert(0, os.path.dirname(__file__))
from queries import *
C, CL, SC = sys.argv[1], sys.argv[2], sys.argv[3]   # container, client, scenario
IDX = sys.argv[4]                                   # base | C | S
OUT = os.path.dirname(os.path.abspath(__file__)) + f"/{'mysql' if CL=='mysql' else 'mariadb'}"
def sh(sql, tmo=900):
    r = subprocess.run(["docker","exec","-i",C,CL,"-uroot","-pp3","outbox","-B","-N"],input=sql,capture_output=True,text=True,timeout=tmo)
    return r.stdout, r.stderr
def timed(q, runs=4):
    s = "".join(f"SET @t=SYSDATE(6); {q};\nSELECT 'T', TIMESTAMPDIFF(MICROSECOND,@t,SYSDATE(6))/1000;\n" for _ in range(runs))
    o, e = sh(s)
    if "ERROR" in e: return None, [l for l in e.splitlines() if "ERROR" in l][0][:160]
    t = [float(l.split("\t")[1]) for l in o.splitlines() if l.startswith("T\t")]
    return t, None
def rows(q):
    o, e = sh(q + ";")
    if "ERROR" in e: return None
    return o
def md5(o): return hashlib.md5(o.encode()).hexdigest()[:10] + "/set:" + hashlib.md5("\n".join(sorted(o.splitlines())).encode()).hexdigest()[:10] + f" n={len(o.splitlines())}"
def med(t): return sorted(t[1:])[len(t[1:])//2]  # drop first (cold), median of 3
res = {}
def run(name, q, equiv_against=None, explain=False):
    t, err = timed(q)
    if err: print(f"{name}: ERROR {err}"); res[name] = err; return
    print(f"{name}: med={med(t):.1f}ms all={t}"); res[name] = med(t)
    if explain:
        pre = "EXPLAIN ANALYZE " if CL == "mysql" else "ANALYZE FORMAT=JSON "
        o, e = sh(pre + q + ";")
        open(f"{OUT}/plans/{SC}-{IDX}-{name}.txt", "w").write(o if o.strip() else e)
os.makedirs(OUT + "/plans", exist_ok=True)
tag = f"{SC}-{IDX}"
mode = "C" if IDX in ("base","C") else "S"
print("== scenario", SC, "index set", IDX)
if IDX == "base":
    for n, q in [("cur_keys_f", cur_keys_f()), ("cur_keys_n", cur_keys_n()), ("cur_oldest_due", cur_oldest_due()), ("cur_oldest_created", cur_oldest_created())]:
        run(n, q, explain=True)
    ks = rows(cur_keys_f()).split()
    for kn in (100, 1000):
        keys = ks[:kn]
        run(f"cur_pages{kn}", cur_pages(keys), explain=(kn==100))
    o,_ = sh("SELECT completed_at FROM outbox_record WHERE status='COMPLETED' ORDER BY completed_at LIMIT 1 OFFSET 99380;"); cut = o.strip()
    print("cut", cut)
    run("cur_cleanup", cur_cleanup(cut), explain=True)
    run("cur_cleanup_ids", cur_cleanup_ids(cut))
    run("cur_failed_before", cur_failed_before())
    # references for equivalence (md5 of ordered result)
    ref = {"keys_f": md5(rows(cur_keys_f())), "keys_n": md5(rows(cur_keys_n())), "oldest_due": rows(cur_oldest_due()).strip(), "oldest_created": rows(cur_oldest_created()).strip()}
    json.dump({"ref": ref, "cut": cut}, open(f"{OUT}/ref-{SC}.json", "w"))
else:
    ref = json.load(open(f"{OUT}/ref-{SC}.json")); cut = ref["cut"]; ref = ref["ref"]
    for lim in (100,):
        run(f"new_keys_f_L{lim}", new_keys_f(mode, lim), explain=True)
        run(f"new_keys_n_L{lim}", new_keys_n(mode, lim), explain=True)
    # equivalence: full ordered (no limit) -> distinct keys in order == current ordered keys? head-of-key output is one row per key -> compare sets/order
    for nm, q, rf in (("keys_f", new_keys_f(mode, 0), ref["keys_f"]), ("keys_n", new_keys_n(mode, 0), ref["keys_n"])):
        o = rows(q); print("equiv", nm, md5(o), "ref", rf)
    # order-of-keys: current orders by MIN(created_at) with ties ambiguous; compare as sequences
    run("new_oldest_due", new_oldest_due(mode), explain=True)
    print("equiv oldest_due", rows(new_oldest_due(mode)).strip(), "ref", ref["oldest_due"])
    run("new_oldest_created", new_oldest_created(), explain=True)
    print("equiv oldest_created", rows(new_oldest_created()).strip(), "ref", ref["oldest_created"])
    ks = rows(cur_keys_f()).split()
    for kn in (100, 1000):
        keys = ks[:kn]
        cur = rows(cur_pages(keys))
        run(f"in_pages{kn}", in_pages(keys, mode), explain=(kn==100))
        o = rows(in_pages(keys, mode)); print(f"equiv in_pages{kn}", md5(o), "cur", md5(cur))
        if CL == "mysql":
            run(f"lat_pages{kn}", lat_pages(keys, mode), explain=(kn==100))
            o = rows(lat_pages(keys, mode));
            # per-key order only: sort by key stable? compare sorted-by-key-then-created
            import collections
            def pk(txt):
                d = collections.defaultdict(list)
                for l in txt.splitlines(): f=l.split("\t"); d[f[2]].append(f[0])
                return d
            print(f"equiv lat_pages{kn} per-key order equal:", pk(o) == pk(cur) if o else None)
    run("new_cleanup", new_cleanup(cut), explain=True)
    run("new_cleanup_ids", new_cleanup_ids(cut))
    run("new_failed_before", new_failed_before())
