# Brief 03 — Run a measurement matrix

You are a sub-agent (Sonnet). The orchestrator gives you: run name, lane(s), variants (name, worktree, optional DDL/query overrides),
cells (labels and kinds), pass order, output root, and what may run in parallel.

## Isolation rule

See `docs/db-mysql/briefs/00-isolation-rule.md` (pasted into your prompt). Use only the harness in `docs/db-mysql/harness/`.
You may not read the reference file.

## Task

1. Record `git rev-parse HEAD` and `git status --short -- . ':!docs'` of every worktree; confirm they match the requested variants.
2. Start the run with `harness/bin/run-cells.sh` (lane 1 is the only lane: `source bin/lane.sh 1` in a subshell; groups
   `HOT,WRITE,MON` only — never RET/ADMIN). Long runs go in the
   background (`run_in_background`), and you wait for the completion notification instead of polling in a loop; check the log at
   most every 20–30 minutes.
3. Respect the parallelism rules of the orchestrator prompt: one driver at a time on this host; no Gradle build or other heavy work
   of yours while a timed step runs.
4. If a step fails or results look inconsistent (spread > 2× the noise threshold, a purge/flush-like outlier, host load > 3 at the
   step start), rerun that cell once and note it. Do not tune the database unless told to.
5. Run equivalence checks when asked (`HARNESS_SAMPLE_ORDERED=1`, groups `HOT,WRITE,MON`).
6. Write `results/<run name>/SUMMARY.md`: per group the slowest queries per variant, plan shape from `plans/*.summary.txt`
   (index, rows examined vs returned, filesort), `handlers.csv` highlights, write cost (redo bytes/write, pages/write, latency,
   index sizes), S9, S10, equivalence verdicts, anomalies and reruns. Tables use `bin/compare.py` output.

Report numbers as measured. Return a summary of ≤300 words with result paths and any failures.
