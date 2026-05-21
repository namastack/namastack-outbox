package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
class ExampleMariadbSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val mariadb = MariaDBContainer(DockerImageName.parse("mariadb:12"))
            .withDatabaseName("outbox_example")
            .withUsername("username")
            .withPassword("password")

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mariadb.jdbcUrl }
            registry.add("spring.datasource.username") { mariadb.username }
            registry.add("spring.datasource.password") { mariadb.password }
        }
    }

    @Test
    fun `prints annotation handler signal`(output: CapturedOutput) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertLogMessage(
                    output,
                    "GenericOutboxHandler",
                    "[Handler] Publish CustomerRegisteredEvent"
                )
                assertLogMessage(
                    output,
                    "CustomerRegisteredOutboxHandler",
                    "[Handler] Send email"
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
