package io.namastack.outbox.context

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxContextCollector")
class OutboxContextCollectorTest {
    private lateinit var contextCollector: OutboxContextCollector

    @Nested
    @DisplayName("collectContext()")
    inner class CollectContextTests {
        @Test
        fun `should return empty map when no providers are registered`() {
            contextCollector = OutboxContextCollector(emptyList())

            val context = contextCollector.collectContext()

            assertThat(context).isEmpty()
        }

        @Test
        fun `should collect context from single provider`() {
            val provider = mockk<OutboxContextProvider>()
            every { provider.provide() } returns mapOf("traceId" to "abc123", "spanId" to "xyz789")

            contextCollector = OutboxContextCollector(listOf(provider))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(2)
                .contains(
                    entry("traceId", "abc123"),
                    entry("spanId", "xyz789"),
                )
            verify(exactly = 1) { provider.provide() }
        }

        @Test
        fun `should collect and merge context from multiple providers`() {
            val tracingProvider = mockk<OutboxContextProvider>()
            val tenantProvider = mockk<OutboxContextProvider>()
            val userProvider = mockk<OutboxContextProvider>()

            every { tracingProvider.provide() } returns mapOf("traceId" to "abc123", "spanId" to "xyz789")
            every { tenantProvider.provide() } returns mapOf("tenantId" to "tenant-1")
            every { userProvider.provide() } returns mapOf("userId" to "user-42", "username" to "john.doe")

            contextCollector = OutboxContextCollector(listOf(tracingProvider, tenantProvider, userProvider))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(5)
                .contains(
                    entry("traceId", "abc123"),
                    entry("spanId", "xyz789"),
                    entry("tenantId", "tenant-1"),
                    entry("userId", "user-42"),
                    entry("username", "john.doe"),
                )
            verify(exactly = 1) { tracingProvider.provide() }
            verify(exactly = 1) { tenantProvider.provide() }
            verify(exactly = 1) { userProvider.provide() }
        }

        @Test
        fun `should use last provider value when multiple providers return same key`() {
            val provider1 = mockk<OutboxContextProvider>()
            val provider2 = mockk<OutboxContextProvider>()
            val provider3 = mockk<OutboxContextProvider>()

            every { provider1.provide() } returns mapOf("key" to "value1", "unique1" to "data1")
            every { provider2.provide() } returns mapOf("key" to "value2", "unique2" to "data2")
            every { provider3.provide() } returns mapOf("key" to "value3", "unique3" to "data3")

            contextCollector = OutboxContextCollector(listOf(provider1, provider2, provider3))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(4)
                .contains(
                    entry("key", "value3"),
                    entry("unique1", "data1"),
                    entry("unique2", "data2"),
                    entry("unique3", "data3"),
                )
        }

        @Test
        fun `should return empty map when provider returns empty map`() {
            val provider = mockk<OutboxContextProvider>()
            every { provider.provide() } returns emptyMap()

            contextCollector = OutboxContextCollector(listOf(provider))

            val context = contextCollector.collectContext()

            assertThat(context).isEmpty()
            verify(exactly = 1) { provider.provide() }
        }

        @Test
        fun `should return empty map when all providers return empty maps`() {
            val provider1 = mockk<OutboxContextProvider>()
            val provider2 = mockk<OutboxContextProvider>()

            every { provider1.provide() } returns emptyMap()
            every { provider2.provide() } returns emptyMap()

            contextCollector = OutboxContextCollector(listOf(provider1, provider2))

            val context = contextCollector.collectContext()

            assertThat(context).isEmpty()
        }

        @Test
        fun `should skip failed provider and continue collecting from remaining providers`() {
            val failingProvider = mockk<OutboxContextProvider>()
            val successfulProvider = mockk<OutboxContextProvider>()

            every { failingProvider.provide() } throws RuntimeException("Provider failed")
            every { successfulProvider.provide() } returns mapOf("key" to "value")

            contextCollector = OutboxContextCollector(listOf(failingProvider, successfulProvider))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(1)
                .contains(entry("key", "value"))
            verify(exactly = 1) { failingProvider.provide() }
            verify(exactly = 1) { successfulProvider.provide() }
        }

        @Test
        fun `should handle multiple failing providers gracefully`() {
            val failingProvider1 = mockk<OutboxContextProvider>()
            val failingProvider2 = mockk<OutboxContextProvider>()
            val successfulProvider = mockk<OutboxContextProvider>()

            every { failingProvider1.provide() } throws RuntimeException("Provider 1 failed")
            every { failingProvider2.provide() } throws IllegalStateException("Provider 2 failed")
            every { successfulProvider.provide() } returns mapOf("key" to "value")

            contextCollector = OutboxContextCollector(listOf(failingProvider1, failingProvider2, successfulProvider))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(1)
                .contains(entry("key", "value"))
        }

        @Test
        fun `should return empty map when all providers fail`() {
            val failingProvider1 = mockk<OutboxContextProvider>()
            val failingProvider2 = mockk<OutboxContextProvider>()

            every { failingProvider1.provide() } throws RuntimeException("Provider 1 failed")
            every { failingProvider2.provide() } throws RuntimeException("Provider 2 failed")

            contextCollector = OutboxContextCollector(listOf(failingProvider1, failingProvider2))

            val context = contextCollector.collectContext()

            assertThat(context).isEmpty()
        }

        @Test
        fun `should handle null pointer exception from provider`() {
            val failingProvider = mockk<OutboxContextProvider>()
            val successfulProvider = mockk<OutboxContextProvider>()

            every { failingProvider.provide() } throws NullPointerException("Null value")
            every { successfulProvider.provide() } returns mapOf("key" to "value")

            contextCollector = OutboxContextCollector(listOf(failingProvider, successfulProvider))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(1)
                .contains(entry("key", "value"))
        }

        @Test
        fun `should merge contexts with partial failures`() {
            val provider1 = mockk<OutboxContextProvider>()
            val failingProvider = mockk<OutboxContextProvider>()
            val provider2 = mockk<OutboxContextProvider>()

            every { provider1.provide() } returns mapOf("key1" to "value1")
            every { failingProvider.provide() } throws RuntimeException("Failed")
            every { provider2.provide() } returns mapOf("key2" to "value2")

            contextCollector = OutboxContextCollector(listOf(provider1, failingProvider, provider2))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(2)
                .contains(
                    entry("key1", "value1"),
                    entry("key2", "value2"),
                )
        }

        @Test
        fun `should handle empty string values from provider`() {
            val provider = mockk<OutboxContextProvider>()
            every { provider.provide() } returns
                mapOf(
                    "key1" to "",
                    "key2" to "value",
                    "key3" to "",
                )

            contextCollector = OutboxContextCollector(listOf(provider))

            val context = contextCollector.collectContext()

            assertThat(context)
                .hasSize(3)
                .contains(
                    entry("key1", ""),
                    entry("key2", "value"),
                    entry("key3", ""),
                )
        }
    }
}
