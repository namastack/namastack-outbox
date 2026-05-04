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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
class ExamplePostgresqlSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("outbox_example")
            .withUsername("username")
            .withPassword("password")

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
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
