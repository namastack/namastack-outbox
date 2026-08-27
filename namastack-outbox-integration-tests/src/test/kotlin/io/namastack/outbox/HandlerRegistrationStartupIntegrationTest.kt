package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.config.OutboxCoreThreadingAutoConfiguration
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

/** Verifies handler startup validation through the real Spring bean registration lifecycle. */
class HandlerRegistrationStartupIntegrationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TaskExecutionAutoConfiguration::class.java,
                    TaskSchedulingAutoConfiguration::class.java,
                    OutboxCoreInfrastructureAutoConfiguration::class.java,
                    OutboxCoreThreadingAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(MinimalInfrastructure::class.java)
            .withPropertyValues("namastack.outbox.instance.heartbeat-interval=1h")

    @Test
    fun `unsupported zero argument handler is skipped`() {
        contextRunner.withUserConfiguration(ZeroArgumentHandlerConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(OutboxHandlerRegistry::class.java).findAllHandlerDescriptors()).isEmpty()
        }
    }

    @Test
    fun `unsupported second parameter handler is skipped`() {
        contextRunner.withUserConfiguration(WrongSecondParameterConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(OutboxHandlerRegistry::class.java).findAllHandlerDescriptors()).isEmpty()
        }
    }

    @Test
    fun `same method discovered through annotation and interface fails startup`() {
        assertStartupFails(MixedSameMethodConfiguration::class.java, "both annotation and interface")
    }

    @Test
    fun `same method discovered through typed and generic interfaces fails startup`() {
        assertStartupFails(CombinedInterfacesConfiguration::class.java, "implements both OutboxHandler")
    }

    @Test
    fun `blank annotation ID fails startup`() {
        assertStartupFails(BlankAnnotationIdConfiguration::class.java, "Blank handler ID")
    }

    @Test
    fun `blank interface ID fails startup`() {
        assertStartupFails(BlankInterfaceIdConfiguration::class.java, "Blank handler ID")
    }

    @Test
    fun `blank alias fails startup`() {
        assertStartupFails(BlankAliasConfiguration::class.java, "Blank handler alias")
    }

    @Test
    fun `canonical ID collision fails startup with both bean names`() {
        contextRunner.withUserConfiguration(CanonicalCollisionConfiguration::class.java).run { context ->
            val messages = failureMessages(context.startupFailure)
            assertThat(context).hasFailed()
            assertThat(messages)
                .contains("duplicate handler routing ID collision")
                .contains("shared-canonical")
                .contains("firstHandler")
                .contains("secondHandler")
                .contains("canonical")
        }
    }

    @Test
    fun `canonical and alias collision fails startup with routing roles`() {
        contextRunner.withUserConfiguration(CanonicalAliasCollisionConfiguration::class.java).run { context ->
            val messages = failureMessages(context.startupFailure)
            assertThat(context).hasFailed()
            assertThat(messages)
                .contains("shared-route")
                .contains("canonical")
                .contains("alias")
                .contains("canonicalHandler")
                .contains("aliasHandler")
        }
    }

    @Test
    fun `alias collision fails startup with both bean names`() {
        contextRunner.withUserConfiguration(AliasCollisionConfiguration::class.java).run { context ->
            val messages = failureMessages(context.startupFailure)
            assertThat(context).hasFailed()
            assertThat(messages)
                .contains("shared-alias")
                .contains("firstAliasHandler")
                .contains("secondAliasHandler")
                .contains("alias")
        }
    }

    @Test
    fun `unmatched fallback is ignored while primary handler is registered`() {
        contextRunner.withUserConfiguration(UnmatchedFallbackConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(OutboxHandlerRegistry::class.java).findAllHandlerDescriptors())
                .extracting<String> { it.id }
                .containsExactly("primary-with-unmatched-fallback")
        }
    }

    private fun assertStartupFails(
        configuration: Class<*>,
        expectedMessage: String,
    ) {
        contextRunner.withUserConfiguration(configuration).run { context ->
            assertThat(context).hasFailed()
            assertThat(failureMessages(context.startupFailure)).contains(expectedMessage)
        }
    }

    private fun failureMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")

    @Configuration(proxyBeanMethods = false)
    class MinimalInfrastructure {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() =
            mockk<OutboxInstanceRepository>(relaxed = true).apply {
                every { findActiveInstances() } returns listOf(mockk<OutboxInstance>(relaxed = true))
            }
    }

    @Configuration(proxyBeanMethods = false)
    class ZeroArgumentHandlerConfiguration {
        @Bean
        fun zeroArgumentHandler() = ZeroArgumentHandler()
    }

    class ZeroArgumentHandler {
        @OutboxHandlerAnnotation
        fun handle() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class WrongSecondParameterConfiguration {
        @Bean
        fun wrongSecondParameterHandler() = WrongSecondParameterHandler()
    }

    class WrongSecondParameterHandler {
        @OutboxHandlerAnnotation
        fun handle(
            payload: String,
            wrong: String,
        ) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class MixedSameMethodConfiguration {
        @Bean
        fun mixedSameMethodHandler() = MixedSameMethodHandler()
    }

    class MixedSameMethodHandler : OutboxTypedHandler<String> {
        @OutboxHandlerAnnotation
        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class CombinedInterfacesConfiguration {
        @Bean
        fun combinedInterfacesHandler() = CombinedInterfacesHandler()
    }

    class CombinedInterfacesHandler :
        OutboxTypedHandler<Any>,
        OutboxHandler {
        override fun getHandlerId(): String? = null

        override fun getHandlerAliases(): Set<String> = emptySet()

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class BlankAnnotationIdConfiguration {
        @Bean
        fun blankAnnotationIdHandler() = BlankAnnotationIdHandler()
    }

    class BlankAnnotationIdHandler {
        @OutboxHandlerAnnotation(id = " ")
        fun handle(payload: String) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class BlankInterfaceIdConfiguration {
        @Bean
        fun blankInterfaceIdHandler() = BlankInterfaceIdHandler()
    }

    class BlankInterfaceIdHandler : OutboxTypedHandler<String> {
        override fun getHandlerId() = " "

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class BlankAliasConfiguration {
        @Bean
        fun blankAliasHandler() = BlankAliasHandler()
    }

    class BlankAliasHandler {
        @OutboxHandlerAnnotation(aliases = [" "])
        fun handle(payload: String) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class CanonicalCollisionConfiguration {
        @Bean
        fun firstHandler() = FirstCanonicalHandler()

        @Bean
        fun secondHandler() = SecondCanonicalHandler()
    }

    class FirstCanonicalHandler {
        @OutboxHandlerAnnotation(id = "shared-canonical")
        fun handle(payload: String) = Unit
    }

    class SecondCanonicalHandler {
        @OutboxHandlerAnnotation(id = "shared-canonical")
        fun handle(payload: Int) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class CanonicalAliasCollisionConfiguration {
        @Bean
        fun canonicalHandler() = CanonicalHandler()

        @Bean
        fun aliasHandler() = AliasHandler()
    }

    class CanonicalHandler {
        @OutboxHandlerAnnotation(id = "shared-route")
        fun handle(payload: String) = Unit
    }

    class AliasHandler {
        @OutboxHandlerAnnotation(id = "different-route", aliases = ["shared-route"])
        fun handle(payload: Int) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class AliasCollisionConfiguration {
        @Bean
        fun firstAliasHandler() = FirstAliasHandler()

        @Bean
        fun secondAliasHandler() = SecondAliasHandler()
    }

    class FirstAliasHandler {
        @OutboxHandlerAnnotation(id = "first-route", aliases = ["shared-alias"])
        fun handle(payload: String) = Unit
    }

    class SecondAliasHandler {
        @OutboxHandlerAnnotation(id = "second-route", aliases = ["shared-alias"])
        fun handle(payload: Int) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class UnmatchedFallbackConfiguration {
        @Bean
        fun unmatchedFallbackHandler() = UnmatchedFallbackHandler()
    }

    class UnmatchedFallbackHandler {
        @OutboxHandlerAnnotation(id = "primary-with-unmatched-fallback")
        fun handle(payload: String) = Unit

        @OutboxFallbackHandler
        fun handleFailure(
            payload: Int,
            context: io.namastack.outbox.handler.OutboxFailureContext,
        ) = Unit
    }
}
