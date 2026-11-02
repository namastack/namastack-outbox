package io.namastack.performance.tooling.benchmark

import io.namastack.outbox.JacksonOutboxPayloadSerializer
import io.namastack.outbox.JdbcOutboxPersistenceFactory
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.partition.PartitionHasher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exercises the actual library selection and bulk-loading path against a stable mixed-state corpus. */
internal class JdbcReadBenchmark(
    private val database: JdbcBenchmarkDatabase,
) {
    fun run(
        duration: Duration,
        readers: Int,
        batchSize: Int,
        strict: Boolean,
        decode: Boolean,
        label: String,
    ) {
        require(!duration.isNegative && !duration.isZero && readers in 1..256 && batchSize > 0)
        val fixture = database.mapper.readTree(database.reportDirectory.resolve("fixture.json").toFile())
        val clock = Clock.fixed(Instant.parse(fixture["clock"].asString()), ZoneOffset.UTC)
        val perKey = fixture["recordsPerKey"].asInt()
        val totalKeys = fixture["fixtureRecords"].asLong() / perKey
        val runId = fixture["runId"].asString()
        val repository =
            JdbcOutboxPersistenceFactory
                .create(
                    database.source,
                    DataSourceTransactionManager(database.source),
                    JacksonOutboxPayloadSerializer(database.mapper),
                    clock,
                ).recordRepository
        val selectionNanos = Collections.synchronizedList(mutableListOf<Long>())
        val loadingNanos = Collections.synchronizedList(mutableListOf<Long>())
        val selectedKeys = AtomicLong()
        val loadedRecords = AtomicLong()
        val threads = Executors.newFixedThreadPool(readers)
        val started = System.nanoTime()
        val deadline = started + duration.toNanos()
        try {
            val futures =
                (0 until readers).map { worker ->
                    threads.submit {
                        val partitions = (0 until 256).filter { it % readers == worker }.toSet()
                        val expected =
                            (0L until totalKeys)
                                .filter { key ->
                                    (!strict || (key % 10 != 0L && key % 10 != 1L)) &&
                                        PartitionHasher.getPartitionForRecordKey(
                                            "payment:${key.toString().padStart(9, '0')}",
                                        ) in
                                        partitions
                                }.take(batchSize)
                                .map { "payment:${it.toString().padStart(9, '0')}" }
                        require(expected.isNotEmpty()) { "Fixture has no eligible keys for reader $worker" }
                        var checked = false
                        do {
                            val selectStarted = System.nanoTime()
                            val keys =
                                repository.findRecordKeysInPartitions(
                                    partitions,
                                    OutboxRecordStatus.NEW,
                                    batchSize,
                                    strict,
                                )
                            val selected = System.nanoTime()
                            if (!checked) {
                                check(keys == expected) { "Reader $worker returned unexpected eligible keys" }
                            }
                            var rows = 0L
                            keys.chunked(1000).forEach { chunk ->
                                val pages = repository.findIncompleteRecordPages(chunk.toSet())
                                for ((key, page) in pages) {
                                    if (!checked) {
                                        val group = key.removePrefix("payment:").toLong()
                                        val firstIndex = group * perKey
                                        val expectedIds =
                                            (firstIndex until firstIndex + perKey)
                                                .filter { index ->
                                                    !(index == firstIndex && (group % 10 == 0L || group % 10 == 2L))
                                                }.map { index ->
                                                    "$runId-record-${(index + 1).toString().padStart(12, '0')}"
                                                }
                                        check(
                                            (0 until page.size).map { page[it].id } == expectedIds,
                                        ) { "Incorrect bulk page for $key" }
                                    }
                                    if (decode) {
                                        for (index in 0 until page.size) {
                                            check(
                                                page[index].payload == READ_PAYLOAD,
                                            )
                                        }
                                    }
                                    rows += page.size
                                }
                            }
                            val loaded = System.nanoTime()
                            selectionNanos.add(selected - selectStarted)
                            loadingNanos.add(loaded - selected)
                            selectedKeys.addAndGet(keys.size.toLong())
                            loadedRecords.addAndGet(rows)
                            checked = true
                        } while (System.nanoTime() < deadline)
                    }
                }
            futures.forEach { it.get(duration.toSeconds() + 180, TimeUnit.SECONDS) }
        } finally {
            threads.shutdownNow()
        }
        val seconds = (System.nanoTime() - started) / 1e9

        fun percentile(
            values: List<Long>,
            fraction: Double,
        ): Double = values.sorted()[(fraction * (values.size - 1)).toInt()] / 1e6
        val result =
            mapOf(
                "database" to database.name,
                "mode" to "read",
                "strict" to strict,
                "decode" to decode,
                "readers" to readers,
                "keysPerQuery" to batchSize,
                "seconds" to seconds,
                "iterations" to selectionNanos.size,
                "selectedKeys" to selectedKeys.get(),
                "loadedRecords" to loadedRecords.get(),
                "selectedKeysPerSecond" to selectedKeys.get() / seconds,
                "loadedRecordsPerSecond" to loadedRecords.get() / seconds,
                "selectionMedianMs" to percentile(selectionNanos.toList(), .5),
                "selectionP95Ms" to percentile(selectionNanos.toList(), .95),
                "loadingMedianMs" to percentile(loadingNanos.toList(), .5),
                "loadingP95Ms" to percentile(loadingNanos.toList(), .95),
                "correctness" to "eligible keys and per-key creation/id order verified",
            )
        database.write("read-$label.json", result)
        println(database.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    }
}
