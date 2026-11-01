-- Dedicated benchmark database only. Recreate the known tables so neither old rows nor old
-- index definitions can leak from another branch. Files in reports/ are retained on the host.
SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots
WHERE starts_with(slot_name, 'namastack_perf_order_') AND NOT active;

DROP TABLE IF EXISTS performance_seed_record, performance_test_run,
    outbox_record, outbox_partition, outbox_instance CASCADE;
