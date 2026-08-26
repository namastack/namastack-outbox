package io.namastack.outbox

import io.namastack.outbox.handler.JavaLambdaOutboxHandlerFactory
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

/** Verifies Java SAM handler discovery and bean-name identity end to end. */
@OutboxIntegrationTest
@Import(JavaLambdaHandlerIntegrationTest.LambdaConfiguration::class)
class JavaLambdaHandlerIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var handlerRegistry: OutboxHandlerRegistry

    @Autowired
    @Qualifier("javaLambdaHandler")
    private lateinit var javaLambdaHandler: OutboxHandler

    @AfterEach
    fun cleanup() {
        invocations.clear()
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity").executeUpdate()
            entityManager.flush()
            entityManager.clear()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `Java lambda uses Spring bean name without generated class alias`() {
        outbox.schedule(LambdaEvent("lambda"), "lambda-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations).containsExactly("lambda" to "javaLambdaHandler")
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactly("javaLambdaHandler")
        }

        val generatedLambdaId =
            "${javaLambdaHandler::class.java.name}#handle(" +
                "java.lang.Object,${OutboxRecordMetadata::class.java.name})"
        assertThat(handlerRegistry.getHandlerById(generatedLambdaId)).isNull()
        assertThat(handlerRegistry.findAllHandlerDescriptors())
            .extracting<String> { it.id }
            .containsExactly("javaLambdaHandler")
    }

    data class LambdaEvent(
        val value: String,
    )

    @Configuration
    class LambdaConfiguration {
        @Bean("javaLambdaHandler")
        fun javaLambdaHandler(): OutboxHandler =
            JavaLambdaOutboxHandlerFactory.create { payload, metadata ->
                invocations.add((payload as LambdaEvent).value to metadata.handlerId)
            }
    }

    @SpringBootApplication
    class TestApplication

    companion object {
        private val invocations = CopyOnWriteArrayList<Pair<String, String>>()
    }
}
