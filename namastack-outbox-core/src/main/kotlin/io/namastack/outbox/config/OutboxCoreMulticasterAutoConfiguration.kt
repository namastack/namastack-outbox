package io.namastack.outbox.config

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxEventMulticaster
import io.namastack.outbox.OutboxProperties
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.event.SimpleApplicationEventMulticaster

@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreMulticasterAutoConfiguration {
    @Bean(name = ["applicationEventMulticaster"])
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["namastack.outbox.multicaster.enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxApplicationEventMulticaster(
        outbox: Outbox,
        beanFactory: BeanFactory,
        outboxProperties: OutboxProperties,
    ): OutboxEventMulticaster =
        OutboxEventMulticaster(
            outbox = outbox,
            outboxProperties = outboxProperties,
            delegateEventMulticaster = SimpleApplicationEventMulticaster(beanFactory),
        )
}
