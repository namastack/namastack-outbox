#!/usr/bin/env python3
"""Noise report: run1 vs run2 (label and label-r2) per variant/query/scenario. usage: bin/noise.py <results root> <dialect>"""
import csv, os, sys, glob, re
root, dia = sys.argv[1], sys.argv[2]
H = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
grp = {}
for f in glob.glob(f"{H}/catalog/*/{dia}/*.sql"):
    for l in open(f):
        if l.startswith("-- group:"): grp[os.path.basename(f)[:-4]] = l.split(":")[1].strip(); break
grp.setdefault("count_statuses_all", "MON")
def grp_of(q): return grp.get(q, "WRITE" if q.startswith("upd_") else "?")
def load(p):
    d = {}
    if os.path.exists(p):
        for r in csv.DictReader(open(p)):
            try: d[r["query_id"]] = float(r["median_ms"])
            except: pass
    return d
rows = []  # variant, scenario, query, group, r1, r2, spread
for v in ("main", "v1"):
    for d in sorted(glob.glob(f"{root}/{v}/{dia}/*")):
        sc = os.path.basename(d)
        if sc.endswith("-r2") or not os.path.exists(d + "/timings.csv"): continue
        a, b = load(d + "/timings.csv"), load(d + "-r2/timings.csv")
        for q in a:
            if q in b and a[q] > 0 and b[q] > 0:
                rows.append((v, sc, q, grp_of(q), a[q], b[q], abs(a[q]-b[q]) / min(a[q], b[q])))
def pct(xs, p):
    xs = sorted(xs); 
    if not xs: return float("nan")
    k = (len(xs)-1)*p; i = int(k); j = min(i+1, len(xs)-1); return xs[i] + (xs[j]-xs[i])*(k-i)
out = [f"# Noise ({dia}): run 1 vs run 2 (cold restart), median ms\n",
       "Spread = |r1-r2| / min(r1,r2). Group statistics exclude queries with min(r1,r2) < 0.3 ms (listed separately).\n",
       "## Per group\n", "| variant | group | n | max spread | p90 spread | worst (scenario/query) |", "|---|---|---|---|---|---|"]
for v in ("main", "v1", "both"):
    for g in ("HOT", "WRITE", "MON", "RET", "ADMIN"):
        rs = [r for r in rows if (v == "both" or r[0] == v) and r[3] == g and min(r[4], r[5]) >= 0.3]
        if not rs: continue
        w = max(rs, key=lambda r: r[6])
        out.append(f"| {v} | {g} | {len(rs)} | {w[6]*100:.0f}% | {pct([r[6] for r in rs], .9)*100:.0f}% | {w[1]}/{w[2]} ({w[4]:.2f} vs {w[5]:.2f} ms) |")
out += ["", "## Queries below 0.3 ms: p90 of the absolute difference |r1-r2| (ms) per group\n", "| variant | group | n | p90 abs diff ms | max abs diff ms |", "|---|---|---|---|---|"]
for v in ("main", "v1", "both"):
    for g in ("HOT", "WRITE", "MON", "RET", "ADMIN"):
        rs = [r for r in rows if (v == "both" or r[0] == v) and r[3] == g and min(r[4], r[5]) < 0.3]
        if rs: out.append(f"| {v} | {g} | {len(rs)} | {pct([abs(r[4]-r[5]) for r in rs], .9):.3f} | {max(abs(r[4]-r[5]) for r in rs):.3f} |")
out += ["", "## Queries below 0.3 ms (excluded from group statistics)\n", "| variant | scenario | query | group | r1 | r2 | spread |", "|---|---|---|---|---|---|---|"]
for r in rows:
    if min(r[4], r[5]) < 0.3: out.append(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]:.3f} | {r[5]:.3f} | {r[6]*100:.0f}% |")
out += ["", "## All queries\n", "| variant | scenario | query | group | run1 ms | run2 ms | spread |", "|---|---|---|---|---|---|---|"]
for r in sorted(rows, key=lambda r: (r[1], r[2], r[0])):
    out.append(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]:.3f} | {r[5]:.3f} | {r[6]*100:.0f}% |")
open(f"{root}/noise-{dia}.md", "w").write("\n".join(out) + "\n")
print("\n".join(out[:20]))
