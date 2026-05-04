package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName


@SpringBootTest
@Testcontainers
class ExampleRabbitSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val rabbit = RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.0-management"))

        @JvmStatic
        @DynamicPropertySource
        fun registerRabbitProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.rabbitmq.host") { rabbit.host }
            registry.add("spring.rabbitmq.port") { rabbit.amqpPort }
            registry.add("spring.rabbitmq.username") { rabbit.adminUsername }
            registry.add("spring.rabbitmq.password") { rabbit.adminPassword }
        }
    }

    @TestConfiguration
    class Config {
        @Bean
        fun registrationQueue(): Queue {
            return Queue("customer-registration-queue")
        }

        @Bean
        fun defaultQueue(): Queue {
            return Queue("default-queue")
        }

        @Bean
        fun customerRegistrationsBinding(@Qualifier("registrationQueue") queue: Queue): Binding =
            BindingBuilder
                .bind(queue)
                .to(TopicExchange("customer-registrations"))
                .with("#")

        @Bean
        fun defaultBinding(@Qualifier("defaultQueue") queue: Queue): Binding =
            BindingBuilder
                .bind(queue)
                .to(TopicExchange("default-exchange"))
                .with("#")
    }

    @Autowired
    private lateinit var rabbitTemplate: RabbitTemplate

    @Test
    fun `publishes to rabbit`() {
        val customerMessage = rabbitTemplate.receiveAndConvert("customer-registration-queue", 5000)
        assertThat(customerMessage).isNotNull()

        val defaultMessage = rabbitTemplate.receiveAndConvert("default-queue", 5000)
        assertThat(defaultMessage).isNotNull()
    }
}
