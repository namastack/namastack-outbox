package io.namastack.example.modulith

import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.config.TopicBuilder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    properties = [
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
@Import(ModulithExampleSmokeTest.KafkaTestConfiguration::class)
@Testcontainers
class ModulithExampleSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))

        @JvmStatic
        @DynamicPropertySource
        fun registerKafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @Test
    fun `captures payment through modulith outbox`(output: CapturedOutput) {
        await()
            .atMost(20, SECONDS)
            .untilAsserted {
                assertLogMessage(
                    output,
                    "OrderService",
                    "[Order] Published OrderPlacedEvent",
                )
                assertLogMessage(
                    output,
                    "PaymentService",
                    "[Payment] Published externalized PaymentRequestedEvent",
                )
                assertLogMessage(
                    output,
                    "PaymentProviderKafkaListener",
                    "[External Payment Provider] Received payment",
                )
                assertLogMessage(
                    output,
                    "PaymentService",
                    "[Payment] Captured payment",
                )
            }
    }

    fun assertLogMessage(
        output: CapturedOutput,
        vararg messages: String,
    ) {
        val found =
            output.out.lines().any { line ->
                messages.all { line.contains(it) }
            }

        assertThat(found)
            .withFailMessage("Could not find log message")
            .isTrue()
    }

    @TestConfiguration
    class KafkaTestConfiguration {
        @Bean
        fun paymentRequestsTopic(): NewTopic =
            TopicBuilder
                .name("payment-requests")
                .partitions(1)
                .replicas(1)
                .build()
    }
}
