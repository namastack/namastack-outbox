package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class JpaOutboxRuntimeModeTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaOutboxAutoConfiguration::class.java))

    @Test
    fun `channels mode omits JPA single runtime persistence`() {
        contextRunner
            .withPropertyValues("namastack.outbox.mode=channels")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(HibernatePropertiesCustomizer::class.java)
                assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
                assertThat(context).doesNotHaveBean(OutboxInstanceRepository::class.java)
                assertThat(context).doesNotHaveBean(PartitionAssignmentRepository::class.java)
                assertThat(context).doesNotHaveBean("outboxEntityManager")
                assertThat(context).doesNotHaveBean("outboxTransactionTemplate")
            }
    }
}
