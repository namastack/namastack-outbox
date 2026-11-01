#!/usr/bin/env python3
"""Generate results/<variant>/SUMMARY-<dialect>.md skeleton data. usage: bin/summary.py <results root> <variant> <dialect>"""
import csv, os, sys, glob, json
root, v, dia = sys.argv[1:4]
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
    except Exception: return "n/a"
    out = []
    def walk(n, d=0):
        s = n["Node Type"]
        if "Relation Name" in n: s += f" on {n['Relation Name']}"
        if "Index Name" in n: s += f" [{n['Index Name']}]"
        if n.get("Join Type"): s += f" ({n['Join Type']})"
        if "Sort Key" in n: s += " (sort)"
        out.append(f"{'  '*d}- {s}: rows={n.get('Actual Rows')}×{n.get('Actual Loops')}, removed-by-filter={n.get('Rows Removed by Filter', 0)}, buffers hit={n.get('Shared Hit Blocks')} read={n.get('Shared Read Blocks')}, {n.get('Actual Total Time')} ms")
        for c in n.get("Plans", []): walk(c, d+1)
    walk(pl["Plan"])
    ex = f"planning {pl.get('Planning Time')} ms, execution {pl.get('Execution Time')} ms"
    return ex + "\n" + "\n".join(out)
md = [f"# {v} — PostgreSQL measurement summary\n",
      f"Worktree HEAD: see `{dia}/*/env.txt`. Run 1 = warm after snapshot load; run 2 (`-r2`) = after container restart. Values are medians in ms (run1 / run2). Plans are for the first parameter set, without the client-side row cap (see harness README).\n"]
scs = [os.path.basename(d) for d in sorted(glob.glob(base + "/*")) if (os.path.isfile(d + "/timings.csv") or glob.glob(d + "/concurrency-*.txt")) and not d.endswith("-r2")]
for sc in scs:
    a = load(f"{base}/{sc}/timings.csv"); b = load(f"{base}/{sc}-r2/timings.csv")
    md.append(f"\n## {sc}\n")
    top = sorted(a.items(), key=lambda kv: -kv[1][0])
    md.append("| group | query | median run1 | median run2 | p95 run1 | rows |"); md.append("|---|---|---:|---:|---:|---:|")
    for q, x in top[:8]:
        md.append(f"| {grp.get(q,'?')} | {q} | {x[0]:.2f} | {b.get(q,(float('nan'),))[0]:.2f} | {x[1]:.2f} | {x[2]} |")
    md.append("\nSlowest per group (run 1) with plan shape:\n")
    for g in ("HOT", "WRITE", "MON", "RET", "ADMIN"):
        qs = [(q, x) for q, x in top if grp.get(q) == g]
        for q, x in qs[:2]:
            md.append(f"**{g} / {q}** — {x[0]:.2f} ms, {x[2]} rows {('('+x[3]+')') if x[3] else ''}\n\n```\n{shape(f'{base}/{sc}/plans/{q}.json')}\n```\n")
    env = open(f"{base}/{sc}/env.txt").read() if os.path.exists(f"{base}/{sc}/env.txt") else ""
    if "--- sizes ---" in env:
        s = env.split("--- sizes ---")[1]
        md.append("Sizes / indexes:\n\n```\n" + "\n".join(s.strip().splitlines()[:22]) + "\n```\n")
    for extra in ("churn.txt", "writecost.csv"):
        p = f"{base}/{sc}/{extra}"
        if os.path.exists(p): md.append(f"{extra}:\n\n```\n{open(p).read().strip()[:2500]}\n```\n")
    for p in sorted(glob.glob(f"{base}/{sc}/concurrency-*.txt")): md.append(f"{os.path.basename(p)}:\n\n```\n{open(p).read().strip()}\n```\n")
    p = f"{base}/{sc}/repo-jdbc/timings.csv"
open(f"{root}/{v}/SUMMARY-{dia}.md", "w").write("\n".join(md) + "\n")
