package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

class JdbcOutboxSchedulingTransactionTest {
    private lateinit var database: EmbeddedDatabase
    private lateinit var jdbcClient: JdbcClient
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var outbox: Outbox

    @BeforeEach
    fun setUp() {
        database =
            EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema/h2/outbox-tables.sql")
                .build()
        jdbcClient = JdbcClient.create(database)
        jdbcClient.sql("CREATE TABLE orders (id VARCHAR(255) PRIMARY KEY)").update()
        val transactionManager = DataSourceTransactionManager(database)
        transactionTemplate = TransactionTemplate(transactionManager)
        val clock = Clock.systemUTC()
        val persistence =
            JdbcOutboxPersistenceFactory.create(
                dataSource = database,
                transactionManager = transactionManager,
                payloadSerializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build()),
                clock = clock,
            )
        val handlerRegistry = mockk<OutboxHandlerRegistry>()
        val handlers =
            listOf("first-handler", "second-handler").map { handlerId ->
                mockk<TypedHandlerMethod> { every { id } returns handlerId }
            }
        every { handlerRegistry.getHandlersForPayloadType(any()) } returns emptyList()
        every { handlerRegistry.getHandlersForPayloadType(String::class) } returns handlers
        every { handlerRegistry.getGenericHandlers(any(), any()) } returns emptyList()
        outbox =
            OutboxService(
                contextCollector = OutboxContextCollector(emptyList()),
                handlerRegistry = handlerRegistry,
                outboxRecordRepository = persistence.recordRepository,
                clock = clock,
            )
    }

    @AfterEach
    fun tearDown() {
        database.shutdown()
    }

    @Test
    fun `rejects scheduling outside a transaction without writing records`() {
        assertThatThrownBy { outbox.schedule("created", "order-1") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires an active transaction")

        assertThat(count("outbox_record")).isZero()
    }

    @Test
    fun `commits business data and every handler record together`() {
        transactionTemplate.executeWithoutResult {
            insertOrder()
            outbox.schedule("created", "order-1")
        }

        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("outbox_record")).isEqualTo(2)
    }

    @Test
    fun `business rollback removes every scheduled handler record`() {
        transactionTemplate.executeWithoutResult { status ->
            insertOrder()
            outbox.schedule("created", "order-1")
            assertThat(count("outbox_record")).isEqualTo(2)
            status.setRollbackOnly()
        }

        assertThat(count("orders")).isZero()
        assertThat(count("outbox_record")).isZero()
    }

    @Test
    fun `failure saving a later handler rolls back business data and earlier handler records`() {
        jdbcClient
            .sql(
                "ALTER TABLE outbox_record ADD CONSTRAINT reject_second_handler " +
                    "CHECK (handler_id <> 'second-handler')",
            ).update()

        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                insertOrder()
                outbox.schedule("created", "order-1")
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(count("orders")).isZero()
        assertThat(count("outbox_record")).isZero()
    }

    private fun insertOrder() {
        jdbcClient.sql("INSERT INTO orders (id) VALUES ('order-1')").update()
    }

    private fun count(table: String): Long =
        jdbcClient
            .sql("SELECT COUNT(*) FROM $table")
            .query(Long::class.java)
            .single()
}
