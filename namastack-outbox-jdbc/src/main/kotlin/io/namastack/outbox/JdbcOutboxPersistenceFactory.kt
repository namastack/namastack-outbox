package io.namastack.outbox

import io.namastack.outbox.config.JdbcDatabaseType
import io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory
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
     * The database type is detected from the datasource metadata when a record repository operation
     * first needs SQL, including inserts and updates. Construction opens no connection.
     *
     * @param dataSource Datasource used by all repositories
     * @param transactionManager Transaction manager for the supplied datasource
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
        create(dataSource, transactionManager, payloadSerializer, clock, tableNameResolver, databaseType = null)

    /**
     * Creates the three JDBC repository implementations required by one outbox runtime with an
     * optional database type.
     *
     * A supplied database type selects the record SQL dialect without accessing datasource metadata.
     * When it is `null`, the type is detected from `DatabaseMetaData.getDatabaseProductName()` when a
     * record repository operation first needs SQL. The resolved statements are cached for subsequent
     * operations. Unrecognized products use portable queries with JDBC row limits and require a
     * manually managed schema. Construction opens no connection in either case. Missing product names
     * or metadata-access failures are reported when the statements are first resolved.
     *
     * @param dataSource Datasource used by all repositories
     * @param transactionManager Transaction manager for the supplied datasource
     * @param payloadSerializer Serializer used for record payloads and context
     * @param clock Clock used for time-based record operations
     * @param tableNameResolver Resolver providing one complete physical table namespace
     * @param databaseType Database type of [dataSource], or `null` to detect it on first use
     * @return Runtime persistence containing the three JDBC repositories
     * @since 1.12.0
     */
    fun create(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        payloadSerializer: OutboxPayloadSerializer,
        clock: Clock,
        tableNameResolver: JdbcTableNameResolver,
        databaseType: JdbcDatabaseType?,
    ): OutboxRuntimePersistence {
        val queriesFactory = JdbcOutboxRecordQueriesFactory(tableNameResolver)
        val jdbcClient = JdbcClient.create(dataSource)
        val transactionTemplate = TransactionTemplate(transactionManager)

        return OutboxRuntimePersistence(
            recordRepository =
                JdbcOutboxRecordRepository(
                    jdbcClient = jdbcClient,
                    transactionTemplate = transactionTemplate,
                    entityMapper = JdbcOutboxRecordEntityMapper(payloadSerializer),
                    clock = clock,
                    queryProvider = {
                        queriesFactory.create(databaseType ?: JdbcDatabaseType.detect(dataSource))
                    },
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
}
