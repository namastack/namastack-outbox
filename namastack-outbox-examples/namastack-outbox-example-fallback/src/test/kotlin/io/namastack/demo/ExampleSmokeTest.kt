package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class ExampleSmokeTest {
    @Test
    fun `prints annotation handler signal`(output: CapturedOutput) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertLogMessage(
                    output,
                    "CustomerRegisteredOutboxHandler",
                    "[Handler] Invoking annotation fallback method"
                )
                assertLogMessage(
                    output,
                    "GenericOutboxHandler",
                    "[Handler] Invoking interface fallback method"
                )
            }
    }

    fun assertLogMessage(output: CapturedOutput, vararg messages: String) {
        val found = output.out.lines().any { line ->
            messages.all { line.contains(it) }
        }
        assertThat(found)
            .withFailMessage("Could not find log message")
            .isTrue()
    }
}
