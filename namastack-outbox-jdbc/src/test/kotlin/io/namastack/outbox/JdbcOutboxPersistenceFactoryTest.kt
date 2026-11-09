package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.completion.OutboxCompletion
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.partition.PartitionAssignment
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import javax.sql.DataSource

class JdbcOutboxPersistenceFactoryTest {
    @Test
    fun `completion writers update and delete only their own namespace`() {
        val dataSource = createDataSource()
        val jdbc = JdbcClient.create(dataSource)
        createTables(jdbc, "completion_orders_")
        createTables(jdbc, "completion_billing_")
        val manager = DataSourceTransactionManager(dataSource)
        val serializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build())
        val clock = Clock.systemUTC()
        val orders = createPersistence(dataSource, manager, serializer, clock, "completion_orders_")
        val billing = createPersistence(dataSource, manager, serializer, clock, "completion_billing_")
        val record = createRecord("key", clock)
        orders.recordRepository.save(record)
        billing.recordRepository.save(record)
        val writer = requireNotNull(orders.completionWriter)
        val update = OutboxCompletion.Update.from(record).copy(status = COMPLETED, completedAt = clock.instant())
        assertThat(writer.writeUpdates(listOf(update)).single().isSuccess).isTrue()
        assertThat(orders.recordRepository.countByStatus(COMPLETED)).isEqualTo(1)
        assertThat(billing.recordRepository.countByStatus(NEW)).isEqualTo(1)
        assertThat(writer.writeDeletions(listOf(OutboxCompletion.Delete(record.id))).single().isSuccess).isTrue()
        assertThat(orders.recordRepository.countByStatus(COMPLETED)).isZero()
        assertThat(billing.recordRepository.countByStatus(NEW)).isEqualTo(1)
    }

    @Test
    fun `creates isolated programmatic repositories for two namespaces on one datasource`() {
        val dataSource = createDataSource()
        val jdbcClient = JdbcClient.create(dataSource)

        createTables(jdbcClient, "orders_")
        createTables(jdbcClient, "billing_")

        val transactionManager = DataSourceTransactionManager(dataSource)
        val serializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build())
        val clock = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), UTC)
        val orders = createPersistence(dataSource, transactionManager, serializer, clock, "orders_")
        val billing = createPersistence(dataSource, transactionManager, serializer, clock, "billing_")

        orders.recordRepository.create(createRecord("orders", clock))
        billing.recordRepository.create(createRecord("billing", clock))
        orders.instanceRepository.save(OutboxInstance.create("orders", "localhost", 8080, ACTIVE, clock))
        billing.instanceRepository.save(OutboxInstance.create("billing", "localhost", 8081, ACTIVE, clock))
        orders.partitionAssignmentRepository.saveAll(setOf(PartitionAssignment.create(1, "orders", clock, null)))
        billing.partitionAssignmentRepository.saveAll(setOf(PartitionAssignment.create(2, "billing", clock, null)))

        assertThat(orders.recordRepository.findIncompleteRecordsByRecordKey("orders")).hasSize(1)
        assertThat(orders.recordRepository.findIncompleteRecordsByRecordKey("billing")).isEmpty()
        assertThat(billing.recordRepository.findIncompleteRecordsByRecordKey("billing")).hasSize(1)
        assertThat(billing.recordRepository.findIncompleteRecordsByRecordKey("orders")).isEmpty()
        val ordersPages = orders.recordRepository.findIncompleteRecordPages(setOf("orders", "billing"))
        val billingPages = billing.recordRepository.findIncompleteRecordPages(setOf("orders", "billing"))
        assertThat(ordersPages.getValue("orders").size).isEqualTo(1)
        assertThat(ordersPages.getValue("billing").isEmpty()).isTrue()
        assertThat(billingPages.getValue("billing").size).isEqualTo(1)
        assertThat(billingPages.getValue("orders").isEmpty()).isTrue()
        assertThat(orders.instanceRepository.findAll().map(OutboxInstance::instanceId)).containsExactly("orders")
        assertThat(billing.instanceRepository.findAll().map(OutboxInstance::instanceId)).containsExactly("billing")
        assertThat(orders.partitionAssignmentRepository.findAll().map(PartitionAssignment::partitionNumber))
            .containsExactly(1)
        assertThat(billing.partitionAssignmentRepository.findAll().map(PartitionAssignment::partitionNumber))
            .containsExactly(2)
    }

    @Test
    fun `accepts database-specific names from a custom resolver without accessing the datasource`() {
        val dataSource = mock(DataSource::class.java)
        val transactionManager = mock(PlatformTransactionManager::class.java)
        val serializer = mock(OutboxPayloadSerializer::class.java)
        val customResolver =
            object : JdbcTableNameResolver {
                override val outboxRecord = "\"OutboxSchema\".\"OutboxRecord\""
                override val outboxInstance = "[database].[schema].[outbox_instance]"
                override val outboxPartitionAssignment = "catalog.schema.outbox_partition"
            }

        val persistence =
            JdbcOutboxPersistenceFactory.create(
                dataSource = dataSource,
                transactionManager = transactionManager,
                payloadSerializer = serializer,
                clock = Clock.systemUTC(),
                tableNameResolver = customResolver,
            )

        assertThat(persistence.recordRepository).isNotNull()
        assertThat(persistence.instanceRepository).isNotNull()
        assertThat(persistence.partitionAssignmentRepository).isNotNull()
        verifyNoInteractions(dataSource, transactionManager, serializer)
    }

    @Test
    fun `record repository opens no connection at construction and detects the database once on first use`() {
        val metadata = mockk<DatabaseMetaData> { every { databaseProductName } returns "SQLite" }
        val connection = mockk<Connection>(relaxed = true) { every { metaData } returns metadata }
        val dataSource = mockk<DataSource> { every { getConnection() } returns connection }
        val persistence =
            JdbcOutboxPersistenceFactory.create(
                dataSource = dataSource,
                transactionManager = DataSourceTransactionManager(dataSource),
                payloadSerializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build()),
                clock = Clock.systemUTC(),
            )
        verify(exactly = 0) { dataSource.connection }

        repeat(2) { persistence.recordRepository.countByStatuses(emptySet()) }
        verify(exactly = 0) { dataSource.connection }

        runCatching { persistence.recordRepository.countByStatus(NEW) }
        runCatching { persistence.recordRepository.countByStatus(NEW) }
        verify(exactly = 1) { metadata.databaseProductName }
    }

    @Test
    fun `record selection counts and ages read only the table of their namespace`() {
        val dataSource = createDataSource()
        val jdbcClient = JdbcClient.create(dataSource)
        createTables(jdbcClient, "sales_")
        createTables(jdbcClient, "audit_")
        val transactionManager = DataSourceTransactionManager(dataSource)
        val serializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build())
        val clock = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), UTC)
        val sales = createPersistence(dataSource, transactionManager, serializer, clock, "sales_").recordRepository
        val audit = createPersistence(dataSource, transactionManager, serializer, clock, "audit_").recordRepository
        val record = sales.create(createRecord("sales", clock))
        val partitions = setOf(record.partition)
        val now = Instant.now(clock)

        listOf(true, false).forEach { strict ->
            assertThat(sales.findRecordKeysInPartitions(partitions, 10, strict)).containsExactly("sales")
            assertThat(audit.findRecordKeysInPartitions(partitions, 10, strict)).isEmpty()
            assertThat(sales.findOldestDueNextRetryAtByPartitions(partitions, now, strict)).isNotNull()
            assertThat(audit.findOldestDueNextRetryAtByPartitions(partitions, now, strict)).isNull()
        }
        assertThat(sales.findOldestCreatedAtByPartitions(partitions)).isEqualTo(record.createdAt)
        assertThat(audit.findOldestCreatedAtByPartitions(partitions)).isNull()
        assertThat(sales.countByStatuses(setOf(NEW))).containsExactlyEntriesOf(mapOf(NEW to 1L))
        assertThat(audit.countByStatuses(setOf(NEW))).containsExactlyEntriesOf(mapOf(NEW to 0L))
        assertThat(sales.countRecordsByPartitions(partitions, NEW)).isEqualTo(1)
        assertThat(audit.countRecordsByPartitions(partitions, NEW)).isZero()
    }

    private fun createPersistence(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        serializer: OutboxPayloadSerializer,
        clock: Clock,
        tablePrefix: String,
    ) = JdbcOutboxPersistenceFactory.create(
        dataSource = dataSource,
        transactionManager = transactionManager,
        payloadSerializer = serializer,
        clock = clock,
        tableNameResolver = DefaultJdbcTableNameResolver(tablePrefix = tablePrefix),
    )

    private fun createRecord(
        key: String,
        clock: Clock,
    ): OutboxRecord<String> =
        OutboxRecord
            .Builder<String>()
            .key(key)
            .payload("$key-payload")
            .handlerId("$key-handler")
            .build(clock)

    private fun createDataSource(): DataSource =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:jdbc-outbox-persistence-factory;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

    private fun createTables(
        jdbcClient: JdbcClient,
        tablePrefix: String,
    ) {
        jdbcClient
            .sql(
                """
                CREATE TABLE ${tablePrefix}outbox_record (
                    id VARCHAR(255) PRIMARY KEY,
                    status VARCHAR(20) NOT NULL,
                    record_key VARCHAR(255) NOT NULL,
                    record_type VARCHAR(255) NOT NULL,
                    payload CLOB NOT NULL,
                    context CLOB,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    failure_count INT NOT NULL,
                    failure_reason VARCHAR(1000),
                    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    partition_no INTEGER NOT NULL,
                    handler_id VARCHAR(1000) NOT NULL
                )
                """.trimIndent(),
            ).update()
        jdbcClient
            .sql(
                """
                CREATE TABLE ${tablePrefix}outbox_instance (
                    instance_id VARCHAR(255) PRIMARY KEY,
                    hostname VARCHAR(255) NOT NULL,
                    port INTEGER NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_heartbeat TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """.trimIndent(),
            ).update()
        jdbcClient
            .sql(
                """
                CREATE TABLE ${tablePrefix}outbox_partition (
                    partition_number INTEGER PRIMARY KEY,
                    instance_id VARCHAR(255),
                    version BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """.trimIndent(),
            ).update()
    }
}
