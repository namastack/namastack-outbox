package io.namastack.outbox.tracing

import io.micrometer.tracing.Tracer
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@Import(
    OutboxTracingIntegrationTest.HandlerTraceCapture::class,
    OutboxTracingIntegrationTest.TraceCapturingHandler::class,
)
class OutboxTracingIntegrationTest {
    private val log = LoggerFactory.getLogger(OutboxTracingIntegrationTest::class.java)

    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var tracer: Tracer

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var handlerTraceCapture: HandlerTraceCapture

    @Test
    fun `stores tracing context and restores it during handler execution`() {
        val producerSpan = tracer.nextSpan().name("schedule-outbox-record").start()
        val producerTraceId = producerSpan.context().traceId()

        try {
            tracer.withSpan(producerSpan).use {
                log.info("Scheduling outbox record")
                outbox.schedule(TracingEvent("hello tracing"), "trace-key")
            }
        } finally {
            producerSpan.end()
        }

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                val records = recordRepository.findCompletedRecords()
                assertThat(records).hasSize(1)

                val traceparent = records.single().context[TRACEPARENT]
                assertThat(traceparent).isNotBlank()
                assertThat(extractTraceId(traceparent!!)).isEqualTo(producerTraceId)

                assertThat(handlerTraceCapture.metadataContext.get())
                    .containsEntry(TRACEPARENT, traceparent)
                assertThat(handlerTraceCapture.handlerTraceId.get()).isEqualTo(producerTraceId)
            }
    }

    private fun extractTraceId(traceparent: String): String = traceparent.split('-')[1]

    data class TracingEvent(
        val value: String,
    )

    @EnableScheduling
    @SpringBootApplication
    class TestApplication

    @Component
    class HandlerTraceCapture {
        val metadataContext = AtomicReference<Map<String, String>>(emptyMap())
        val handlerTraceId = AtomicReference<String?>()
    }

    @Component
    class TraceCapturingHandler(
        private val tracer: Tracer,
        private val handlerTraceCapture: HandlerTraceCapture,
    ) {
        private val log = LoggerFactory.getLogger(TraceCapturingHandler::class.java)

        @OutboxHandler
        fun handle(
            tracingEvent: TracingEvent,
            metadata: OutboxRecordMetadata,
        ) {
            log.info("Handling outbox record with value: {}", tracingEvent.value)
            handlerTraceCapture.metadataContext.set(metadata.context)
            handlerTraceCapture.handlerTraceId.set(tracer.currentSpan()?.context()?.traceId())
        }
    }

    private companion object {
        const val TRACEPARENT = "traceparent"
    }
}
