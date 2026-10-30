# G2 iteration 1 experiment drivers (H10–H13, findIncompleteRecordPages / findIncompleteRecordsByRecordKey)
- `make-qdirs.sh` builds `qdirs/{base,H11,H12,H13,H13c}` (harness/queries overlaid; H13c = `H13/queries-h11`, ANY + `completed_at IS NULL`). Run first.
- `run-scenario.sh <S> [--reg]` one container (`outbox-db-H11`), states applied post-seed in place:
  control -> H11 -> H13 -> +H02 idx (H12, H13c) -> +H04 idx (H12) -> H12-alt (B, D) -> H10 add-only -> H10 replace -> H10 noid (B, D).
  Target queries custom / generic / costs (D: + `rpc11`, `nobitmap` tags); equivalence vs the control output
  (`eq.sh`, ordered md5, keys 10/100/1000, custom + generic) -> `results/G2-eq-<S>.txt`; `--reg` adds the full read suite
  on control, H10 add-only and H10 replace -> `results/{G2ctl,H10add,H10}-reg-<S>/`.
- `run-edge.sh` same equivalence on the edge seed (key_ties 0/1) + H11 negative control -> `results/G2-eq-edge.txt`.
- `run-diag.sh A B` A/B with `enable_bitmapscan=off` / `random_page_cost=1.1` (ordered-path runtime) into the same labels.
- `run-writecost.sh A B` fresh container per index set (pre-seed DDL): base, H10 replace, H10 add -> `results/G2-wc-<S>/`.
- `report.py` before -> after tables; regression diff with `../G1-iter1/reg-compare.py`.
