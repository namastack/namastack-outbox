# G1 iteration 1 experiment drivers (H01–H04)
- `make-qdirs.sh` builds `qdirs/{base,H01..H04}` (harness/queries overlaid with each H's files, plus a
  `partlist_override` hook). Run it first; the drivers expect `qdirs/`.
- `run-edge.sh` equivalence on the edge seed (with/without `key_ties=1`, custom + generic) -> `results/G1-edge/`.
- `run-scenario.sh <S> [--reg] [--h03b]` one container, states applied post-seed: control -> H01 -> H02 -> H03 -> H04
  [-> H03b]; target queries in all modes, equivalence, optional full-suite regression -> `results/{G1ctl,H0x,H0x-reg}-<S>/`.
- `run-writecost.sh A B` fresh container per index set (pre-seed DDL) -> `results/G1-wc-<S>/`.
- `eq-check.sh` / `eq-suite.sh` equivalence with tie analysis; `report.py`, `reg-compare.py` tables.
