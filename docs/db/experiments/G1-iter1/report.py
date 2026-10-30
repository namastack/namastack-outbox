#!/usr/bin/env python3
"""Markdown tables for G1 iter 1. Usage: report.py <results_dir> <H> [modes...]
Prints, per scenario, ctl -> H median ms (buffers) for the target queries."""
import csv, os, sys
R, H = sys.argv[1], sys.argv[2]
modes = sys.argv[3:] or ["custom-serial", "generic-serial", "custom"]
Q = ["findRecordKeysInPartitions_filter", "findRecordKeysInPartitions_nofilter",
     "findOldestDueNextRetryAtByPartitions_filter", "findOldestDueNextRetryAtByPartitions_nofilter"]
ABBR = {Q[0]: "keys on", Q[1]: "keys off", Q[2]: "MIN on", Q[3]: "MIN off"}
def load(label):
    p = os.path.join(R, label, "summary.tsv"); d = {}
    if not os.path.exists(p): return d
    for r in csv.DictReader(open(p), delimiter="\t"):
        d[(r["query"], r["variant"], r["mode"])] = r
    return d
def cell(r):
    if not r: return "-"
    ms = float(r["exec_ms_median"]); return f"{ms:.3g} ({int(float(r['shared_hit'] or 0)):,})" if ms < 1000 else f"{ms:.0f} ({int(float(r['shared_hit'] or 0)):,})"
for mode in modes:
    print(f"\n**{mode}** — ms (shared hits), ctl → {H}\n")
    print("| Query | parts | " + " | ".join("ABCDE") + " |"); print("|---|---|" + "---|" * 5)
    for q in Q:
        for p in ("64", "256"):
            cells = []
            for s in "ABCDE":
                c, h = load(f"G1ctl-{s}"), load(f"{H}-{s}")
                k = (q, f"parts={p}", mode)
                cells.append(f"{cell(c.get(k))} → {cell(h.get(k))}" if h.get(k) else "-")
            print(f"| {ABBR[q]} | {p} | " + " | ".join(cells) + " |")
