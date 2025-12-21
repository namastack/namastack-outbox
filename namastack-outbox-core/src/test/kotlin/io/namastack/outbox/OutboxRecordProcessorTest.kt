package io.namastack.outbox

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class OutboxRecordProcessorTest {
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var handlerInvoker: OutboxHandlerInvoker
    private lateinit var fallbackHandlerInvoker: OutboxFallbackHandlerInvoker
    private lateinit var retryPolicyRegistry: OutboxRetryPolicyRegistry
    private lateinit var properties: OutboxProperties
    private lateinit var clock: Clock
    private lateinit var processor: OutboxRecordProcessor

    private val fixedInstant = Instant.parse("2024-01-01T10:00:00Z")

    @BeforeEach
    fun setUp() {
        recordRepository = mockk(relaxed = true)
        handlerInvoker = mockk(relaxed = true)
        fallbackHandlerInvoker = mockk(relaxed = true)
        retryPolicyRegistry = mockk(relaxed = true)
        properties = mockk(relaxed = true)
        clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

        every { properties.processing } returns
            mockk {
                every { deleteCompletedRecords } returns false
            }

        processor =
            OutboxRecordProcessor(
                recordRepository = recordRepository,
                handlerInvoker = handlerInvoker,
                fallbackHandlerInvoker = fallbackHandlerInvoker,
                retryPolicyRegistry = retryPolicyRegistry,
                properties = properties,
                clock = clock,
            )
    }

    @Test
    fun `processRecord dispatches to handler and marks as completed on success`() {
        val record = createRecord()
        every { handlerInvoker.dispatch(any(), any()) } just runs

        val result = processor.processRecord(record)

        assertThat(result).isTrue()
        verify { handlerInvoker.dispatch(record.payload, any()) }
        verify { recordRepository.save(record) }
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
    }

    @Test
    fun `processRecord deletes record when deleteCompletedRecords is true`() {
        val record = createRecord()
        every { properties.processing.deleteCompletedRecords } returns true
        every { handlerInvoker.dispatch(any(), any()) } just runs

        val result = processor.processRecord(record)

        assertThat(result).isTrue()
        verify { recordRepository.deleteById(record.id) }
        verify(exactly = 0) { recordRepository.save(any() as OutboxRecord<*>) }
    }

    @Test
    fun `processRecord schedules retry on retryable failure`() {
        val record = createRecord()
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = RuntimeException("Transient error")

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(exception) } returns true
        every { retryPolicy.nextDelay(any()) } returns Duration.ofSeconds(10)

        val result = processor.processRecord(record)

        assertThat(result).isFalse()
        verify { recordRepository.save(record) }
        assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(record.failureCount).isEqualTo(1)
        assertThat(record.nextRetryAt).isNotNull
    }

    @Test
    fun `processRecord invokes fallback when retries exhausted`() {
        val record = createRecord(failureCount = 3)
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = RuntimeException("Error")

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(exception) } returns true
        every { fallbackHandlerInvoker.dispatch(any(), any(), any()) } returns true

        val result = processor.processRecord(record)

        assertThat(result).isTrue()
        verify { fallbackHandlerInvoker.dispatch(eq(record.payload), any(), any()) }
        verify { recordRepository.save(record) }
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.failureCount).isEqualTo(4)
    }

    @Test
    fun `processRecord invokes fallback on non-retryable exception`() {
        val record = createRecord()
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = IllegalArgumentException("Validation error")

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(exception) } returns false
        every { fallbackHandlerInvoker.dispatch(any(), any(), any()) } returns true

        val result = processor.processRecord(record)

        assertThat(result).isTrue()
        verify { fallbackHandlerInvoker.dispatch(eq(record.payload), any(), any()) }
        assertThat(record.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(record.failureCount).isEqualTo(1)
    }

    @Test
    fun `processRecord marks as failed when fallback fails`() {
        val record = createRecord(failureCount = 3)
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val handlerException = RuntimeException("Handler error")
        val fallbackException = RuntimeException("Fallback error")

        every { handlerInvoker.dispatch(any(), any()) } throws handlerException
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(handlerException) } returns true
        every { fallbackHandlerInvoker.dispatch(any(), any(), any()) } throws fallbackException

        val result = processor.processRecord(record)

        assertThat(result).isFalse()
        verify { fallbackHandlerInvoker.dispatch(eq(record.payload), any(), any()) }
        verify { recordRepository.save(record) }
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
        assertThat(record.failureCount).isEqualTo(4)
    }

    @Test
    fun `processRecord marks as failed when no fallback and retries exhausted`() {
        val record = createRecord(failureCount = 3)
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = RuntimeException("Error")

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(exception) } returns true
        every { fallbackHandlerInvoker.dispatch(any(), any(), any()) } returns true

        // Simulate no fallback by making it return without throwing
        every { fallbackHandlerInvoker.dispatch(any(), any(), any()) } answers {
            // Fallback returns normally but record should be FAILED if fallback itself fails
            throw RuntimeException("Fallback not found")
        }

        val result = processor.processRecord(record)

        assertThat(result).isFalse()
        assertThat(record.status).isEqualTo(OutboxRecordStatus.FAILED)
    }

    @Test
    fun `processRecord passes correct failure context to fallback handler`() {
        val record = createRecord(failureCount = 2)
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = RuntimeException("Error")
        val contextSlot = slot<OutboxFailureContext>()

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 2
        every { retryPolicy.shouldRetry(exception) } returns true
        every { fallbackHandlerInvoker.dispatch(any(), any(), capture(contextSlot)) } returns true

        processor.processRecord(record)

        assertThat(contextSlot.captured.recordId).isEqualTo(record.id)
        assertThat(contextSlot.captured.failureCount).isEqualTo(3)
        assertThat(contextSlot.captured.handlerId).isEqualTo(record.handlerId)
        assertThat(contextSlot.captured.retriesExhausted).isTrue()
        assertThat(contextSlot.captured.nonRetryableException).isFalse()
    }

    @Test
    fun `processRecord sets retriesExhausted false for non-retryable exception`() {
        val record = createRecord()
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = IllegalArgumentException("Validation error")
        val contextSlot = slot<OutboxFailureContext>()

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(exception) } returns false
        every { fallbackHandlerInvoker.dispatch(any(), any(), capture(contextSlot)) } returns true

        processor.processRecord(record)

        assertThat(contextSlot.captured.retriesExhausted).isFalse()
        assertThat(contextSlot.captured.nonRetryableException).isTrue()
    }

    @Test
    fun `processRecord updates failure reason on error`() {
        val record = createRecord()
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = RuntimeException("Specific error message")

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(exception) } returns true
        every { retryPolicy.nextDelay(any()) } returns Duration.ofSeconds(5)

        processor.processRecord(record)

        assertThat(record.failureReason).isEqualTo("Specific error message")
    }

    @Test
    fun `processRecord passes correct metadata to handler`() {
        val record = createRecord()
        val metadataSlot = slot<OutboxRecordMetadata>()

        every { handlerInvoker.dispatch(any(), capture(metadataSlot)) } just runs

        processor.processRecord(record)

        assertThat(metadataSlot.captured.key).isEqualTo(record.key)
        assertThat(metadataSlot.captured.handlerId).isEqualTo(record.handlerId)
        assertThat(metadataSlot.captured.createdAt).isNotNull
    }

    @Test
    fun `processRecord calculates retry delay based on failure count`() {
        val record = createRecord(failureCount = 2)
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val exception = RuntimeException("Error")

        every { handlerInvoker.dispatch(any(), any()) } throws exception
        every { retryPolicyRegistry.getByHandlerId(record.handlerId) } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 5
        every { retryPolicy.shouldRetry(exception) } returns true
        every { retryPolicy.nextDelay(3) } returns Duration.ofSeconds(20)

        processor.processRecord(record)

        verify { retryPolicy.nextDelay(3) }
    }

    private fun createRecord(
        id: String = "record-123",
        key: String = "test-key",
        handlerId: String = "handler-1",
        payload: Any = TestPayload("test"),
        failureCount: Int = 0,
    ): OutboxRecord<Any> {
        val fixedOffsetDateTime = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"))

        return OutboxRecord.restore(
            id = id,
            recordKey = key,
            payload = payload,
            context = emptyMap(),
            createdAt = fixedOffsetDateTime,
            status = OutboxRecordStatus.NEW,
            completedAt = null,
            failureCount = failureCount,
            failureReason = null,
            partition = 1,
            nextRetryAt = fixedOffsetDateTime,
            handlerId = handlerId,
        )
    }

    private data class TestPayload(
        val data: String,
    )
}
