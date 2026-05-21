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
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
class ExampleSqlServerSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val sqlServer = MSSQLServerContainer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
            .withPassword("ThisIsAComplexPassw0rd123!")
            .acceptLicense()

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { sqlServer.jdbcUrl }
            registry.add("spring.datasource.username") { sqlServer.username }
            registry.add("spring.datasource.password") { sqlServer.password }
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
