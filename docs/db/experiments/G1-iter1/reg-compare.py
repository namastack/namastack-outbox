#!/usr/bin/env python3
"""Regression diff: results/<reg>/summary.tsv vs results/<base>/summary.tsv.
Flags rows whose plan shape or index set changed, or median >10% (and >0.2 ms) slower.
Usage: reg-compare.py <results_dir> <base_label> <reg_label>"""
import csv, os, sys
R, B, G = sys.argv[1:4]
def load(l):
    return {(r["query"], r["variant"], r["mode"]): r for r in csv.DictReader(open(os.path.join(R, l, "summary.tsv")), delimiter="\t")}
b, g = load(B), load(G)
print(f"| query | variant | mode | {B} ms | {G} ms | Δ | change |\n|---|---|---|---|---|---|---|")
for k in sorted(g):
    if k not in b or "costs" in k[2]: continue
    x, y = b[k], g[k]
    bm, gm = float(x["exec_ms_median"] or 0), float(y["exec_ms_median"] or 0)
    flags = []
    if x["plan_shape"] != y["plan_shape"]: flags.append("shape")
    if x["indexes"] != y["indexes"]: flags.append(f"idx {x['indexes'] or '-'} → {y['indexes'] or '-'}")
    slower = bm > 0 and gm > bm * 1.10 and gm - bm > 0.2
    if slower: flags.append("SLOWER")
    if flags:
        print(f"| {k[0]} | {k[1]} | {k[2]} | {bm:.3g} | {gm:.3g} | {(gm/bm-1)*100 if bm else 0:+.0f}% | {'; '.join(flags)} |")
