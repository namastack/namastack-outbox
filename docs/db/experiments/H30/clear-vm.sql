-- H30: clear the visibility map of every page holding COMPLETED rows (a rolled-back DELETE clears PD_ALL_VISIBLE),
-- reproducing pages touched by updates that autovacuum has not processed yet. ~1 s on 1M rows.
-- Baseline note: the -generic and -costs runs of countByStatus(COMPLETED) in results/base-* ran after the rolled-back
-- deleteByStatus/deleteByIds files and show Heap Fetches 993,800 / 82 ms; the custom run (before them) 0 / 24 ms.
BEGIN;
DELETE FROM outbox_record WHERE status = 'COMPLETED';
ROLLBACK;
