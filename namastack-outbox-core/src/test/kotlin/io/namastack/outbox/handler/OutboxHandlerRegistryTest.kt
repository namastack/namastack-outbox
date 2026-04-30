package io.namastack.outbox.handler

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

@DisplayName("OutboxHandlerRegistry")
class OutboxHandlerRegistryTest {
    private fun metadata(
        key: String = "key",
        handlerId: String = "",
        context: Map<String, String> = emptyMap(),
    ) =
        OutboxRecordMetadata(
            key = key,
            handlerId = handlerId,
            createdAt = clock.instant(),
            context = context,
        )
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var registry: OutboxHandlerRegistry

    @BeforeEach
    fun setUp() {
        registry = OutboxHandlerRegistry()
    }

    @Nested
    @DisplayName("registerTypedHandler()")
    inner class RegisterTypedHandlerTests {
        @Test
        fun `should register typed handler for payload type`() {
            val payloadType = TestPayload::class
            val handler = createMockTypedHandler("handler1", payloadType)

            registry.register(handler)

            val result = registry.getHandlersForPayloadType(payloadType)
            assertThat(result).contains(handler)
        }

        @Test
        fun `should allow multiple handlers for same type`() {
            val payloadType = TestPayload::class
            val handler1 = createMockTypedHandler("handler1", payloadType)
            val handler2 = createMockTypedHandler("handler2", payloadType)

            registry.register(handler1)
            registry.register(handler2)

            val result = registry.getHandlersForPayloadType(payloadType)
            assertThat(result).hasSize(2).contains(handler1, handler2)
        }

        @Test
        fun `should register handler in ID map`() {
            val handler = createMockTypedHandler("unique-id", TestPayload::class)
            registry.register(handler)

            assertThat(registry.getHandlerById("unique-id")).isEqualTo(handler)
        }

        @Test
        fun `should return empty list for unregistered payload type`() {
            val result = registry.getHandlersForPayloadType(UnregisteredPayload::class)
            assertThat(result).isEmpty()
        }

        @Test
        fun `should throw error when registering duplicate handler IDs`() {
            val handler1 = createMockTypedHandler("duplicate-id", TestPayload::class)
            val handler2 = createMockTypedHandler("duplicate-id", AnotherPayload::class)

            registry.register(handler1)

            val exception =
                try {
                    registry.register(handler2)
                    null
                } catch (e: IllegalStateException) {
                    e
                }

            assertThat(exception).isNotNull()
            assertThat(exception?.message).contains("duplicate")
        }
    }

    @Nested
    @DisplayName("registerGenericHandler()")
    inner class RegisterGenericHandlerTests {
        @Test
        fun `should register generic handler`() {
            val handler = createMockGenericHandler("generic-handler")

            registry.register(handler)

            val result = registry.getGenericHandlers(1, metadata())
            assertThat(result).contains(handler)
        }

        @Test
        fun `should allow multiple generic handlers`() {
            val handler1 = createMockGenericHandler("generic-1")
            val handler2 = createMockGenericHandler("generic-2")

            registry.register(handler1)
            registry.register(handler2)

            val result = registry.getGenericHandlers(1, metadata())
            assertThat(result).hasSize(2).contains(handler1, handler2)
        }

        @Test
        fun `should not return generic handlers when supports returns false`() {
            val handler = createMockGenericHandler("generic-handler", false)

            registry.register(handler)

            val result = registry.getGenericHandlers(1, metadata())
            assertThat(result).isEmpty()
        }

        @Test
        fun `should register handler in ID map`() {
            val handler = createMockGenericHandler("generic-id")
            registry.register(handler)

            assertThat(registry.getHandlerById("generic-id")).isEqualTo(handler)
        }

        @Test
        fun `should throw error when registering duplicate handler ID with generic handler`() {
            val handler1 = createMockTypedHandler("duplicate-id", TestPayload::class)
            val handler2 = createMockGenericHandler("duplicate-id")

            registry.register(handler1)

            val exception =
                try {
                    registry.register(handler2)
                    null
                } catch (e: IllegalStateException) {
                    e
                }

            assertThat(exception).isNotNull()
        }
    }

    @Nested
    @DisplayName("getHandlerById()")
    inner class GetHandlerByIdTests {
        @Test
        fun `should retrieve typed handler by ID`() {
            val handler = createMockTypedHandler("handler-id", TestPayload::class)
            registry.register(handler)

            val result = registry.getHandlerById("handler-id")

            assertThat(result).isEqualTo(handler)
        }

        @Test
        fun `should retrieve generic handler by ID`() {
            val handler = createMockGenericHandler("generic-id")
            registry.register(handler)

            val result = registry.getHandlerById("generic-id")

            assertThat(result).isEqualTo(handler)
        }

        @Test
        fun `should return null for unknown ID`() {
            val result = registry.getHandlerById("unknown-id")
            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("registerAlias()")
    inner class RegisterAliasTests {
        @Test
        fun `should allow lookup by alias ID`() {
            val handler = createMockTypedHandler("stable-id", TestPayload::class)
            registry.register(handler)
            registry.registerAlias("legacy-id", handler)

            assertThat(registry.getHandlerById("legacy-id")).isEqualTo(handler)
        }

        @Test
        fun `should still allow lookup by stable ID after alias registration`() {
            val handler = createMockTypedHandler("stable-id", TestPayload::class)
            registry.register(handler)
            registry.registerAlias("legacy-id", handler)

            assertThat(registry.getHandlerById("stable-id")).isEqualTo(handler)
        }

        @Test
        fun `should not add alias to typed handlers list`() {
            val handler = createMockTypedHandler("stable-id", TestPayload::class)
            registry.register(handler)
            registry.registerAlias("legacy-id", handler)

            val typedHandlers = registry.getHandlersForPayloadType(TestPayload::class)
            assertThat(typedHandlers).hasSize(1)
        }

        @Test
        fun `should throw on duplicate alias ID`() {
            val handler1 = createMockTypedHandler("existing-id", TestPayload::class)
            val handler2 = createMockTypedHandler("other-id", AnotherPayload::class)
            registry.register(handler1)
            registry.register(handler2)

            assertThatThrownBy { registry.registerAlias("existing-id", handler2) }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Nested
    @DisplayName("Handler Discovery Integration")
    inner class IntegrationTests {
        @Test
        fun `should support typed and generic handlers simultaneously`() {
            val typedHandler = createMockTypedHandler("typed", TestPayload::class)
            val genericHandler = createMockGenericHandler("generic")

            registry.register(typedHandler)
            registry.register(genericHandler)

            val typedResult = registry.getHandlersForPayloadType(TestPayload::class)
            val genericResult = registry.getGenericHandlers(1, metadata())

            assertThat(typedResult).contains(typedHandler)
            assertThat(genericResult).contains(genericHandler)
        }

        @Test
        fun `should maintain separate indexes for different types`() {
            val handler1 = createMockTypedHandler("handler1", TestPayload::class)
            val handler2 = createMockTypedHandler("handler2", AnotherPayload::class)

            registry.register(handler1)
            registry.register(handler2)

            assertThat(registry.getHandlersForPayloadType(TestPayload::class)).contains(handler1)
            assertThat(registry.getHandlersForPayloadType(AnotherPayload::class)).contains(handler2)
            assertThat(registry.getHandlersForPayloadType(TestPayload::class)).doesNotContain(handler2)
        }

        @Test
        fun `should return copy of generic handlers list`() {
            val handler = createMockGenericHandler("generic")
            registry.register(handler)

            val result1 = registry.getGenericHandlers(1, metadata())
            val result2 = registry.getGenericHandlers(1, metadata())

            assertThat(result1).isEqualTo(result2)
            assertThat(result1).isNotSameAs(result2)
        }
    }

    private fun createMockTypedHandler(
        id: String,
        paramType: KClass<*>,
    ): TypedHandlerMethod {
        val handler = mockk<TypedHandlerMethod>()
        every { handler.id } returns id
        every { handler.paramType } returns paramType
        return handler
    }

    private fun createMockGenericHandler(
        id: String,
        supports: Boolean = true,
    ): GenericHandlerMethod {
        val handler = mockk<GenericHandlerMethod>()
        every { handler.id } returns id
        every { handler.supportsScheduling(any(), any()) } returns supports
        return handler
    }

    class TestPayload

    class AnotherPayload

    class UnregisteredPayload
}
