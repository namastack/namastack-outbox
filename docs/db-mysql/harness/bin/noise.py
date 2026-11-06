#!/usr/bin/env python3
"""Noise report: compares any two result trees (pass 1 vs pass 2, solo vs loaded, ...) per variant, label and query group.

usage: bin/noise.py <tree1> <tree2> [--dialect mysql] [--small-ms 0.3] [--out FILE] [--labels S8-S2,S8-S4] [--variants a,b] [--csv timings.csv]

A tree is a results directory with <variant>/<dialect>/<label>/timings.csv (e.g. results/calibration/pass1).
Pairs: same variant and label in both trees (use --map old=new to pair differently named variants/labels, repeatable).
Spread = |m1 - m2| / min(m1, m2) of the medians. Per group (HOT/WRITE/MON/RET/ADMIN from the catalog headers):
  - queries with min(m1, m2) >= --small-ms: n, p90 and max relative spread, worst query;
  - queries below --small-ms: n, p90 and max absolute difference in ms (relative spread is meaningless there).
Output: markdown, written to --out (default <tree2>/noise-<dialect>.md) and the summary tables printed.
"""
import argparse, csv, glob, os

ap = argparse.ArgumentParser()
ap.add_argument("tree1"); ap.add_argument("tree2")
ap.add_argument("--dialect", default="mysql")
ap.add_argument("--small-ms", type=float, default=0.3)
ap.add_argument("--out")
ap.add_argument("--labels", help="comma list; default: all labels present in both trees")
ap.add_argument("--variants", help="comma list; default: all variants present in both trees")
ap.add_argument("--file", default="timings.csv", help="csv name inside the label dir (timings.csv, or repo-jdbc/timings.csv)")
ap.add_argument("--map", action="append", default=[], help="variant or label rename tree1=tree2")
o = ap.parse_args()
H = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUPS = ("HOT", "WRITE", "MON", "RET", "ADMIN")
grp = {}
for f in glob.glob(f"{H}/catalog/*/{o.dialect}/*.sql"):
    for l in open(f):
        if l.startswith("-- group:"):
            grp[os.path.basename(f)[:-4]] = l.split(":")[1].strip(); break
grp.setdefault("count_statuses_all", "MON")
rename = dict(m.split("=", 1) for m in o.map)
def grp_of(q): return grp.get(q, "WRITE" if q.startswith("upd_") else "?")

def load(p):
    d = {}
    if os.path.exists(p):
        for r in csv.DictReader(open(p)):
            try: d[r["query_id"]] = float(r["median_ms"])
            except (ValueError, KeyError, TypeError): pass
    return d

def cells(tree):
    out = {}
    for f in glob.glob(f"{tree}/*/{o.dialect}/*/{o.file}"):
        parts = f[len(tree):].strip("/").split("/")
        out[(parts[0], parts[2])] = f
    return out

c1, c2 = cells(o.tree1), cells(o.tree2)
want_v = set(o.variants.split(",")) if o.variants else None
want_l = set(o.labels.split(",")) if o.labels else None
rows = []   # variant, label, query, group, m1, m2, spread
missing = []
for (v, lab), f1 in sorted(c1.items()):
    if (want_v and v not in want_v) or (want_l and lab not in want_l): continue
    k2 = (rename.get(v, v), rename.get(lab, lab))
    if k2 not in c2: missing.append(f"{v}/{lab}"); continue
    a, b = load(f1), load(c2[k2])
    for q in a:
        if q in b and a[q] > 0 and b[q] > 0:
            rows.append((v, lab, q, grp_of(q), a[q], b[q], abs(a[q] - b[q]) / min(a[q], b[q])))

def pct(xs, p):
    xs = sorted(xs)
    if not xs: return float("nan")
    k = (len(xs) - 1) * p; i = int(k); j = min(i + 1, len(xs) - 1); return xs[i] + (xs[j] - xs[i]) * (k - i)

variants = sorted({r[0] for r in rows})
out = [f"# Noise ({o.dialect}): `{o.tree1}` vs `{o.tree2}`, median ms\n",
       f"Spread = |m1-m2| / min(m1,m2). Group statistics use queries with min(m1,m2) >= {o.small_ms} ms; smaller ones are listed as absolute differences.\n"]
if missing: out.append("Cells missing in tree2: " + ", ".join(missing) + "\n")
out += ["## Relative spread per group\n", "| variant | group | n | p90 spread | max spread | worst (label/query) |", "|---|---|---|---|---|---|"]
for v in variants + ["all"]:
    for g in GROUPS:
        rs = [r for r in rows if (v == "all" or r[0] == v) and r[3] == g and min(r[4], r[5]) >= o.small_ms]
        if not rs: continue
        w = max(rs, key=lambda r: r[6])
        out.append(f"| {v} | {g} | {len(rs)} | {pct([r[6] for r in rs], .9)*100:.0f}% | {w[6]*100:.0f}% | {w[1]}/{w[2]} ({w[4]:.2f} vs {w[5]:.2f} ms) |")
out += ["", f"## Queries below {o.small_ms} ms: absolute difference |m1-m2| (ms) per group\n", "| variant | group | n | p90 abs diff ms | max abs diff ms |", "|---|---|---|---|---|"]
for v in variants + ["all"]:
    for g in GROUPS:
        rs = [r for r in rows if (v == "all" or r[0] == v) and r[3] == g and min(r[4], r[5]) < o.small_ms]
        if rs: out.append(f"| {v} | {g} | {len(rs)} | {pct([abs(r[4]-r[5]) for r in rs], .9):.3f} | {max(abs(r[4]-r[5]) for r in rs):.3f} |")
out += ["", "## All queries\n", "| variant | label | query | group | m1 ms | m2 ms | spread |", "|---|---|---|---|---|---|---|"]
for r in sorted(rows, key=lambda r: (r[1], r[2], r[0])):
    out.append(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]:.3f} | {r[5]:.3f} | {r[6]*100:.0f}% |")
dest = o.out or f"{o.tree2}/noise-{o.dialect}.md"
os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
open(dest, "w").write("\n".join(out) + "\n")
split = next(i for i, l in enumerate(out) if l.startswith("## All queries"))
print("\n".join(out[:split]))
print(f"(full table: {dest})")
