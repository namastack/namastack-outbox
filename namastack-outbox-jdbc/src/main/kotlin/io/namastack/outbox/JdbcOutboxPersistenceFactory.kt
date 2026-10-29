package io.namastack.outbox

import io.namastack.outbox.runtime.OutboxRuntimePersistence
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * Constructs JDBC repositories for one fully resolved outbox table namespace.
 *
 * The supplied datasource and transaction manager are borrowed and are never closed by the returned
 * persistence. Construction snapshots all table names without opening a connection. Custom table
 * name resolvers are trusted and retain full control over database-specific identifier syntax.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
object JdbcOutboxPersistenceFactory {
    /**
     * Creates the three JDBC repository implementations required by one outbox runtime.
     *
     * @param dataSource Datasource used by all repositories
     * @param transactionManager Transaction manager used for repository transactions
     * @param payloadSerializer Serializer used for record payloads and context
     * @param clock Clock used for time-based record operations
     * @param tableNameResolver Resolver providing one complete physical table namespace
     * @return Runtime persistence containing the three JDBC repositories
     */
    fun create(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        payloadSerializer: OutboxPayloadSerializer,
        clock: Clock,
        tableNameResolver: JdbcTableNameResolver = DefaultJdbcTableNameResolver(),
    ): OutboxRuntimePersistence =
        createRepositories(dataSource, transactionManager, payloadSerializer, clock, tableNameResolver, null)

    /** Creates repositories borrowing the supplied caller-owned completion writer. */
    fun create(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        payloadSerializer: OutboxPayloadSerializer,
        clock: Clock,
        tableNameResolver: JdbcTableNameResolver = DefaultJdbcTableNameResolver(),
        completionBatcher: PostgresOutboxCompletionBatcher,
    ): OutboxRuntimePersistence =
        createRepositories(
            dataSource,
            transactionManager,
            payloadSerializer,
            clock,
            tableNameResolver,
            completionBatcher,
        )

    private fun createRepositories(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        payloadSerializer: OutboxPayloadSerializer,
        clock: Clock,
        tableNameResolver: JdbcTableNameResolver,
        completionBatcher: PostgresOutboxCompletionBatcher?,
    ): OutboxRuntimePersistence {
        require(completionBatcher == null || completionBatcher.tableName == tableNameResolver.outboxRecord) {
            "Completion batcher and runtime must address the same outbox table"
        }
        val jdbcClient = JdbcClient.create(dataSource)
        val transactionTemplate = TransactionTemplate(transactionManager)

        return OutboxRuntimePersistence(
            recordRepository =
                JdbcOutboxRecordRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    entityMapper = JdbcOutboxRecordEntityMapper(payloadSerializer),
                    clock = clock,
                    tableNameResolver = tableNameResolver,
                    completionBatcher = completionBatcher,
                ),
            instanceRepository =
                JdbcOutboxInstanceRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    tableNameResolver = tableNameResolver,
                ),
            partitionAssignmentRepository =
                JdbcOutboxPartitionAssignmentRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    tableNameResolver = tableNameResolver,
                ),
        )
    }

    /**
     * Creates a caller-owned PostgreSQL completion writer for programmatic runtimes.
     * Pass it to [create] and close it after every runtime borrowing it has stopped.
     */
    fun completionBatcher(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        tableNameResolver: JdbcTableNameResolver = DefaultJdbcTableNameResolver(),
        maxBatchSize: Int = 20,
        maxWait: Duration = Duration.ofNanos(250_000),
    ): PostgresOutboxCompletionBatcher {
        dataSource.connection.use { connection ->
            require(connection.metaData.databaseProductName == "PostgreSQL") {
                "Completion batching currently supports PostgreSQL only"
            }
        }
        return PostgresOutboxCompletionBatcher(
            JdbcClient.create(dataSource),
            TransactionTemplate(transactionManager),
            tableNameResolver.outboxRecord,
            maxBatchSize,
            maxWait,
        )
    }
}
