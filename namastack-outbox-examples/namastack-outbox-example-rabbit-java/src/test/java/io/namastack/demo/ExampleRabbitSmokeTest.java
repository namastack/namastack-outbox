package io.namastack.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class ExampleRabbitSmokeTest {
    @Container
    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.0-management"));

    @DynamicPropertySource
    static void registerRabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @TestConfiguration
    static class Config {
        @Bean
        Queue registrationQueue() {
            return new Queue("customer-registration-queue");
        }

        @Bean
        Queue defaultQueue() {
            return new Queue("default-queue");
        }

        @Bean
        Binding customerRegistrationsBinding(@Qualifier("registrationQueue") Queue queue) {
            return BindingBuilder
                    .bind(queue)
                    .to(new TopicExchange("customer-registrations"))
                    .with("#");
        }

        @Bean
        Binding defaultBinding(@Qualifier("defaultQueue") Queue queue) {
            return BindingBuilder
                    .bind(queue)
                    .to(new TopicExchange("default-exchange"))
                    .with("#");
        }
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Test
    void publishesToRabbitTest() {
        var customerMessage = rabbitTemplate.receive("customer-registration-queue", 5000);
        assertThat(customerMessage).isNotNull();

        var defaultMessage = rabbitTemplate.receive("default-queue", 5000);
        assertThat(defaultMessage).isNotNull();
    }
}
