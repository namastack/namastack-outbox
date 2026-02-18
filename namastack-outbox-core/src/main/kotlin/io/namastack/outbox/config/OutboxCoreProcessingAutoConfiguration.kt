package io.namastack.outbox.config

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.processor.FallbackOutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.processor.PermanentFailureOutboxRecordProcessor
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.processor.RetryOutboxRecordProcessor
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.time.Clock

@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreProcessingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordProcessorChain(
        handlerInvoker: io.namastack.outbox.handler.invoker.OutboxHandlerInvoker,
        fallbackHandlerInvoker: io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker,
        recordRepository: OutboxRecordRepository,
        retryPolicyRegistry: OutboxRetryPolicyRegistry,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxRecordProcessor {
        val primary = PrimaryOutboxRecordProcessor(handlerInvoker, recordRepository, properties, clock)
        val retry = RetryOutboxRecordProcessor(retryPolicyRegistry, recordRepository, clock)
        val fallback =
            FallbackOutboxRecordProcessor(
                recordRepository = recordRepository,
                fallbackHandlerInvoker = fallbackHandlerInvoker,
                retryPolicyRegistry = retryPolicyRegistry,
                properties = properties,
                clock = clock,
            )
        val permanentFailure = PermanentFailureOutboxRecordProcessor(recordRepository)

        primary
            .setNext(retry)
            .setNext(fallback)
            .setNext(permanentFailure)

        return primary
    }
}
