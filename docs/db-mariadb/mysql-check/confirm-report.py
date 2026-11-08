#!/usr/bin/env python3
"""Tables for CONFIRM.md: keys_strict / keys_nonstrict (and all HOT/MON ids with --all) head vs ne per label and pass."""
import csv, os, re, sys
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results-confirm")
LABS = "S8-S2 S8-S4 S16-S4 S8-S5 S8-S6 S8-S7 S16-S2 S1 S2 S4 S4-b100 S5 S6 S7".split()
def med(p, run, lab, q):
    f = f"{R}/{p}/{run}/mysql/{lab}/timings.csv"
    if not os.path.exists(f): return None
    for r in csv.DictReader(open(f)):
        if r["query_id"] == q: return (float(r["median_ms"]), r["note"], int(r["reps"]))
def fmt(x):
    if x is None: return "-"
    v = x[0]; s = f"{v:.0f}" if v >= 100 else f"{v:.2f}"
    if "limit" in x[1] or "stopped" in x[1]: s = ">" + s
    return s
def ratio(a, b): return "-" if not a or not b or not b[0] else f"{a[0]/b[0]:.2f}"
def verdict(h, n, thr=0.11, absl=0.0):
    if not h or not n: return "-"
    d = abs(h[0] - n[0]); 
    if d <= thr * min(h[0], n[0]) or d <= absl: return "="
    return "ne better" if n[0] < h[0] else "head better"
out = []
for q in ["keys_strict", "keys_nonstrict"] + (["oldest_created", "oldest_due_strict", "oldest_due_nonstrict", "pages"] if "--all" in sys.argv else []):
    out.append(f"\n### {q} (median ms)\n\n| label | head p1 | ne p1 | ne/head p1 | head p2 | ne p2 | ne/head p2 | verdict p1 / p2 | SPREAD>2x head / ne |\n|---|---|---|---|---|---|---|---|---|")
    for lab in LABS:
        c = {(p, r): med(p, r, lab, q) for p in ("pass1", "pass2") for r in ("my-head", "my-ne")}
        if not any(c.values()): continue
        sp = []
        for r in ("my-head", "my-ne"):
            a, b = c[("pass1", r)], c[("pass2", r)]
            sp.append("-" if not a or not b else ("YES %.0f%%" % (abs(a[0]-b[0]) / min(a[0], b[0]) * 100) if abs(a[0]-b[0]) > 2*0.11*min(a[0], b[0]) else "no"))
        out.append(f"| {lab} | {fmt(c[('pass1','my-head')])} | {fmt(c[('pass1','my-ne')])} | {ratio(c[('pass1','my-ne')], c[('pass1','my-head')])} | {fmt(c[('pass2','my-head')])} | {fmt(c[('pass2','my-ne')])} | {ratio(c[('pass2','my-ne')], c[('pass2','my-head')])} | {verdict(c[('pass1','my-head')], c[('pass1','my-ne')])} / {verdict(c[('pass2','my-head')], c[('pass2','my-ne')])} | {' / '.join(sp)} |")
out.append("\n### 2 GB buffer pool (median ms)\n\n| label | query | head | ne | ne/head | verdict |\n|---|---|---|---|---|---|")
for lab in ["S8-S2", "S8-S5", "S5"]:
    for q in ["keys_strict", "keys_nonstrict"]:
        h, n = med("bp2g", "my-head", lab, q), med("bp2g", "my-ne", lab, q)
        if h or n: out.append(f"| {lab} | {q} | {fmt(h)} | {fmt(n)} | {ratio(n, h)} | {verdict(h, n)} |")
out.append("\n### S10 (60 s)\n\n| cell | run | processed/s | inserted/s | keys_query_ms | pages_ms | errors |\n|---|---|---|---|---|---|---|")
for p in ("pass1", "pass2", "bp2g"):
    for lab in ["S10-S2-p1-unthr", "S10-S2-p4-unthr", "S10-S4-p4", "S10-S4-p4-unthr"]:
        for r in ("my-head", "my-ne"):
            d = f"{R}/{p}/{r}/mysql/{lab}"
            if not os.path.isdir(d): continue
            for f in os.listdir(d):
                if f.startswith("concurrency-") and f.endswith(".txt") and "plans" not in f:
                    t = open(f"{d}/{f}").read(); g = lambda k: (re.search(k + r": *(.*)", t) or [0, "-"])[1].strip()
                    out.append(f"| {p} {lab} | {r} | {g('processed_per_s').split()[0]} | {g('inserted_per_s').split()[0]} | {g('keys_query_ms')} | {g('pages_query_ms')} | {g('errors')} |")
print("\n".join(out))
