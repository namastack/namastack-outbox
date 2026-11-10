package io.namastack.performance.tooling.benchmark

import java.nio.file.Files
import java.util.Locale

/** Writes raw measurements and a short report without requiring a monitoring service. */
internal class JdbcBenchmarkReport(
    private val database: JdbcBenchmarkDatabase,
) {
    fun write(
        name: String,
        result: Map<String, Any>,
    ) {
        database.write("$name.json", result)
        val labels =
            when (result["mode"]) {
                "processing" ->
                    linkedMapOf(
                        "Produced" to "producedRecords",
                        "Processed" to "processedAtStopSnapshot",
                        "Remaining" to "remainingAtStopSnapshot",
                        "Failed" to "failedAtStopSnapshot",
                        "Average processed records/s" to "processedPerSecondConservative",
                        "Measured seconds (including snapshot)" to "measurementSeconds",
                        "Producer target met" to "producerTargetMet",
                        "Capacity screen passed" to "capacityScreenPassed",
                    )

                "producer-only" ->
                    linkedMapOf(
                        "Produced and committed" to "producedRecords",
                        "Verified stored" to "verifiedStoredRecords",
                        "Average inserted records/s" to "actualProducerRate",
                        "Producer target met" to "producerTargetMet",
                    )

                else ->
                    linkedMapOf(
                        "Repeatedly loaded records" to "loadedRecords",
                        "Loaded records/s (not processing throughput)" to "loadedRecordsPerSecond",
                        "Selection median ms" to "selectionMedianMs",
                        "Selection p95 ms" to "selectionP95Ms",
                        "Loading median ms" to "loadingMedianMs",
                        "Correctness" to "correctness",
                    )
            }
        val text =
            buildString {
                appendLine("# ${database.name} ${result["mode"]} benchmark")
                appendLine()
                appendLine("| Measurement | Result |")
                appendLine("| --- | ---: |")
                labels.forEach { (label, key) ->
                    val value = result[key]
                    val formatted = if (value is Double) String.format(Locale.ROOT, "%.2f", value) else value.toString()
                    appendLine("| $label | $formatted |")
                }
                appendLine()
                appendLine("See environment.json for settings, source revision and artifact hashes.")
                appendLine("Processing rates include the final snapshot query; read rates repeat a fixed corpus.")
            }
        Files.writeString(database.reportDirectory.resolve("summary.md"), text)
    }
}
