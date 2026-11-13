package io.namastack.performance.consumer

import com.sun.net.httpserver.HttpServer
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.completion.OutboxCompletion
import io.namastack.outbox.completion.OutboxCompletionPersistence
import io.namastack.outbox.completion.OutboxCompletionWriter
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * Benchmark-only observation of the real persistence and writer beans.
 * The loopback endpoint is reachable only inside the benchmark JVM container.
 *
 * @author Roland Beisel
 */
@Component
@ConditionalOnProperty(name = ["perf.completion-metrics"], havingValue = "true")
class CompletionBenchmarkMetrics :
    BeanPostProcessor,
    InitializingBean,
    DisposableBean {
    private val runs = ConcurrentHashMap<String, RunMetrics>()
    private var server: HttpServer? = null

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        when (bean) {
            is OutboxCompletionWriter ->
                object : OutboxCompletionWriter {
                    override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> {
                        if (updates.isEmpty()) return bean.writeUpdates(updates)
                        return measured(metrics(updates.first().recordId).writer, updates.size, {
                            it.count { result ->
                                result.isFailure
                            }
                        }) {
                            bean.writeUpdates(updates)
                        }
                    }

                    override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> {
                        if (deletions.isEmpty()) return bean.writeDeletions(deletions)
                        return measured(metrics(deletions.first().recordId).writer, deletions.size, {
                            it.count { result ->
                                result.isFailure
                            }
                        }) {
                            bean.writeDeletions(deletions)
                        }
                    }
                }

            is OutboxCompletionPersistence ->
                object : OutboxCompletionPersistence {
                    override fun updateProcessingState(record: OutboxRecord<*>) =
                        measured(metrics(record.id).completion, 1, { 0 }) { bean.updateProcessingState(record) }

                    override fun deleteCompleted(id: String) =
                        measured(metrics(id).completion, 1, { 0 }) { bean.deleteCompleted(id) }
                }

            else -> bean
        }

    private fun metrics(id: String): RunMetrics =
        runs.computeIfAbsent(id.substringBeforeLast("-record-")) { RunMetrics() }

    internal fun snapshot(runId: String): Map<String, Any> =
        mapOf(
            "instance" to (System.getenv("PERF_INSTANCE_ID") ?: "test"),
            "runId" to runId,
            "capturedAt" to Instant.now().toString(),
            "writer" to (runs[runId]?.writer ?: Samples()).snapshot(),
            "completion" to (runs[runId]?.completion ?: Samples()).snapshot(),
        )

    override fun afterPropertiesSet() {
        val port = checkNotNull(System.getenv("PERF_METRICS_PORT")).toInt()
        val mapper = JsonMapper.builder().build()
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/completion-metrics") { exchange ->
                    exchange.use {
                        val runId = URLDecoder.decode(exchange.requestURI.rawQuery.removePrefix("run="), UTF_8)
                        val bytes = mapper.writeValueAsBytes(snapshot(runId))
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
                start()
            }
    }

    override fun destroy() {
        server?.stop(0)
    }

    private inline fun <T> measured(
        samples: Samples,
        size: Int,
        failures: (T) -> Int,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        samples.enter()
        var failed = size
        try {
            return block().also { failed = failures(it) }
        } finally {
            samples.record(size, System.nanoTime() - started, failed)
        }
    }

    private class RunMetrics {
        val writer = Samples()
        val completion = Samples()
    }

    private class Samples {
        private val calls = LongAdder()
        private val records = LongAdder()
        private val failures = LongAdder()
        private val nanos = LongAdder()
        private val active = AtomicInteger()
        private val peakActive = AtomicInteger()
        private val batchSizes = ConcurrentHashMap<Int, LongAdder>()
        private val durations = Array(LATENCY_UPPER_BOUNDS_MS.size) { LongAdder() }

        fun enter() {
            peakActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        }

        fun record(
            size: Int,
            elapsedNanos: Long,
            failed: Int,
        ) {
            calls.increment()
            records.add(size.toLong())
            failures.add(failed.toLong())
            nanos.add(elapsedNanos)
            batchSizes.computeIfAbsent(size) { LongAdder() }.increment()
            durations[LATENCY_UPPER_BOUNDS_MS.indexOfFirst { elapsedNanos / 1e6 <= it }].increment()
            active.decrementAndGet()
        }

        fun snapshot(): Map<String, Any> =
            mapOf(
                "calls" to calls.sum(),
                "records" to records.sum(),
                "failedRecords" to failures.sum(),
                "totalNanos" to nanos.sum(),
                "peakConcurrentCalls" to peakActive.get(),
                "batchSizeCounts" to batchSizes.entries.associate { it.key.toString() to it.value.sum() },
                "latencyUpperBoundsMs" to LATENCY_UPPER_BOUNDS_MS.toList(),
                "latencyCounts" to durations.map { it.sum() },
            )
    }

    private companion object {
        val LATENCY_UPPER_BOUNDS_MS =
            doubleArrayOf(
                0.1,
                0.25,
                0.5,
                1.0,
                2.0,
                4.0,
                8.0,
                16.0,
                32.0,
                64.0,
                128.0,
                256.0,
                512.0,
                1000.0,
                2000.0,
                4000.0,
                8000.0,
                16000.0,
                32000.0,
                64000.0,
                Double.MAX_VALUE,
            )
    }
}
