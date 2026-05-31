package io.namastack.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Arrays;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Smoke test verifying that both handler types (interface + annotated method) fire
 * and that the stable handler IDs appear in the logs.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ExampleSmokeTest {

    @Test
    void interfaceHandlerWithStableIdFires(CapturedOutput output) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted(() -> assertLogMessage(output,
                "CustomerEmailHandler",
                "Sending welcome email"
            ));
    }

    @Test
    void annotatedMethodHandlerWithStableIdFires(CapturedOutput output) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted(() -> {
                assertLogMessage(output, "CustomerEventHandler", "Publishing CustomerRegisteredEvent");
                assertLogMessage(output, "CustomerEventHandler", "Publishing CustomerRemovedEvent");
            });
    }

    private void assertLogMessage(CapturedOutput output, String... messages) {
        boolean found = output.getOut().lines().anyMatch(line ->
            Arrays.stream(messages).allMatch(line::contains)
        );
        assertThat(found)
            .withFailMessage("Could not find log message containing: " + Arrays.toString(messages))
            .isTrue();
    }
}
