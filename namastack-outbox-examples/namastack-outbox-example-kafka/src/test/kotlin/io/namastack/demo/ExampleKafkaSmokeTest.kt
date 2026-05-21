package io.namastack.demo

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Properties


@SpringBootTest
@Import(KafkaTestConfiguration::class)
@Testcontainers
class ExampleKafkaSmokeTest {
    companion object {
        @JvmStatic
        @Container
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.8"))

        @JvmStatic
        @DynamicPropertySource
        fun registerKafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    private lateinit var consumer: KafkaConsumer<String, String>;

    @BeforeEach
    fun setUp() {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-group")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer"
            )
            put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer"
            )
        }

        consumer = KafkaConsumer<String, String>(props)
    }

    @AfterEach
    fun tearDown() {
        consumer.close()
    }

    @Test
    fun `publishes to kafka`() {
        val consumerRecords = pollKafka("customer-registrations")
        assertThat(consumerRecords.count()).isGreaterThan(0)

        val defaultRecords = pollKafka("default-topic")
        assertThat(defaultRecords.count()).isGreaterThan(0)
    }

    private fun pollKafka(topic: String): ConsumerRecords<String, String> {
        consumer.subscribe(listOf(topic))
        return consumer.poll(Duration.ofSeconds(10))
    }
}
