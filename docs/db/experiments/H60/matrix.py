#!/usr/bin/env python3
"""Builds results/H60-matrix.md data from results/*/summary.tsv. Usage: matrix.py <results-root>"""
import csv, sys, os
R = sys.argv[1]

def load(label):
    p = os.path.join(R, label, "summary.tsv")
    d = {}
    if not os.path.exists(p):
        return d
    for r in csv.DictReader(open(p), delimiter="\t"):
        d[(r["query"], r["variant"], r["mode"])] = r
    return d

SER = {"findRecordKeysInPartitions_filter", "findRecordKeysInPartitions_nofilter", "findCompletedRecordsBefore",
       "findFailedRecordsBefore", "deleteCompletedRecords_selectIds", "deleteFailedRecords_selectIds"}
KEYQ = {"findRecordKeysInPartitions_filter", "findRecordKeysInPartitions_nofilter"}
def mode_for(q, side, kind):  # side base|S ; kind custom|generic
    if kind == "generic":
        return "generic-serial" if q in KEYQ else "generic"
    if q in SER and (side == "base" or q in KEYQ):
        return "custom-serial"
    return "custom"

def v(d, q, var, mode, f="exec_ms_median"):
    r = d.get((q, var, mode))
    return r[f] if r else None
def fmt(x):
    if x is None: return "-"
    x = float(x)
    return f"{x:.3g}" if x < 10 else f"{x:.0f}" if x >= 100 else f"{x:.1f}"

CLEAN = {"findCompletedRecordsBefore", "findFailedRecordsBefore", "deleteCompletedRecords_selectIds", "deleteFailedRecords_selectIds"}
SC = ["A", "B", "D"]
base = {s: load(f"base-{s}") for s in "ABCDE"}
S1 = {s: load(f"H60-S1-{s}") for s in "ABCDE"}
S2 = {s: load(f"H60-S2-{s}") for s in "ABD"}
rows = []
for (q, var, mode) in S1["A"]:
    if mode == "custom": rows.append((q, var))
rows.sort()
def table(kind, scen, with_s2=True):
    out = [f"| query | variant | " + " | ".join(f"base {s}" for s in scen) + " | " + " | ".join(f"S1 {s}" for s in scen) +
           (" | " + " | ".join(f"S2 {s}" for s in scen) if with_s2 else "") + " |",
           "|---|---|" + "---:|" * (len(scen) * (3 if with_s2 else 2))]
    for q, var in rows:
        if kind == "generic" and not any((q, var, "generic") in S1["A"] or (q, var, "generic-serial") in S1["A"] for _ in [0]):
            continue
        cells = []
        for side, D in (("base", base), ("S", S1)) + ((("S", S2),) if with_s2 else ()):
            for s in scen:
                if s not in D: cells.append("-"); continue
                m = mode_for(q, side, kind)
                bv = "default" if (side == "base" and q in CLEAN) else var
                x = v(D[s], q, bv, m)
                if x is None and kind == "custom": x = v(D[s], q, bv, "custom")
                cells.append(fmt(x))
        if all(c == "-" for c in cells): continue
        out.append(f"| {q} | {var} | " + " | ".join(cells) + " |")
    return "\n".join(out)

def plans(D, scen):
    out = ["| query | variant | " + " | ".join(f"S1 {s} shape" for s in scen) + " | S1 B indexes |", "|---|---|" + "---|" * (len(scen) + 1)]
    for q, var in rows:
        cells = []
        for s in scen:
            m = mode_for(q, "S", "custom")
            r = D[s].get((q, var, m)) or D[s].get((q, var, "custom"))
            cells.append((r["plan_shape"] if r else "-").replace("Nested Loop Anti Join", "NL Anti").replace("Index Only Scan", "IOS").replace("Index Scan", "IS").replace("Parallel Seq Scan", "PSeq").replace("Seq Scan", "Seq").replace("Bitmap Heap Scan", "BHS").replace("Bitmap Index Scan", "BIS"))
        r = D["B"].get((q, var, mode_for(q, "S", "custom"))) or D["B"].get((q, var, "custom"))
        ix = (r["indexes"] if r else "-").replace("idx_outbox_record_", "").replace("outbox_record_pkey", "pkey")
        out.append(f"| {q} | {var} | " + " | ".join(cells) + f" | {ix} |")
    return "\n".join(out)

HOT = [("findRecordKeysInPartitions_filter", "parts=64"), ("findRecordKeysInPartitions_filter", "parts=256"),
       ("findRecordKeysInPartitions_nofilter", "parts=64"), ("findRecordKeysInPartitions_nofilter", "parts=256"),
       ("findOldestDueNextRetryAtByPartitions_filter", "parts=64"), ("findOldestDueNextRetryAtByPartitions_filter", "parts=256"),
       ("findIncompleteRecordPages", "keys=10"), ("findIncompleteRecordPages", "keys=100"), ("findIncompleteRecordPages", "keys=1000"),
       ("findOldestCreatedAtByPartitions", "parts=64"), ("findOldestCreatedAtByPartitions", "parts=256")]
def iso(q, var, s, sett):
    if q in ("findRecordKeysInPartitions_filter", "findOldestDueNextRetryAtByPartitions_filter"):
        return v(load(f"H08-{s}"), q, var, "custom-serial")
    if q == "findRecordKeysInPartitions_nofilter":
        return v(load(f"H05X-{s}"), q, var, "custom-serial")
    if q == "findIncompleteRecordPages":
        return v(load(f"G2i2-{s}"), q, var, "custom-h08a-Lon" if sett == "S1" else "custom-u2b-Lon")
    if q == "findOldestCreatedAtByPartitions":
        return v(load(f"G4G5-{s}"), q, var, "custom-h32psr")
def regress():
    out = ["| query | variant | scen | baseline | best isolated | S1 | S2 | S1 vs iso | S2 vs iso | S1 vs base |", "|---|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    flags = []
    for q, var in HOT:
        for s in SC:
            m = mode_for(q, "base", "custom"); b = v(base[s], q, var, m)
            mS = mode_for(q, "S", "custom")
            a1 = v(S1[s], q, var, mS); a2 = v(S2[s], q, var, mS) if s in S2 else None
            i1 = iso(q, var, s, "S1"); i2 = iso(q, var, s, "S2")
            def d(x, y): return "-" if x is None or y is None else f"{(float(x)/float(y)-1)*100:+.0f}%"
            out.append(f"| {q} | {var} | {s} | {fmt(b)} | {fmt(i1)} | {fmt(a1)} | {fmt(a2)} | {d(a1,i1)} | {d(a2,i2)} | {d(a1,b)} |")
            for tag, x, y in (("S1 vs iso", a1, i1), ("S2 vs iso", a2, i2), ("S1 vs base", a1, b), ("S2 vs base", a2, b)):
                if x is not None and y is not None and float(x) > float(y) * 1.10 and float(x) - float(y) > 0.05:
                    flags.append(f"{q} {var} {s}: {tag} {float(y):.3g} -> {float(x):.3g} ms")
    return "\n".join(out) + "\n\nFlags (>10% and >0.05 ms):\n" + "\n".join("- " + f for f in flags)
def ce():
    out = ["| query | variant | base C | S1 C | base E | S1 E |", "|---|---|---:|---:|---:|---:|"]
    for q, var in HOT:
        out.append(f"| {q} | {var} | " + " | ".join(fmt(v(D_, q, var, mode_for(q, side, "custom"))) for D_, side in
                   ((base["C"], "base"), (S1["C"], "S"), (base["E"], "base"), (S1["E"], "S"))) + " |")
    return "\n".join(out)
def scaling():
    HP = [("findRecordKeysInPartitions_filter", "parts=64"), ("findOldestDueNextRetryAtByPartitions_filter", "parts=64"),
          ("findIncompleteRecordPages", "keys=100"), ("findOldestCreatedAtByPartitions", "parts=64")]
    out = ["| set | A | B | D | B vs base B |", "|---|---:|---:|---:|---:|"]
    tot = {}
    for name, DD, side in (("base", base, "base"), ("S1", S1, "S"), ("S2", S2, "S")):
        t = {s: sum(float(v(DD[s], q, var, mode_for(q, side, "custom")) or 0) for q, var in HP) for s in SC}
        tot[name] = t
        out.append(f"| {name} | {t['A']:.2f} | {t['B']:.2f} | {t['D']:.2f} | {(t['B']/tot['base']['B']-1)*100:+.1f}% |")
    return "\n".join(out)
if __name__ == "__main__":
    print("## custom plans, ms (key queries and base cleanup selects: serial = pgjdbc maxRows)\n"); print(table("custom", SC)); print()
    print("## generic plans, ms\n"); print(table("generic", SC)); print()
    print("## hot path vs best isolated Phase 1 number\n"); print(regress()); print()
    print("## C and E, hot path\n"); print(ce()); print()
    print("## hot path sum (key filter 64p + oldest due filter 64p + pages 100 keys + oldest created 64p), ms\n"); print(scaling()); print()
    print("## plan shape and indexes (S1)\n"); print(plans(S1, SC))
