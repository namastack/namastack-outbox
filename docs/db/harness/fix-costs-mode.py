#!/usr/bin/env python3
"""One-off fix for summaries written before run-explain labelled --costs runs: rows measured with a
single run (no '/' in exec_ms_runs) and mode 'custom' are relabelled 'custom-costs'."""
import sys
for p in sys.argv[1:]:
    lines = open(p).read().splitlines()
    out = [lines[0]]
    for l in lines[1:]:
        f = l.split("\t")
        if len(f) > 4 and f[2] == "custom" and "/" not in f[4] and f[3] != "ERR":
            f[2] = "custom-costs"
        out.append("\t".join(f))
    open(p, "w").write("\n".join(out) + "\n")
