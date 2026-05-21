package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
class ExampleSnsSmokeTest {
    companion object {
        @Container
        @JvmStatic
        val localstack =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                .withServices(LocalStackContainer.Service.SNS)

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.sns.endpoint") {
                localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString()
            }
            registry.add("spring.cloud.aws.region.static") { localstack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localstack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localstack.secretKey }
        }

        @JvmStatic
        @BeforeAll
        fun createTopics() {
            val snsClient =
                SnsClient
                    .builder()
                    .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SNS))
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey),
                        ),
                    ).build()

            snsClient.createTopic { it.name("customer-registrations") }
            snsClient.createTopic { it.name("default-topic") }
            snsClient.createTopic { it.name("customers") }
            snsClient.close()
        }
    }

    @Test
    fun `publishes to sns`(output: CapturedOutput) {
        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertLogMessage(
                    output,
                    "SnsOutboxHandler",
                    "Successfully sent outbox record to SNS",
                )
            }
    }

    fun assertLogMessage(
        output: CapturedOutput,
        vararg messages: String,
    ) {
        val found =
            output.out.lines().any { line ->
                messages.all { line.contains(it) }
            }
        assertThat(found)
            .withFailMessage("Could not find log message")
            .isTrue()
    }
}
