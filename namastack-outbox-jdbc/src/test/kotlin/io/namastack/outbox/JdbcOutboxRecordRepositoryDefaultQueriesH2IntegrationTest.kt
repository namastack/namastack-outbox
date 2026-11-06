package io.namastack.outbox

import io.namastack.outbox.query.DefaultJdbcOutboxRecordQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/**
 * Runs the repository contract with the portable statements an unsupported database receives: no row-limit
 * clause, so `withMaxRows` alone must honour every limit.
 *
 * @author Aleksander Zamojski
 */
class JdbcOutboxRecordRepositoryDefaultQueriesH2IntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    override fun createRepository(
        source: DataSource,
        transactions: TransactionTemplate,
        serializer: OutboxPayloadSerializer,
        clock: Clock,
    ): OutboxRecordRepository =
        JdbcOutboxRecordRepository(
            jdbcClient = JdbcClient.create(source),
            transactionTemplate = transactions,
            entityMapper = JdbcOutboxRecordEntityMapper(serializer),
            clock = clock,
            queries = { DefaultJdbcOutboxRecordQueries(DefaultJdbcTableNameResolver().outboxRecord) },
        )
}
