package io.namastack.demo

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "namastack.outbox.polling.fixed.interval=100ms",
        "namastack.outbox.retry.fixed.delay=100ms",
        "namastack.outbox.observability.metrics.refresh-interval=200ms",
        "namastack.outbox.observability.metrics.max-age=1s",
        "namastack.outbox.instance.graceful-shutdown-timeout=1ms",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500",
    ],
)
@Testcontainers
@DirtiesContext
class ExampleMetricsSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("outbox_metrics")
                .withUsername("username")
                .withPassword("password")
                .withUrlParam("connectTimeout", "2")
                .withUrlParam("socketTimeout", "2")

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    private val client = HttpClient.newHttpClient()

    @Test
    fun `exports successful and failed message counts and processing timers`() {
        assertThat(request("/messages?count=2", post = true).statusCode()).isEqualTo(202)
        assertThat(request("/messages?count=1&fail=true", post = true).statusCode()).isEqualTo(202)

        await().atMost(15, SECONDS).untilAsserted {
            assertThat(recordCount("completed")).isGreaterThanOrEqualTo(2.0)
            assertThat(recordCount("failed")).isGreaterThanOrEqualTo(1.0)
        }
        val scrape = request("/actuator/prometheus")
        assertThat(scrape.statusCode()).isEqualTo(200)
        assertThat(scrape.body()).contains(
            "outbox_records",
            "outbox_record_process_seconds_count",
            "jvm_memory_used_bytes",
        )
        assertThat(request("/messages?count=0", post = true).statusCode()).isEqualTo(400)
    }

    @Test
    fun `scrape stays responsive while postgres is paused and recovers afterwards`() {
        await().atMost(15, SECONDS).until { lastSuccess().isFinite() }
        val beforeOutage = lastSuccess()
        val pingsBeforeOutage = meterRegistry.get("demo.pings").counter().count()
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
        try {
            assertThat(request("/ping").body()).isEqualTo("pong")
            assertThat(meterRegistry.get("demo.pings").counter().count()).isEqualTo(pingsBeforeOutage + 1)
            await().atMost(10, SECONDS).untilAsserted {
                val scrape = request("/actuator/prometheus", timeout = Duration.ofSeconds(1))
                assertThat(scrape.statusCode()).isEqualTo(200)
                assertThat(scrape.body()).contains("demo_pings_total", "jvm_memory_used_bytes")
                val recordLines = scrape.body().lineSequence().filter { it.startsWith("outbox_records{") }.toList()
                assertThat(recordLines).hasSize(3)
                assertThat(recordLines).allMatch { it.endsWith(" NaN") }
            }
        } finally {
            postgres.dockerClient?.unpauseContainerCmd(postgres.containerId)?.exec()
        }
        await().atMost(20, SECONDS).untilAsserted {
            assertThat(lastSuccess()).isGreaterThan(beforeOutage)
            assertThat(recordCount("new")).isFinite()
            assertThat(request("/actuator/prometheus").statusCode()).isEqualTo(200)
        }
    }

    private fun recordCount(status: String): Double =
        meterRegistry
            .get("outbox.records")
            .tag("outbox.record.status", status)
            .gauge()
            .value()

    private fun lastSuccess(): Double =
        meterRegistry
            .get("outbox.metrics.collection.last.success.timestamp")
            .gauge()
            .value()

    private fun request(
        path: String,
        post: Boolean = false,
        timeout: Duration = Duration.ofSeconds(5),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(timeout)
        if (post) builder.POST(HttpRequest.BodyPublishers.noBody())
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
