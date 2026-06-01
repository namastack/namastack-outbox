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
    fun `JSON event handled by OrderCreatedHandler`(output: CapturedOutput) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertLogMessage(output, "OrderCreatedHandler", "[Handler] OrderCreatedHandler received order")
            }
    }

    @Test
    fun `protobuf event handled by OrderShippedHandler`(output: CapturedOutput) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertLogMessage(output, "OrderShippedHandler", "[Handler] OrderShippedHandler dispatched to")
            }
    }

    private fun assertLogMessage(output: CapturedOutput, vararg messages: String) {
        val found = output.out.lines().any { line -> messages.all { line.contains(it) } }
        assertThat(found)
            .withFailMessage("Could not find log message containing: ${messages.toList()}")
            .isTrue()
    }
}
