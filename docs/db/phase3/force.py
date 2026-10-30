# forced-index variants of the new query forms: python3 force.py <container> <client> <scenario> <C|S>
import subprocess, sys, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from queries import *
C, CL, SC, IDX = sys.argv[1:5]
OUT = os.path.dirname(os.path.abspath(__file__)) + f"/{'mysql' if CL=='mysql' else 'mariadb'}"
def sh(sql):
    r = subprocess.run(["docker","exec","-i",C,CL,"-uroot","-pp3","outbox","-B","-N"],input=sql,capture_output=True,text=True,timeout=900); return r.stdout, r.stderr
def timed(q, runs=4):
    s = "".join(f"SET @t=SYSDATE(6); {q};\nSELECT 'T', TIMESTAMPDIFF(MICROSECOND,@t,SYSDATE(6))/1000;\n" for _ in range(runs))
    o, e = sh(s)
    if "ERROR" in e: return None, e.splitlines()[-1][:160]
    t = [float(l.split("\t")[1]) for l in o.splitlines() if l.startswith("T\t")]; return sorted(t[1:])[1], None
ix = "idx_outbox_record_incomplete_created" if IDX == "C" else "idx_outbox_record_status_created"
mode = IDX
def force(q, alias_pat=r"FROM outbox_record o "):
    return q.replace("FROM outbox_record o ", f"FROM outbox_record o FORCE INDEX ({ix}) ", 1)
cases = {
 "force_keys_f_L100": force(new_keys_f(mode, 100)),
 "force_keys_n_L100": force(new_keys_n(mode, 100)),
 "force_keys_f_L1000": force(new_keys_f(mode, 1000)),
 "force_oldest_due_min_scan(no)": None,
 "oldest_created_limit1": f"SELECT created_at FROM outbox_record FORCE INDEX ({ix}) WHERE partition_no IN ({P}) AND status='NEW' AND completed_at IS NULL ORDER BY created_at LIMIT 1" if IDX=="C" else f"SELECT created_at FROM outbox_record FORCE INDEX ({ix}) WHERE partition_no IN ({P}) AND status='NEW' ORDER BY created_at LIMIT 1",
 "oldest_created_min_forced": f"SELECT MIN(created_at) FROM outbox_record FORCE INDEX ({ix}) WHERE partition_no IN ({P}) AND status='NEW' AND completed_at IS NULL",
}
for n, q in cases.items():
    if not q: continue
    t, e = timed(q); print(f"{SC}-{IDX} {n}: {'ERR '+e if e else f'{t:.1f}ms'}")
    pre = "EXPLAIN ANALYZE " if CL=="mysql" else "ANALYZE FORMAT=JSON "
    o, _ = sh(pre + q + ";"); open(f"{OUT}/plans/{SC}-{IDX}-{n}.txt","w").write(o)
# equivalence of forced full key result vs unforced
a,_ = sh(new_keys_f(mode,0)+";"); b,_ = sh(force(new_keys_f(mode,0))+";"); print("forced==unforced keys_f:", a==b)
