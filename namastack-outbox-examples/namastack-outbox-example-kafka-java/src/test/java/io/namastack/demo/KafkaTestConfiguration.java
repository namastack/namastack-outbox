package io.namastack.demo;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@TestConfiguration
class KafkaTestConfiguration {
    @Bean
    NewTopic customerRegistrationsTopic() {
        return TopicBuilder.name("customer-registrations").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic defaultTopic() {
        return TopicBuilder.name("default-topic").partitions(1).replicas(1).build();
    }
}
