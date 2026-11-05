#!/usr/bin/env python3
"""Diff two result dirs (each containing timings.csv, or repo-jdbc/ repo-jpa subdirs) into a markdown table.

usage: bin/compare.py <baseline dir> <candidate dir> [--metric median|p95|min|max] [--sub repo-jdbc]
Relative change = (candidate - baseline) / baseline; negative = candidate faster.
"""
import csv, os, sys, argparse

def load(path):
    rows = {}
    with open(path) as f:
        for r in csv.DictReader(f):
            rows[r["query_id"]] = r
    return rows

def load_modes(path):
    """plancache.csv -> {query_id: (label, class)}; label cN/gM = custom/generic plans of the timed reps, class c|g|mix|?"""
    m = {}
    if not os.path.exists(path):
        return m
    with open(path) as f:
        for r in csv.DictReader(f):
            try:
                c, g = int(r["custom_timed"]), int(r["generic_timed"])
            except (ValueError, KeyError):
                m[r["query_id"]] = ("-", "?"); continue
            m[r["query_id"]] = (f"c{c}/g{g}", "c" if g == 0 and c > 0 else "g" if c == 0 and g > 0 else "mix" if c + g > 0 else "?")
    return m

def num(r, k):
    try:
        return float(r[k]) if r and r.get(k, "") != "" else None
    except ValueError:
        return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a"); ap.add_argument("b")
    ap.add_argument("--metric", default="median", choices=["median", "p95", "min", "max"])
    ap.add_argument("--sub", default="")
    ap.add_argument("--noise-ms", type=float, default=0.05, help="absolute differences below this are shown as ~0")
    o = ap.parse_args()
    pa = os.path.join(o.a, o.sub, "timings.csv"); pb = os.path.join(o.b, o.sub, "timings.csv")
    A, B = load(pa), load(pb)
    MA = load_modes(os.path.join(o.a, o.sub, "plancache.csv")); MB = load_modes(os.path.join(o.b, o.sub, "plancache.csv"))
    ids = list(A.keys()) + [k for k in B if k not in A]
    key = o.metric + "_ms"
    print(f"Baseline: `{o.a}`  \nCandidate: `{o.b}`  \nMetric: {o.metric} (ms), relative change = (candidate - baseline) / baseline\n")
    print("Plan mode = custom/generic plans of the timed reps (plancache.csv); cells whose modes differ are flagged `MODE DIFFERS` and are not comparable.\n")
    print(f"| query | base median | cand median | change | base p95 | cand p95 | change | rows base | rows cand | mode base | mode cand | note |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|")
    def fmt(v): return "n/a" if v is None else (f"{v:.3f}" if v < 100 else f"{v:.1f}")
    def rel(x, y):
        if x is None or y is None: return "n/a"
        if abs(y - x) < o.noise_ms: return "~0"
        if x == 0: return "n/a"
        return f"{(y - x) / x * 100:+.0f}%"
    for i in ids:
        a, b = A.get(i), B.get(i)
        am, bm, ap95, bp95 = num(a, "median_ms"), num(b, "median_ms"), num(a, "p95_ms"), num(b, "p95_ms")
        note = "; ".join(x for x in [(a or {}).get("note", ""), (b or {}).get("note", "")] if x)
        if o.metric != "median":
            am, bm = num(a, key), num(b, key)
        ma, mb = MA.get(i, ("", "")), MB.get(i, ("", ""))
        flag = "MODE DIFFERS" if ma[1] and mb[1] and "?" not in (ma[1], mb[1]) and ma[1] != mb[1] else ""
        note = "; ".join(x for x in [flag, note] if x)
        print(f"| {i} | {fmt(am)} | {fmt(bm)} | {rel(am, bm)} | {fmt(ap95)} | {fmt(bp95)} | {rel(ap95, bp95)} | "
              f"{(a or {}).get('rows', '')} | {(b or {}).get('rows', '')} | {ma[0]} | {mb[0]} | {note} |")

if __name__ == "__main__":
    main()
