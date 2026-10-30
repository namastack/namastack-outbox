#!/usr/bin/env python3
"""Parses the psql output produced by run-explain.sh for one query variant.

Input: a file with runs separated by lines `==RUN <n>==` (run 1 = warm-up).
Writes the trimmed raw output (median run plus timings of all runs) to <out_txt> and prints one
TSV summary line to stdout:

query variant mode exec_ms_median exec_ms_runs plan_ms shared_hit shared_read temp_blks spill
rows_removed workers_launched rows_out indexes plan_shape
"""
import re
import statistics
import sys

LONG_LIST = re.compile(r"'\{([^}]{80,})\}'")
LONG_IN = re.compile(r"\(((?:'[^']*'|\$?\d+)(?:\s*,\s*(?:'[^']*'|\$?\d+)){9,})\)")


def trim(line: str) -> str:
    line = line.rstrip()
    if line.startswith("----"):
        return "-" * 40
    if line.strip() == "QUERY PLAN":
        return " QUERY PLAN"
    line = re.sub(r"ARRAY\[(\$\d+), (?:\$\d+, )+(\$\d+)\]", r"ARRAY[\1, ..., \2]", line)
    line = LONG_LIST.sub(lambda m: "'{%s,... %d items}'" % (m.group(1).split(",")[0], m.group(1).count(",") + 1), line)
    line = LONG_IN.sub(lambda m: "(%s, ... %d items)" % (m.group(1).split(",")[0].strip(), m.group(1).count(",") + 1), line)
    if len(line) > 400:
        line = line[:400] + " ...[trimmed]"
    return line


def parse_run(lines):
    plan = [l for l in lines if l.strip() and not l.startswith(("BEGIN", "ROLLBACK", "PREPARE", "DEALLOCATE", "SET"))]
    res = {"exec": None, "plan_ms": None, "hit": 0, "read": 0, "temp": 0, "spill": False,
           "removed": 0, "workers": "", "rows_out": "", "indexes": [], "shape": [], "lines": plan}
    first_buffers = True
    in_planning = False
    for l in plan:
        s = l.strip()
        if s.startswith("Planning:"):
            in_planning = True
        m = re.match(r"Execution Time: ([\d.]+) ms", s)
        if m:
            res["exec"] = float(m.group(1))
        m = re.match(r"Planning Time: ([\d.]+) ms", s)
        if m:
            res["plan_ms"] = float(m.group(1))
        if s.startswith("Buffers:") and first_buffers and not in_planning:
            first_buffers = False
            h = re.search(r"shared hit=(\d+)", s)
            r = re.search(r"shared(?: hit=\d+)? read=(\d+)", s)
            res["hit"] = int(h.group(1)) if h else 0
            res["read"] = int(r.group(1)) if r else 0
            tw = re.search(r"temp.*?written=(\d+)", s)
            res["temp"] = int(tw.group(1)) if tw else 0
        if "external" in s or "Disk:" in s or "Batches: " in s and not re.search(r"Batches: 1\b", s):
            res["spill"] = True
        m = re.match(r"Rows Removed by (?:Filter|Index Recheck|Join Filter): (\d+)", s)
        if m:
            res["removed"] += int(m.group(1))
        m = re.match(r"Workers Launched: (\d+)", s)
        if m and res["workers"] == "":
            res["workers"] = m.group(1)
        node = re.match(r"(?:->\s+)?([A-Z][A-Za-z ]+?)(?: using (\S+))?(?: on \S+(?: \w+)?)?(?:\s+\(cost=[^)]*\))?\s+\(actual rows=([\d.]+)", s)
        if node and not in_planning:
            name = node.group(1).strip()
            if node.group(2):
                res["indexes"].append(node.group(2))
            if res["rows_out"] == "":
                res["rows_out"] = node.group(3)
            depth = (len(l) - len(l.lstrip())) // 2
            res["shape"].append((depth, name))
        m = re.match(r"Bitmap Index Scan on (\S+)", s.replace("-> ", "").strip())
        if m:
            res["indexes"].append(m.group(1))
    return res


def shape_str(shape):
    # compact pre-order rendering: Node(child, child)
    out = []
    stack = []
    for depth, name in shape:
        while stack and stack[-1] >= depth:
            stack.pop()
            out.append(")")
        if out and out[-1] not in ("(",):
            out.append(",")
        out.append(name)
        out.append("(")
        stack.append(depth)
    while stack:
        stack.pop()
        out.append(")")
    s = "".join(out).replace("()", "").replace("(,", "(")
    return s


def main():
    raw_path, out_txt, query, variant, mode = sys.argv[1:6]
    with open(raw_path) as f:
        content = f.read().splitlines()
    runs, cur = [], None
    errors = [l for l in content if "ERROR" in l]
    for l in content:
        if l.startswith("==RUN"):
            cur = []
            runs.append(cur)
        elif cur is not None:
            cur.append(l)
    parsed = [parse_run(r) for r in runs]
    measured = [p for p in parsed[1:] if p["exec"] is not None] or [p for p in parsed if p["exec"] is not None]
    with open(out_txt, "w") as f:
        f.write(f"# query={query} variant={variant} mode={mode}\n")
        if errors:
            f.write("# ERRORS:\n" + "\n".join(errors) + "\n")
        if not measured:
            f.write("\n".join(trim(l) for l in content))
            print("\t".join([query, variant, mode, "ERR"] + [""] * 11))
            return
        execs = [p["exec"] for p in measured]
        med = sorted(measured, key=lambda p: p["exec"])[len(measured) // 2]
        f.write("# exec_ms runs (warm-up excluded): " + ", ".join(f"{e:.3f}" for e in execs) + "\n")
        f.write("# median run:\n")
        f.write("\n".join(trim(l) for l in med["lines"]) + "\n")
    idx = []
    for i in med["indexes"]:
        if i not in idx:
            idx.append(i)
    print("\t".join([
        query, variant, mode,
        f"{statistics.median(execs):.3f}",
        "/".join(f"{e:.2f}" for e in execs),
        f"{med['plan_ms']:.3f}" if med["plan_ms"] is not None else "",
        str(med["hit"]), str(med["read"]), str(med["temp"]),
        "yes" if med["spill"] else "no",
        str(med["removed"]), med["workers"], med["rows_out"],
        ",".join(idx) or "-",
        shape_str(med["shape"]),
    ]))


if __name__ == "__main__":
    main()
