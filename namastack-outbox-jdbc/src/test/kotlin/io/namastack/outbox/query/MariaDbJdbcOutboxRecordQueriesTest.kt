package io.namastack.outbox.query

import io.namastack.outbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MariaDbJdbcOutboxRecordQueriesTest {
    private val queries = MariaDbJdbcOutboxRecordQueries("outbox_record")

    @Test
    fun `strict record keys use the first incomplete record of the key`() {
        assertThat(queries.findStrictRecordKeys).isEqualTo(
            "SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) " +
                "AND o.status = :status " +
                "AND o.next_retry_at <= :now " +
                "AND o.completed_at IS NULL AND o.id = " +
                "( SELECT e.id FROM outbox_record e WHERE e.record_key = o.record_key " +
                "AND e.completed_at IS NULL ORDER BY e.created_at, e.id LIMIT 1 ) " +
                "ORDER BY o.created_at, o.id LIMIT :limit",
        )
    }

    @Test
    fun `non-strict record keys use the first due incomplete record of the key`() {
        assertThat(queries.findNonStrictRecordKeys).isEqualTo(
            "SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) " +
                "AND o.status = :status " +
                "AND o.next_retry_at <= :now " +
                "AND o.completed_at IS NULL AND o.id = " +
                "( SELECT e.id FROM outbox_record e WHERE e.record_key = o.record_key " +
                "AND e.completed_at IS NULL AND e.status = :status " +
                "AND e.next_retry_at <= :now ORDER BY e.created_at, e.id LIMIT 1 ) " +
                "ORDER BY o.created_at, o.id LIMIT :limit",
        )
    }

    @Test
    fun `oldest statements order by time and take the first row`() {
        assertThat(queries.oldestCreatedAtByPartitionsStatus).isEqualTo(
            "SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status " +
                "AND completed_at IS NULL ORDER BY created_at, id LIMIT :limit",
        )
        assertThat(queries.oldestDueNonStrict).isEqualTo(
            "SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) " +
                "AND o.status = :status " +
                "AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT :limit",
        )
    }

    @Test
    fun `other statements are the portable statements`() {
        val default = DefaultJdbcOutboxRecordQueries("outbox_record", "LIMIT :limit")

        assertThat(queries.oldestDueStrict).isEqualTo(default.oldestDueStrict)
        assertThat(queries.findByStatusAndCompletedBefore).isEqualTo(default.findByStatusAndCompletedBefore)
        assertThat(queries.countByPartitionsStatus(NEW))
            .isEqualTo(default.countByPartitionsStatus(NEW))
    }
}
