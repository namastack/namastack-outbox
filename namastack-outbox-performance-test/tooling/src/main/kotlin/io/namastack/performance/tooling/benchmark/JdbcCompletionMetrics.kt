package io.namastack.performance.tooling.benchmark

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** Collects benchmark-only writer and completion-wait measurements. @author Roland Beisel */
internal class JdbcCompletionMetrics(
    private val database: JdbcBenchmarkDatabase,
) {
    fun collect(runId: String) {
        if (System.getenv("PERF_COMPLETION_METRICS") != "true") return
        val instances = checkNotNull(System.getenv("PERF_INSTANCES")).toInt()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val snapshots =
            (1..instances).map { instance ->
                val uri =
                    URI(
                        "http://127.0.0.1:${18080 + instance}/completion-metrics?run=${URLEncoder.encode(
                            runId,
                            UTF_8,
                        )}",
                    )
                val response =
                    client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                        BodyHandlers.ofString(),
                    )
                check(response.statusCode() == 200) { "Consumer $instance metrics returned ${response.statusCode()}" }
                database.mapper.readTree(response.body())
            }
        database.write("completion-metrics-$runId.json", snapshots)
    }
}
