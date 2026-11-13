package io.namastack.outbox.config

import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.JacksonOutboxPayloadSerializer
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.completion.OutboxCompletion
import io.namastack.outbox.completion.OutboxCompletionWriter
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus.ACTIVE
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import javax.sql.DataSource

/**
 * Verifies that JDBC repositories and completion writes use the configured outbox template.
 *
 * @author Roland Beisel
 */
class JdbcOutboxSharedTemplateTest {
    private val clock = Clock.systemUTC()
    private val applicationDataSource = mockk<DataSource>()
    private val outboxDataSource =
        EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(H2)
            .addScript("schema/h2/outbox-tables.sql")
            .build()
    private val jdbc = JdbcTemplate(outboxDataSource)
    private val record =
        OutboxRecord
            .Builder<String>()
            .key("key")
            .payload("payload")
            .handlerId("handler")
            .build(clock)

    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceInitializationAutoConfiguration::class.java,
                    JdbcOutboxAutoConfiguration::class.java,
                    JdbcOutboxSchemaAutoConfiguration::class.java,
                    JdbcOutboxCompletionAutoConfiguration::class.java,
                ),
            ).withBean(OutboxPayloadSerializer::class.java, {
                JacksonOutboxPayloadSerializer(JsonMapper.builder().build())
            })
            .withPropertyValues(
                "namastack.outbox.completion-batching.enabled=true",
                "namastack.outbox.jdbc.schema-initialization.enabled=false",
            )

    private val customTemplateRunner =
        contextRunner
            .withBean(DataSource::class.java, { applicationDataSource })
            .withBean("outboxJdbcTemplate", NamedParameterJdbcTemplate::class.java, {
                NamedParameterJdbcTemplate(outboxDataSource)
            })
            .withBean("outboxTransactionTemplate", TransactionTemplate::class.java, {
                TransactionTemplate(DataSourceTransactionManager(outboxDataSource))
            })

    @AfterEach
    fun tearDown() {
        outboxDataSource.shutdown()
        verify { applicationDataSource wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `record repository uses the custom template with or without batching`(batching: Boolean) {
        customTemplateRunner
            .withPropertyValues("namastack.outbox.completion-batching.enabled=$batching")
            .run { context ->
                val repository = context.getBean<OutboxRecordRepository>()

                repository.create(record)

                assertThat(repository.findIncompleteRecordsByRecordKey("key").map { it.id }).containsExactly(record.id)
                assertThat(jdbc.queryForObject("SELECT id FROM outbox_record", String::class.java)).isEqualTo(record.id)
            }
    }

    @Test
    fun `completion writer updates records in the custom database`() {
        customTemplateRunner.run { context ->
            context.getBean<OutboxRecordRepository>().create(record)
            val update = OutboxCompletion.Update.from(record).copy(status = COMPLETED, completedAt = clock.instant())

            val result = context.getBean<OutboxCompletionWriter>().writeUpdates(listOf(update))

            assertThat(result.single().isSuccess).isTrue()
            assertThat(
                jdbc.queryForObject("SELECT status FROM outbox_record", String::class.java),
            ).isEqualTo("COMPLETED")
        }
    }

    @Test
    fun `completion writer deletes records in the custom database`() {
        customTemplateRunner.run { context ->
            context.getBean<OutboxRecordRepository>().create(record)

            val result =
                context.getBean<OutboxCompletionWriter>().writeDeletions(
                    listOf(OutboxCompletion.Delete(record.id)),
                )

            assertThat(result.single().isSuccess).isTrue()
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_record", Int::class.java)).isZero()
        }
    }

    @Test
    fun `instance repository uses the custom template`() {
        customTemplateRunner.run { context ->
            val repository = context.getBean<OutboxInstanceRepository>()

            repository.save(OutboxInstance.create("instance", "localhost", 8080, ACTIVE, clock))

            assertThat(jdbc.queryForObject("SELECT instance_id FROM outbox_instance", String::class.java))
                .isEqualTo("instance")
        }
    }

    @Test
    fun `partition repository uses the custom template`() {
        customTemplateRunner.run { context ->
            val repository = context.getBean<PartitionAssignmentRepository>()

            repository.saveAll(setOf(PartitionAssignment.create(1, "instance", clock, null)))

            assertThat(jdbc.queryForObject("SELECT partition_number FROM outbox_partition", Int::class.java)).isOne()
        }
    }

    @Test
    fun `custom repository replaces the default repository`() {
        val repository = mockk<OutboxRecordRepository>()

        customTemplateRunner
            .withBean(OutboxRecordRepository::class.java, { repository })
            .run { context ->
                assertThat(context.getBean<OutboxRecordRepository>()).isSameAs(repository)
                assertThat(context).hasSingleBean(OutboxCompletionWriter::class.java)
            }
    }

    @Test
    fun `custom writer replaces the default writer`() {
        val writer = mockk<OutboxCompletionWriter>()

        customTemplateRunner
            .withBean(OutboxCompletionWriter::class.java, { writer })
            .run { context ->
                assertThat(context.getBean<OutboxCompletionWriter>()).isSameAs(writer)
                assertThat(context).hasSingleBean(OutboxRecordRepository::class.java)
            }
    }

    @Test
    fun `default template uses the application datasource with schema initialization enabled`() {
        contextRunner
            .withBean(DataSource::class.java, { outboxDataSource })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(outboxDataSource) })
            .withPropertyValues("namastack.outbox.jdbc.schema-initialization.enabled=true")
            .run { context ->
                val template = context.getBean<NamedParameterJdbcTemplate>("outboxJdbcTemplate")

                assertThat(template.jdbcTemplate.dataSource).isSameAs(outboxDataSource)
            }
    }
}
