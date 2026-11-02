package io.namastack.outbox

import io.namastack.outbox.query.H2JdbcOutboxRecordQueries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

class JdbcBulkRecordLoadingH2IntegrationTest : AbstractJdbcBulkRecordLoadingTest() {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Test
    fun `metadata first loading retains large unicode payload and context values and null context`() {
        val payload = "東京 Grüße 🐘".repeat(10000)
        val context = "контекст 東京".repeat(10000)
        jdbc
            .sql(
                """
                INSERT INTO outbox_record
                (id, status, record_key, record_type, payload, context, created_at, failure_count, next_retry_at, partition_no, handler_id)
                VALUES (:id, 'NEW', 'large', 'java.lang.String', :payload, :context, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 1, 'handler')
                """.trimIndent(),
            ).param("id", "large")
            .param("payload", payload)
            .param("context", context)
            .update()
        val rows =
            jdbc
                .sql(H2JdbcOutboxRecordQueries("outbox_record").findByKeysAndStatus)
                .param("recordKeys", arrayOf("large"))
                .param("status", "NEW")
                .query(JdbcOutboxRecordEntityRowMapper())
                .list()
        assertThat(rows.single().payload).isEqualTo(payload)
        assertThat(rows.single().context).isEqualTo(context)
        jdbc.sql("UPDATE outbox_record SET context = NULL WHERE id = 'large'").update()
        val row =
            jdbc
                .sql(H2JdbcOutboxRecordQueries("outbox_record").findByKeysAndStatus)
                .param("recordKeys", arrayOf("large"))
                .param("status", "NEW")
                .query(JdbcOutboxRecordEntityRowMapper())
                .single()
        assertThat(row.context).isNull()
    }
}
