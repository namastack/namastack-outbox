#!/usr/bin/env python3
"""Before -> after table for G2 targets.
Usage: report.py <results_dir> <after_label_fmt> [--after-tag T] [--scen A,B,D] [--modes custom,generic] [--before-fmt G2ctl-{S}]
  label fmt uses {S}; after-tag T selects rows whose mode is '<mode>-T' in the after label (e.g. origq, h10)."""
import csv, os, sys, argparse
p = argparse.ArgumentParser(); p.add_argument("R"); p.add_argument("after")
p.add_argument("--after-tag", default=""); p.add_argument("--scen", default="A,B,C,D,E")
p.add_argument("--modes", default="custom,generic"); p.add_argument("--before-fmt", default="G2ctl-{S}")
p.add_argument("--before-tag", default="")
a = p.parse_args()
def load(l):
    f = os.path.join(a.R, l, "summary.tsv")
    if not os.path.exists(f): return {}
    return {(r["query"], r["variant"], r["mode"]): r for r in csv.DictReader(open(f), delimiter="\t")}
short = {"idx_outbox_record_record_key_completed_created": "rkcc", "idx_outbox_record_record_key_created": "rkc",
         "idx_outbox_record_status": "status", "idx_outbox_record_incomplete_key": "inc_key",
         "idx_outbox_record_incomplete_created": "inc_created", "idx_outbox_record_record_key_status_created": "rksc",
         "idx_outbox_record_new_key_created": "new_key", "idx_outbox_record_status_retry": "status_retry",
         "idx_outbox_record_partition_status_retry": "psr", "outbox_record_pkey": "pkey"}
shape = lambda r: r["plan_shape"].replace("Bitmap Heap Scan", "BHS").replace("Bitmap Index Scan", "BIS").replace("Parallel ", "P.").replace("Incremental Sort", "IncSort").replace("Gather Merge", "GM")
idx = lambda r: ",".join(short.get(i, i) for i in r["indexes"].split(",") if i)
def cell(r):
    if not r: return "-"
    sp = " spill" if r["spill"] not in ("no", "") else ""
    return f"{float(r['exec_ms_median']):.2f} ({int(float(r['shared_hit'] or 0))}{sp})"
print("| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |\n|---|---|---|---|---|---|---|")
for S in a.scen.split(","):
    b, g = load(a.before_fmt.format(S=S)), load(a.after.format(S=S))
    for m in a.modes.split(","):
        bm = m + (f"-{a.before_tag}" if a.before_tag else "")
        am = m + (f"-{a.after_tag}" if a.after_tag else "")
        for q, v in [("findIncompleteRecordPages", "keys=10"), ("findIncompleteRecordPages", "keys=100"),
                     ("findIncompleteRecordPages", "keys=1000"), ("findIncompleteRecordsByRecordKey", "default")]:
            x, y = b.get((q, v, bm)), g.get((q, v, am))
            if not y: continue
            d = f"{(float(y['exec_ms_median'])/float(x['exec_ms_median'])-1)*100:+.0f}%" if x and float(x['exec_ms_median']) else ""
            qn = ("pages " + v.replace("keys=", "")) if q.startswith("findIncompleteRecordPages") else "byKey"
            print(f"| {S} | {qn} | {m} | {cell(x)} | {cell(y)} | {d} | {shape(y)} [{idx(y)}] |")
