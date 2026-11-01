#!/usr/bin/env python3
"""Compact per-variant SUMMARY (PostgreSQL). usage: bin/summary2.py <results root> <variant>
Per scenario: slowest queries (run1 / run2 median, rows returned, plan shape: scan nodes with rows examined (actual rows x loops + removed by
filter) vs returned, shared buffers), plus write cost, concurrency, churn, repo-level."""
import csv, os, sys, glob, json, re
root, v = sys.argv[1:3]; dia = "postgres"
H = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
grp = {}
for f in glob.glob(f"{H}/catalog/{v}/{dia}/*.sql"):
    for l in open(f):
        if l.startswith("-- group:"): grp[os.path.basename(f)[:-4]] = l.split(":")[1].strip(); break
grp["count_statuses_all"] = "MON"
for k in ("upd_complete", "upd_retry", "upd_fail"): grp.setdefault(k, "WRITE")
base = f"{root}/{v}/{dia}"
def load(p):
    d = {}
    if os.path.exists(p):
        for r in csv.DictReader(open(p)):
            try: d[r["query_id"]] = (float(r["median_ms"]), float(r["p95_ms"]), r["rows"], r.get("note", ""))
            except: pass
    return d
def shape(p):
    try: pl = json.load(open(p))[0]
    except Exception: return "no plan"
    scans, other = [], []
    def walk(n):
        t = n["Node Type"]
        if "Scan" in t:
            nm = n.get("Index Name") or n.get("Relation Name", "")
            loops = n.get("Actual Loops", 1) or 1
            ex = (n.get("Actual Rows", 0) or 0) * loops + (n.get("Rows Removed by Filter", 0) or 0) * loops
            hf = f", heapfetch {n['Heap Fetches']}" if "Heap Fetches" in n else ""
            scans.append(f"{t}[{nm.replace('idx_outbox_record_', '')}] ret {n.get('Actual Rows')}x{loops} examined {ex}{hf}")
        elif t in ("Sort", "Aggregate", "Limit", "Unique", "Gather", "Hash Join", "Nested Loop", "Merge Join", "Bitmap Heap Scan"): other.append(t + (" (disk)" if n.get("Sort Space Type") == "Disk" else ""))
        for c in n.get("Plans", []): walk(c)
    walk(pl["Plan"])
    pr = pl["Plan"]
    buf = f"buf hit {pr.get('Shared Hit Blocks')} read {pr.get('Shared Read Blocks')}"
    return f"{'; '.join(scans) or pr['Node Type']}{' | ' + ','.join(other) if other else ''} | {buf} | exec {pl.get('Execution Time')} ms"
def size(env):
    return " ".join(x.replace("\t", " ") for x in re.findall(r"# (?:table_size|indexes_size_total)[^\n]*\n([^\n]*)", env))
md = [f"# {v}: PostgreSQL measurement summary\n",
      f"Worktree `{'outbox-wt-' + v}`. Table `outbox_record`; `prepare` = fresh schema + `VACUUM (FREEZE, ANALYZE)`. Medians in ms, run 1 (after snapshot load) / run 2 (after container restart, other variant order). "
      "Plan shape = first parameter set, without the client row cap; `examined` = rows produced by scan nodes plus rows removed by filter. Full tables: `postgres/<scenario>/timings.csv`, plans in `plans/`.\n"]
order = ["S1", "S2", "S8-S2", "S3", "S4", "S4-b100", "S8-S4", "S5", "S6", "S7", "S9"]
for sc in order:
    d = f"{base}/{sc}"
    a = load(f"{d}/timings.csv"); b = load(f"{d}-r2/timings.csv")
    if not a: continue
    env = open(f"{d}/env.txt").read()
    stat = (re.findall(r"# pg_stat_user_tables\n([^\n]*)", env) or [""])[0].split("\t")
    av = f"autovacuum during bench: last_autovacuum={stat[3] or 'none'}, vacuum_count={stat[5] if len(stat)>5 else '?'}" if len(stat) > 5 else ""
    md.append(f"\n## {sc}\n\n{size(env)}; {av}\n")
    md.append("| group | query | run1 | run2 | rows | plan shape |"); md.append("|---|---|---:|---:|---:|---|")
    top = sorted(a.items(), key=lambda kv: -kv[1][0])
    chosen = [kv for kv in top if grp.get(kv[0]) in ("HOT", "RET", "MON", "ADMIN")][:5]
    seen = {kv[0] for kv in chosen}
    for g in ("HOT", "WRITE"):
        kv = next((kv for kv in top if grp.get(kv[0]) == g and kv[0] not in seen), None)
        if kv and g == "HOT" and not any(grp.get(c[0]) == "HOT" for c in chosen): chosen.append(kv)
    if not any(grp.get(c[0]) == "HOT" for c in chosen):
        kv = next((kv for kv in top if grp.get(kv[0]) == "HOT"), None)
        if kv: chosen.append(kv)
    for q, x in chosen:
        r2 = b.get(q); note = f" ({x[3].split(':')[0]})" if x[3] else ""
        md.append(f"| {grp.get(q,'?')} | {q} | {x[0]:.2f}{note} | {r2[0]:.2f} | {x[2]} | {shape(f'{d}/plans/{q}.json')} |" if r2 else f"| {grp.get(q,'?')} | {q} | {x[0]:.2f}{note} | - | {x[2]} | {shape(f'{d}/plans/{q}.json')} |")
    w = [(q, a[q][0], b.get(q, (0,))[0]) for q in ("insert", "upd_complete", "delete_by_id") if q in a]
    md.append("\nWrite statements (rolled back, run1/run2 ms): " + ", ".join(f"{q} {x:.2f}/{y:.2f}" for q, x, y in w))
    if os.path.exists(f"{d}/churn.txt"):
        c = open(f"{d}/churn.txt").read(); parts = c.split("== after")
        md.append("\nChurn (S9, 500k inserts, no manual VACUUM after): " + "; before " + size(parts[0]) + "; after " + size(parts[1]) + "; " + re.findall(r"churn_seconds: \d+", c)[0]
                  + "; pg_stat after (live, dead, last_vacuum, last_autovacuum, last_autoanalyze, vac, autovac): " + (re.findall(r"# pg_stat_user_tables\n([^\n]*)", parts[1]) or ["?"])[0].replace("\t", " | "))
    if sc == "S2":
        wc = {r["metric"]: r["value"] for r in csv.DictReader(open(f"{d}/writecost.csv"))}
        md.append("\nWrite cost (committed, S2): insert 1 thread p50 %s ms p95 %s ms %s/s; 4 threads p50 %s ms %s/s; upd_complete committed p50 %s ms p95 %s ms. Sizes before/after: %s -> %s" % (
            wc["insert_1thread.p50_ms"], wc["insert_1thread.p95_ms"], wc["insert_1thread.throughput_per_s"], wc["insert_4threads.p50_ms"], wc["insert_4threads.throughput_per_s"],
            wc["upd_complete_committed.p50_ms"], wc["upd_complete_committed.p95_ms"], size(open(f"{d}/sizes-before-write.txt").read()), size(open(f"{d}/sizes-after-write.txt").read())))
        md.append("\nConcurrency S10 (run1 / run2; processed rec/s, inserted rec/s, keys p50 ms, pages p50 ms, update p50 ms, insert p50 ms):\n")
        md.append("| config | processed/s | inserted/s | keys p50 | pages p50 | update p50 | insert p50 |"); md.append("|---|---:|---:|---:|---:|---:|---:|")
        for p in sorted(glob.glob(f"{d}/../S10/concurrency-*.txt")):
            def parse(f):
                t = open(f).read() if os.path.exists(f) else ""
                g = lambda pat: (re.findall(pat, t) or ["-"])[0]
                return [g(r"processed_per_s: ([\d.]+)"), g(r"inserted_per_s: ([\d.]+)"), g(r"keys_query_ms: n=\d+ p50=([\d.]+)"), g(r"pages_query_ms: n=\d+ p50=([\d.]+)"), g(r"update_ms: n=\d+ p50=([\d.]+)"), g(r"insert_ms: n=\d+ p50=([\d.]+)")]
            x, y = parse(p), parse(p.replace("/S10/", "/S10-r2/"))
            md.append(f"| {os.path.basename(p)[12:-4]} | " + " | ".join(f"{i} / {j}" for i, j in zip(x, y)) + " |")
md.append("\n## Repository level (run 1; median ms, slowest 4 methods)\n")
for sc in ("S1", "S2", "S4", "S5"):
    for e in ("jdbc", "jpa"):
        t = load(f"{base}/{sc}/repo-{e}/timings.csv")
        if t:
            top = sorted(t.items(), key=lambda kv: -kv[1][0])[:4]
            md.append(f"- {sc} {e}: " + ", ".join(f"{q} {x[0]:.2f}" for q, x in top))
open(f"{root}/{v}/SUMMARY-{dia}.md", "w").write("\n".join(md) + "\n")
print(len(md))
