package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class StableHandlerIdentityTest {
    private fun processor(
        registry: OutboxHandlerRegistry = OutboxHandlerRegistry(),
        fallbacks: OutboxFallbackHandlerRegistry = OutboxFallbackHandlerRegistry(),
        retryPolicies: OutboxRetryPolicyRegistry = mockk(relaxed = true),
    ) = OutboxHandlerBeanPostProcessor(
        registry,
        fallbacks,
        retryPolicies,
    )

    @Test
    fun `annotation id is canonical and configured plus generated ids are aliases`() {
        val registry = OutboxHandlerRegistry()

        processor(registry).postProcessAfterInitialization(StableAnnotatedHandler(), "stableAnnotatedHandler")

        val scheduled = registry.getHandlersForPayloadType(String::class).single()
        assertThat(scheduled.id).isEqualTo("orders-v2")
        assertThat(registry.getHandlerById("orders-v1")).isSameAs(scheduled)
        assertThat(registry.getHandlerById(scheduled.legacyGeneratedId)).isSameAs(scheduled)
        assertThat(registry.findAllHandlerDescriptors()).extracting<String> { it.id }.containsExactly("orders-v2")
    }

    @Test
    fun `aliases work without explicit annotation id while generated id remains canonical`() {
        val registry = OutboxHandlerRegistry()

        processor(registry).postProcessAfterInitialization(AliasOnlyAnnotatedHandler(), "aliasOnly")

        val scheduled = registry.getHandlersForPayloadType(String::class).single()
        assertThat(scheduled.id).isEqualTo(scheduled.legacyGeneratedId)
        assertThat(registry.getHandlerById("future-orders-id")).isSameAs(scheduled)
    }

    @Test
    fun `interface id and aliases are used for regular implementations`() {
        val registry = OutboxHandlerRegistry()

        processor(registry).postProcessAfterInitialization(StableInterfaceHandler(), "stableInterfaceHandler")

        val scheduled = registry.getHandlersForPayloadType(String::class).single()
        assertThat(scheduled.id).isEqualTo("interface-v2")
        assertThat(registry.getHandlerById("interface-v1")).isSameAs(scheduled)
        assertThat(registry.getHandlerById(scheduled.legacyGeneratedId)).isSameAs(scheduled)
    }

    @Test
    fun `canonical id is removed from aliases`() {
        val registry = OutboxHandlerRegistry()

        processor(registry).postProcessAfterInitialization(CanonicalRepeatedAsAlias(), "repeated")

        assertThat(registry.findAllHandlerDescriptors()).hasSize(1)
        assertThat(registry.getHandlersForPayloadType(String::class).single().aliases).doesNotContain("same-id")
    }

    @Test
    fun `blank configured ids and aliases fail clearly`() {
        assertThatThrownBy {
            processor().postProcessAfterInitialization(BlankIdHandler(), "blankId")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Blank handler ID")

        assertThatThrownBy {
            processor().postProcessAfterInitialization(BlankAliasHandler(), "blankAlias")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Blank handler alias")
    }

    @Test
    fun `rejected registration batch leaves every scheduling index unchanged`() {
        val registry = OutboxHandlerRegistry()
        val bean = CollidingBatchHandler()

        assertThatThrownBy {
            processor(registry).postProcessAfterInitialization(bean, "collidingBatchHandler")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("shared")
            .hasMessageContaining("canonical")
            .hasMessageContaining("alias")
            .hasMessageContaining("collidingBatchHandler")

        assertThat(registry.getHandlersForPayloadType(String::class)).isEmpty()
        assertThat(registry.getHandlersForPayloadType(Int::class)).isEmpty()
        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    @Test
    fun `canonical to canonical collision fails with both declarations`() {
        val registry = OutboxHandlerRegistry()

        assertThatThrownBy {
            processor(registry).postProcessAfterInitialization(CanonicalCollisionHandler(), "canonicalCollision")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("canonical")
            .hasMessageContaining("canonicalCollision")
            .hasMessageContaining("first")
            .hasMessageContaining("second")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    @Test
    fun `alias to alias collision fails with both declarations`() {
        val registry = OutboxHandlerRegistry()

        assertThatThrownBy {
            processor(registry).postProcessAfterInitialization(AliasCollisionHandler(), "aliasCollision")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("alias")
            .hasMessageContaining("aliasCollision")
            .hasMessageContaining("first")
            .hasMessageContaining("second")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    @Test
    fun `rolling migration accepts both ids while changing only the canonical id`() {
        val firstRegistry = OutboxHandlerRegistry()
        processor(firstRegistry).postProcessAfterInitialization(MigrationPhaseOneHandler(), "migrationOne")

        val firstHandler = firstRegistry.getHandlersForPayloadType(String::class).single()
        assertThat(firstHandler.id).isEqualTo("orders-v1")
        assertThat(firstRegistry.getHandlerById("orders-v2")).isSameAs(firstHandler)

        val secondRegistry = OutboxHandlerRegistry()
        processor(secondRegistry).postProcessAfterInitialization(MigrationPhaseTwoHandler(), "migrationTwo")

        val secondHandler = secondRegistry.getHandlersForPayloadType(String::class).single()
        assertThat(secondHandler.id).isEqualTo("orders-v2")
        assertThat(secondRegistry.getHandlerById("orders-v1")).isSameAs(secondHandler)
    }

    @Test
    fun `alias routes the same fallback and explicit retry policy`() {
        val registry = OutboxHandlerRegistry()
        val fallbacks = OutboxFallbackHandlerRegistry()
        val retryPolicies = mockk<OutboxRetryPolicyRegistry>(relaxed = true)
        val policy = mockk<io.namastack.outbox.retry.OutboxRetryPolicy>()
        every { retryPolicies.getRetryPolicy("stable-policy") } returns policy

        processor(registry, fallbacks, retryPolicies)
            .postProcessAfterInitialization(StableHandlerWithFallbackAndRetry(), "stableWithFallback")

        assertThat(registry.getHandlerById("stable-route")).isSameAs(registry.getHandlerById("old-route"))
        assertThat(registry.getRegistrationById("stable-route"))
            .isSameAs(registry.getRegistrationById("old-route"))
        assertThat(fallbacks.getByHandlerId("stable-route")).isSameAs(fallbacks.getByHandlerId("old-route"))
        verify { retryPolicies.register("stable-route", policy) }
        verify { retryPolicies.registerAlias("old-route", policy) }
    }

    @Test
    fun `alias preserves lazy default retry lookup`() {
        val registry = OutboxHandlerRegistry()
        val retryPolicies = mockk<OutboxRetryPolicyRegistry>(relaxed = true)
        val policy = mockk<io.namastack.outbox.retry.OutboxRetryPolicy>()
        every { retryPolicies.getByHandlerId("old-default-route") } returns policy
        every { policy.maxRetries() } returns 3
        every { policy.shouldRetry(any()) } returns true
        val bean = DefaultPolicyHandler()
        processor(registry, retryPolicies = retryPolicies)
            .postProcessAfterInitialization(bean, "defaultPolicy")
        val now = Instant.now()
        val record =
            OutboxRecord.restore(
                id = "record-id",
                recordKey = "record-key",
                payload = "payload",
                context = emptyMap(),
                createdAt = now,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 1,
                failureException = IllegalStateException("failure"),
                failureReason = null,
                partition = 1,
                nextRetryAt = now,
                handlerId = "old-default-route",
            )

        OutboxFallbackHandlerInvoker(retryPolicies, registry).dispatch(record)

        verify { retryPolicies.getByHandlerId("old-default-route") }
        assertThat(bean.failureContext?.handlerId).isEqualTo("old-default-route")
    }

    @Test
    fun `mixed discovery of the same method fails atomically`() {
        val registry = OutboxHandlerRegistry()

        assertThatThrownBy {
            processor(registry).postProcessAfterInitialization(MixedSameMethodHandler(), "mixedSame")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("both annotation and interface")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    @Test
    fun `different annotation and interface methods keep independent identity configuration`() {
        val registry = OutboxHandlerRegistry()

        processor(registry).postProcessAfterInitialization(MixedDifferentMethodsHandler(), "mixedDifferent")

        assertThat(registry.findAllHandlerDescriptors())
            .extracting<String> { it.id }
            .containsExactly("annotation-id", "interface-id")
    }

    private class StableAnnotatedHandler {
        @OutboxHandler(id = "orders-v2", aliases = ["orders-v1"])
        fun handle(payload: String) = Unit
    }

    private class AliasOnlyAnnotatedHandler {
        @OutboxHandler(aliases = ["future-orders-id"])
        fun handle(payload: String) = Unit
    }

    private class StableInterfaceHandler : OutboxTypedHandler<String> {
        override fun getHandlerId() = "interface-v2"

        override fun getHandlerAliases() = setOf("interface-v1")

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class CanonicalRepeatedAsAlias : OutboxTypedHandler<String> {
        override fun getHandlerId() = "same-id"

        override fun getHandlerAliases() = setOf("same-id")

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class BlankIdHandler : OutboxTypedHandler<String> {
        override fun getHandlerId() = " "

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class BlankAliasHandler : OutboxTypedHandler<String> {
        override fun getHandlerAliases() = setOf(" ")

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class CollidingBatchHandler {
        @OutboxHandler(id = "shared")
        fun first(payload: String) = Unit

        @OutboxHandler(id = "other", aliases = ["shared"])
        fun second(payload: Int) = Unit
    }

    private class CanonicalCollisionHandler {
        @OutboxHandler(id = "shared-canonical")
        fun first(payload: String) = Unit

        @OutboxHandler(id = "shared-canonical")
        fun second(payload: Int) = Unit
    }

    private class AliasCollisionHandler {
        @OutboxHandler(id = "first-id", aliases = ["shared-alias"])
        fun first(payload: String) = Unit

        @OutboxHandler(id = "second-id", aliases = ["shared-alias"])
        fun second(payload: Int) = Unit
    }

    private class MigrationPhaseOneHandler {
        @OutboxHandler(id = "orders-v1", aliases = ["orders-v2"])
        fun handle(payload: String) = Unit
    }

    private class MigrationPhaseTwoHandler {
        @OutboxHandler(id = "orders-v2", aliases = ["orders-v1"])
        fun handle(payload: String) = Unit
    }

    private class StableHandlerWithFallbackAndRetry {
        @OutboxHandler(id = "stable-route", aliases = ["old-route"])
        @OutboxRetryable(name = "stable-policy")
        fun handle(payload: String) = Unit

        @OutboxFallbackHandler
        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ) = Unit
    }

    private class DefaultPolicyHandler {
        var failureContext: OutboxFailureContext? = null

        @OutboxHandler(id = "default-route", aliases = ["old-default-route"])
        fun handle(payload: String) = Unit

        @OutboxFallbackHandler
        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ) {
            failureContext = context
        }
    }

    private class MixedSameMethodHandler : OutboxTypedHandler<String> {
        @OutboxHandler
        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class MixedDifferentMethodsHandler : OutboxTypedHandler<String> {
        override fun getHandlerId() = "interface-id"

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit

        @OutboxHandler(id = "annotation-id")
        fun handleNumber(payload: Int) = Unit
    }
}
