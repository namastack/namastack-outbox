package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.partition.PartitionAssignment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import javax.sql.DataSource

class JdbcOutboxPersistenceFactoryTest {
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

        orders.recordRepository.save(createRecord("orders", clock))
        billing.recordRepository.save(createRecord("billing", clock))
        orders.instanceRepository.save(OutboxInstance.create("orders", "localhost", 8080, ACTIVE, clock))
        billing.instanceRepository.save(OutboxInstance.create("billing", "localhost", 8081, ACTIVE, clock))
        orders.partitionAssignmentRepository.saveAll(setOf(PartitionAssignment.create(1, "orders", clock, null)))
        billing.partitionAssignmentRepository.saveAll(setOf(PartitionAssignment.create(2, "billing", clock, null)))

        assertThat(orders.recordRepository.findIncompleteRecordsByRecordKey("orders")).hasSize(1)
        assertThat(orders.recordRepository.findIncompleteRecordsByRecordKey("billing")).isEmpty()
        assertThat(billing.recordRepository.findIncompleteRecordsByRecordKey("billing")).hasSize(1)
        assertThat(billing.recordRepository.findIncompleteRecordsByRecordKey("orders")).isEmpty()
        assertThat(orders.instanceRepository.findAll().map(OutboxInstance::instanceId)).containsExactly("orders")
        assertThat(billing.instanceRepository.findAll().map(OutboxInstance::instanceId)).containsExactly("billing")
        assertThat(orders.partitionAssignmentRepository.findAll().map(PartitionAssignment::partitionNumber))
            .containsExactly(1)
        assertThat(billing.partitionAssignmentRepository.findAll().map(PartitionAssignment::partitionNumber))
            .containsExactly(2)
        assertThat(orders.ownedResources).isEmpty()
        assertThat(billing.ownedResources).isEmpty()
    }

    @Test
    fun `rejects invalid resolved names before accessing the datasource`() {
        val dataSource = mock(DataSource::class.java)
        val transactionManager = mock(PlatformTransactionManager::class.java)
        val serializer = mock(OutboxPayloadSerializer::class.java)
        val invalidResolver =
            object : JdbcTableNameResolver {
                override val outboxRecord = "outbox_record; DROP TABLE users"
                override val outboxInstance = "outbox_instance"
                override val outboxPartitionAssignment = "outbox_partition"
            }

        assertThatThrownBy {
            JdbcOutboxPersistenceFactory.create(
                dataSource = dataSource,
                transactionManager = transactionManager,
                payloadSerializer = serializer,
                clock = Clock.systemUTC(),
                tableNameResolver = invalidResolver,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid JDBC outbox table name")

        verifyNoInteractions(dataSource, transactionManager, serializer)
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
        tableNameResolver = JdbcOutboxTableNamespace(tablePrefix = tablePrefix),
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
