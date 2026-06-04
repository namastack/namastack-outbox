package io.namastack.performance.tooling.report

import io.namastack.performance.tooling.internal.encode
import io.namastack.performance.tooling.internal.jsonMapper
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

internal class PrometheusClient(
    private val baseUrl: String,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun queryRange(
        query: String,
        start: Instant,
        end: Instant,
    ): List<MetricSeries> {
        val uri = URI.create("$baseUrl/api/v1/query_range?query=${encode(query)}&start=${start.epochSecond}&end=${end.epochSecond}&step=1")
        return try {
            val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) emptyList() else parseSeries(jsonMapper.readTree(response.body()))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSeries(root: JsonNode): List<MetricSeries> =
        root.path("data").path("result").mapIndexed { index, result ->
            val metric = result.path("metric")
            val name = metric.path("consumer").asString().ifEmpty { metric.path("instance").asString() }.ifEmpty { "series-${index + 1}" }
            val points =
                result.path("values").mapNotNull {
                    val number = it.path(1).asString().toDoubleOrNull() ?: return@mapNotNull null
                    MetricPoint(Instant.ofEpochSecond(it.path(0).asDouble().toLong()), number)
                }
            MetricSeries(name, points)
        }
}
