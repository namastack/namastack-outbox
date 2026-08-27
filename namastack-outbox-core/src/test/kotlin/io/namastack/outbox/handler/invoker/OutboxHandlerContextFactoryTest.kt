package io.namastack.outbox.handler.invoker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxHandlerContextFactoryTest {
    private val createdAt = Instant.parse("2025-01-01T00:00:00Z")
    private val failure = IllegalStateException("handler failed")

    @Test
    fun `creates metadata from record`() {
        val record = record(failureCount = 2)

        val metadata = OutboxHandlerContextFactory.metadata(record)

        assertThat(metadata.key).isEqualTo("record-key")
        assertThat(metadata.handlerId).isEqualTo("handler-id")
        assertThat(metadata.createdAt).isEqualTo(createdAt)
        assertThat(metadata.context).containsEntry("tenant", "north")
        assertThat(metadata.failureCount).isEqualTo(2)
        assertThat(metadata.attempt).isEqualTo(3)
    }

    @Test
    fun `uses explicit retry policy without consulting registry`() {
        val retryPolicies = mockk<OutboxRetryPolicyRegistry>()
        val explicitPolicy = mockk<OutboxRetryPolicy>()
        every { explicitPolicy.maxRetries() } returns 3
        every { explicitPolicy.shouldRetry(failure) } returns true

        val context =
            OutboxHandlerContextFactory.failure(
                record = record(failureCount = 2),
                exception = failure,
                retryPolicies = retryPolicies,
                explicitRetryPolicy = explicitPolicy,
            )

        assertThat(context.retriesExhausted).isFalse()
        assertThat(context.nonRetryableException).isFalse()
        assertThat(context.lastFailure).isSameAs(failure)

        verify(exactly = 0) { retryPolicies.getByHandlerId(any()) }
    }

    @Test
    fun `uses handler registry policy when explicit policy is absent`() {
        val retryPolicies = mockk<OutboxRetryPolicyRegistry>()
        val registeredPolicy = mockk<OutboxRetryPolicy>()

        every { retryPolicies.getByHandlerId("handler-id") } returns registeredPolicy
        every { registeredPolicy.maxRetries() } returns 1
        every { registeredPolicy.shouldRetry(failure) } returns false

        val context =
            OutboxHandlerContextFactory.failure(
                record = record(failureCount = 2),
                exception = failure,
                retryPolicies = retryPolicies,
            )

        assertThat(context.recordId).isEqualTo("record-id")
        assertThat(context.recordKey).isEqualTo("record-key")
        assertThat(context.handlerId).isEqualTo("handler-id")
        assertThat(context.retriesExhausted).isTrue()
        assertThat(context.nonRetryableException).isTrue()
        assertThat(context.context).containsEntry("tenant", "north")

        verify { retryPolicies.getByHandlerId("handler-id") }
    }

    private fun record(failureCount: Int): OutboxRecord<String> =
        OutboxRecord.restore(
            id = "record-id",
            recordKey = "record-key",
            payload = "payload",
            context = mapOf("tenant" to "north"),
            createdAt = createdAt,
            status = OutboxRecordStatus.NEW,
            completedAt = null,
            failureCount = failureCount,
            failureException = failure,
            failureReason = failure.message,
            partition = 1,
            nextRetryAt = createdAt,
            handlerId = "handler-id",
        )
}
