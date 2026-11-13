package io.namastack.outbox.config

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.completion.OutboxCompletionDirectPersistence
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.completion.OutboxRecordCompleter
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.processor.FallbackOutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.processor.PermanentFailureOutboxRecordProcessor
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.processor.RetryOutboxRecordProcessor
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import io.namastack.outbox.runtime.toRuntimeSettings
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.time.Clock

@AutoConfiguration
@ConditionalOnSingleRuntimeMode
@ConditionalOnProperty(name = ["namastack.outbox.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCoreProcessingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxCompletionPersistence(recordRepository: OutboxRecordRepository): OutboxCompletionPersistence =
        OutboxCompletionDirectPersistence(recordRepository)

    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordCompleter(
        completionPersistence: OutboxCompletionPersistence,
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxRecordCompleter =
        OutboxRecordCompleter(completionPersistence, properties.toRuntimeSettings().processing, clock)

    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordProcessorChain(
        handlerInvoker: OutboxHandlerInvoker,
        fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
        fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
        completionPersistence: OutboxCompletionPersistence,
        retryPolicyRegistry: OutboxRetryPolicyRegistry,
        completer: OutboxRecordCompleter,
        clock: Clock,
    ): OutboxRecordProcessor {
        val primary = PrimaryOutboxRecordProcessor(handlerInvoker, completer)
        val retry = RetryOutboxRecordProcessor(retryPolicyRegistry, completionPersistence, clock)
        val fallback =
            FallbackOutboxRecordProcessor(
                fallbackHandlerRegistry = fallbackHandlerRegistry,
                fallbackHandlerInvoker = fallbackHandlerInvoker,
                completer = completer,
            )
        val permanentFailure = PermanentFailureOutboxRecordProcessor(completionPersistence)

        primary
            .setNext(retry)
            .setNext(fallback)
            .setNext(permanentFailure)

        return primary
    }

    @Bean
    @ConditionalOnMissingBean
    fun outboxRecordProcessorChainInvoker(
        recordProcessorChain: OutboxRecordProcessor,
        instrumentations: ObjectProvider<OutboxInstrumentation>,
        channelNameProvider: ObjectProvider<OutboxChannelNameProvider>,
    ): OutboxRecordProcessorChainInvoker =
        OutboxRecordProcessorChainInvoker(
            recordProcessorChain = recordProcessorChain,
            instrumentationSupplier = {
                OutboxInstrumentation.compose(instrumentations.orderedStream().toList())
            },
            channelNameProviderSupplier = channelNameProvider::getObject,
        )
}
