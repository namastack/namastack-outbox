#!/usr/bin/env python3
"""Builds compact markdown tables from results/<label>/summary.tsv files.

Usage: summarize.py <results-root> <label>... [--mode custom] [--compare-modes]
  default: one row per (query, variant), one column group per label (scenario):
           ms | hit/read | notes (spill, removed rows, workers), plus plan shape/indexes of the first label
  --compare-modes: per label, custom vs generic vs serial median ms
Last row per (query, variant, mode) in a summary.tsv wins (reruns append).
"""
import csv
import sys
from collections import OrderedDict


def short_idx(s):
    return (s.replace("idx_outbox_record_", "").replace("outbox_record_pkey", "pkey")
            .replace(",", ", "))


def short_shape(s):
    rep = [("Nested Loop Anti Join", "NL Anti"), ("Nested Loop", "NL"), ("Parallel ", "P."),
           ("Bitmap Heap Scan", "BHS"), ("Bitmap Index Scan", "BIS"), ("Index Only Scan", "IOS"),
           ("Index Scan", "IS"), ("Seq Scan", "Seq"), ("HashAggregate", "HashAgg"),
           ("GroupAggregate", "GroupAgg"), ("Gather Merge", "GatherM"), ("Hash Anti Join", "Hash Anti"),
           ("Hash Right Anti Join", "Hash R-Anti"), ("Incremental Sort", "IncSort"),
           ("Finalize Aggregate", "FinAgg"), ("Partial Aggregate", "PartAgg")]
    for a, b in rep:
        s = s.replace(a, b)
    return s


def load(root, label):
    rows = OrderedDict()
    with open(f"{root}/{label}/summary.tsv") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            rows[(r["query"], r["variant"], r["mode"])] = r
    return rows


def main():
    args = sys.argv[1:]
    root = args.pop(0)
    compare = "--compare-modes" in args
    mode = "custom"
    if "--mode" in args:
        mode = args[args.index("--mode") + 1]
        args.remove("--mode"); args.remove(mode)
    labels = [a for a in args if not a.startswith("--")]
    # Usage addition: --plans prints plan shape + indexes per label
    data = {l: load(root, l) for l in labels}
    keys = []
    for l in labels:
        for k in data[l]:
            if (k[0], k[1]) not in keys:
                keys.append((k[0], k[1]))
    if "--plans" in args:
        print("| query | variant | " + " | ".join(f"{l} plan [indexes]" for l in labels) + " |")
        print("|---|---|" + "---|" * len(labels))
        for q, v in keys:
            cells = []
            for l in labels:
                r = data[l].get((q, v, mode))
                cells.append(f"{short_shape(r['plan_shape'])} [{short_idx(r['indexes'])}]" if r else "-")
            print(f"| {q} | {v} | " + " | ".join(cells) + " |")
        return
    if compare:
        modes = ["custom", "generic", "custom-serial", "generic-serial"]
        print("| query | variant | " + " | ".join(f"{l} custom / generic / serial / generic-serial ms" for l in labels) + " |")
        print("|---|---|" + "---|" * len(labels))
        for q, v in keys:
            cells = []
            any_other = False
            for l in labels:
                vals = []
                for m in modes:
                    r = data[l].get((q, v, m))
                    vals.append(r["exec_ms_median"] if r else "-")
                    if r and m != "custom":
                        any_other = True
                cells.append(" / ".join(vals))
            if any_other:
                print(f"| {q} | {v} | " + " | ".join(cells) + " |")
        return
    print("| query | variant | " + " | ".join(f"{l} ms | {l} hit/read | {l} notes" for l in labels) + " | plan ({}) | indexes |".format(labels[0]))
    print("|---|---|" + "---|---|---|" * len(labels) + "---|---|")
    for q, v in keys:
        cells = []
        first = None
        for l in labels:
            r = data[l].get((q, v, mode))
            if not r:
                cells.append("- | - | -")
                continue
            first = first or r
            notes = []
            if r["spill"] == "yes":
                notes.append("SPILL")
            if r["rows_removed"] not in ("", "0"):
                notes.append(f"rm {int(r['rows_removed']):,}")
            if r["workers"]:
                notes.append(f"w{r['workers']}")
            cells.append(f"{r['exec_ms_median']} | {r['shared_hit']}/{r['shared_read']} | {' '.join(notes)}")
        shape = short_shape(first["plan_shape"]) if first else ""
        idx = short_idx(first["indexes"]) if first else ""
        print(f"| {q} | {v} | " + " | ".join(cells) + f" | {shape} | {idx} |")


if __name__ == "__main__":
    main()
