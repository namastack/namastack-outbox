package io.namastack.performance.tooling.report

internal object PrometheusQueries {
    const val throughput = """sum(rate(outbox_record_process_seconds_count{outbox_handler_kind="primary"}[5s]))"""
    const val throughputByConsumer = """sum by (consumer) (rate(outbox_record_process_seconds_count{outbox_handler_kind="primary"}[5s]))"""
    const val cpu = """process_cpu_usage{job="outbox-consumer"}"""
    const val memory = """sum by (consumer) (jvm_memory_used_bytes{job="outbox-consumer",area="heap"})"""
    const val postgres = """sum(rate(pg_stat_database_xact_commit{datname="outbox_performance_test"}[10s]))"""
}
