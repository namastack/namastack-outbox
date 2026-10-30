#!/usr/bin/env python3
"""Tables from results/G2i2-<S>/summary.tsv. Usage: report.py <S> [S...]  -> per scenario: rows = state-variant, cols = pages 10/100/1000 custom | generic (ms, spill flag)."""
import csv, sys, os
R = os.path.join(os.path.dirname(__file__), "..", "..", "results")
for S in sys.argv[1:]:
    rows = {}
    for r in csv.DictReader(open(f"{R}/G2i2-{S}/summary.tsv"), delimiter="\t"):
        m = r["mode"].split("-", 1)
        if len(m) < 2 or m[0] not in ("custom", "generic") or r["query"] != "findIncompleteRecordPages": continue
        rows.setdefault(m[1], {})[(m[0], r["variant"])] = r
    # baseline control from iteration 1
    base = {}
    f = f"{R}/G2ctl-{S}/summary.tsv"
    if os.path.exists(f):
        for r in csv.DictReader(open(f), delimiter="\t"):
            if r["query"] == "findIncompleteRecordPages" and r["mode"] in ("custom", "generic"): base[(r["mode"], r["variant"])] = r
        rows = {"baseline(G2ctl)": base, **rows}
    print(f"\n### Scenario {S} (median ms; * = sort spill)\n| state-variant | 10 cust | 100 cust | 1000 cust | 10 gen | 100 gen | 1000 gen | 1000 cust plan |\n|---|---|---|---|---|---|---|---|")
    for t, d in rows.items():
        c = lambda m, k: (lambda r: "-" if not r else f"{float(r['exec_ms_median']):.2f}{'*' if r['spill'] not in ('no','') else ''}")(d.get((m, f"keys={k}")))
        p = d.get(("custom", "keys=1000")); sh = p["plan_shape"].replace("Bitmap Heap Scan","BHS").replace("Bitmap Index Scan","BIS").replace("Parallel ","P.").replace("Incremental Sort","IncSort").replace("Gather Merge","GM").replace("Nested Loop","NL") if p else ""
        print(f"| {t} | {c('custom',10)} | {c('custom',100)} | {c('custom',1000)} | {c('generic',10)} | {c('generic',100)} | {c('generic',1000)} | {sh} |")
