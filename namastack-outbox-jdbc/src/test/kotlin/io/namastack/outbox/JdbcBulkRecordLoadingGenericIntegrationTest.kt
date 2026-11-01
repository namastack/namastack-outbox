package io.namastack.outbox

import io.namastack.outbox.query.GenericJdbcOutboxRecordQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/** Runs the shared repository contract with portable SQL and real JDBC row limits on H2. */
class JdbcBulkRecordLoadingGenericIntegrationTest : AbstractJdbcBulkRecordLoadingTest() {
    override fun createRecordRepository(
        dataSource: DataSource,
        transactionTemplate: TransactionTemplate,
        payloadSerializer: OutboxPayloadSerializer,
        clock: Clock,
    ): OutboxRecordRepository =
        JdbcOutboxRecordRepository(
            jdbcClient = JdbcClient.create(dataSource),
            transactionTemplate = transactionTemplate,
            entityMapper = JdbcOutboxRecordEntityMapper(payloadSerializer),
            clock = clock,
            queryProvider = { GenericJdbcOutboxRecordQueries("outbox_record") },
        )
}
