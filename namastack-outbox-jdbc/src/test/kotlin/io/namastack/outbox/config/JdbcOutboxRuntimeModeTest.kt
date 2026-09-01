package io.namastack.outbox.config

import io.namastack.outbox.JdbcTableNameResolver
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class JdbcOutboxRuntimeModeTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JdbcOutboxAutoConfiguration::class.java,
                    JdbcOutboxSchemaAutoConfiguration::class.java,
                ),
            )

    @Test
    fun `channels mode omits JDBC single runtime persistence`() {
        contextRunner
            .withPropertyValues("namastack.outbox.mode=channels")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(JdbcTableNameResolver::class.java)
                assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
                assertThat(context).doesNotHaveBean(OutboxInstanceRepository::class.java)
                assertThat(context).doesNotHaveBean(PartitionAssignmentRepository::class.java)
                assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
                assertThat(context).doesNotHaveBean("outboxJdbcClient")
                assertThat(context).doesNotHaveBean("outboxTransactionTemplate")
            }
    }
}
