package io.namastack.outbox

import io.namastack.outbox.runtime.OutboxRuntimePersistence
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
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
     * @return Runtime persistence containing the three JDBC repositories and no owned resources
     */
    fun create(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        payloadSerializer: OutboxPayloadSerializer,
        clock: Clock,
        tableNameResolver: JdbcTableNameResolver = JdbcOutboxTableNamespace(),
    ): OutboxRuntimePersistence {
        val resolvedTableNames = snapshot(tableNameResolver)
        val jdbcClient = JdbcClient.create(dataSource)
        val transactionTemplate = TransactionTemplate(transactionManager)

        return OutboxRuntimePersistence(
            recordRepository =
                JdbcOutboxRecordRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    entityMapper = JdbcOutboxRecordEntityMapper(payloadSerializer),
                    clock = clock,
                    tableNameResolver = resolvedTableNames,
                ),
            instanceRepository =
                JdbcOutboxInstanceRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    tableNameResolver = resolvedTableNames,
                ),
            partitionAssignmentRepository =
                JdbcOutboxPartitionAssignmentRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    tableNameResolver = resolvedTableNames,
                ),
        )
    }

    private fun snapshot(tableNameResolver: JdbcTableNameResolver): JdbcTableNameResolver =
        object : JdbcTableNameResolver {
            override val outboxRecord = tableNameResolver.outboxRecord
            override val outboxInstance = tableNameResolver.outboxInstance
            override val outboxPartitionAssignment = tableNameResolver.outboxPartitionAssignment
        }
}
