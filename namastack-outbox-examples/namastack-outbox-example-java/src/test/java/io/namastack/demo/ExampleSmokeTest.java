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

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ExampleSmokeTest {
    @Test
    void printsAnnotationHandlerSignal(CapturedOutput output) {
        await()
                .atMost(10, SECONDS)
                .untilAsserted(() -> {
                    assertLogMessage(
                            output,
                            "GenericOutboxHandler",
                            "[Handler] Publish CustomerRegisteredEvent"
                    );
                    assertLogMessage(
                            output,
                            "CustomerRegisteredOutboxHandler",
                            "[Handler] Send email"
                    );
                });
    }

    void assertLogMessage(CapturedOutput output, String... messages) {
        boolean found = output.getOut().lines().anyMatch(line ->
                Arrays.stream(messages).allMatch(line::contains)
        );

        assertThat(found)
                .withFailMessage("Could not find log message containing: " + Arrays.toString(messages))
                .isTrue();
    }
}
