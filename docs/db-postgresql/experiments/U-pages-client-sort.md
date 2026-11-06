# `pages` without `ORDER BY`, client sort (user idea 1)

Verdict: **not adopted**. Keep `unnest` + `LATERAL` with inner `ORDER BY r.created_at, r.id`.

- In the S10 loop, `IN` / `= ANY` without `ORDER BY` have slow passes (custom plans walking queue index A or C with a `record_key` filter), 9.85–16.9 ms vs ~1 ms, also on the proposed indexes (`R3X-screen-extension.md`, X0).
- `unnest` without `ORDER BY` flattens to a hash join unless fenced with `OFFSET 0`. The fenced form is fast, but shows no gain: S8-S5 64 vs 70 ms, S10 1251 vs 1265 proc/s, ranges overlap (`R3C-confirm.md`). B1 already delivers `(created_at, id)` order, so the inner `ORDER BY` costs nothing to remove.
