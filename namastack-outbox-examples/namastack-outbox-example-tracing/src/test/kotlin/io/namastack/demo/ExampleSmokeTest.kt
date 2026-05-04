package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false",
    ],
)
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension::class)
class ExampleSmokeTest {
    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Test
    fun `prints tracing signals`(output: CapturedOutput) {
        restTestClient.get()
            .uri("/")
            .exchange()
            .expectStatus()
            .is2xxSuccessful

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                val trace1 = getTraceId(
                    output,
                    "DemoApplication",
                    "home() has been called"
                )
                val trace2 = getTraceId(
                    output,
                    "GenericOutboxHandler",
                    "[Handler] Publish CustomerRegisteredEvent"
                )
                val trace3 = getTraceId(
                    output,
                    "CustomerRegisteredOutboxHandler",
                    "[Handler] Send email"
                )
                assertThat(trace1)
                    .isEqualTo(trace2)
                    .isEqualTo(trace3)
            }
    }

    fun getTraceId(output: CapturedOutput, vararg messages: String): String? {
        val found = output.out.lines().firstOrNull { line ->
            messages.all { line.contains(it) }
        }
        assertThat(found)
            .withFailMessage("Could not find log message")
            .isNotNull()

        val traceId = extractTraceId(found!!)
        assertThat(traceId)
            .isNotEmpty()

        return traceId
    }

    fun extractTraceId(logMessage: String): String? {
        val regex = """\[([a-f0-9]{32})-([a-f0-9]{16})]""".toRegex()
        return regex.find(logMessage)?.groups?.get(1)?.value
    }
}
