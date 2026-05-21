package io.namastack.demo;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class ExampleSnsSmokeTest {

  @Container
  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
          .withServices(LocalStackContainer.Service.SNS);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.aws.sns.endpoint",
        () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SNS).toString());
    registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
    registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
    registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
  }

  @BeforeAll
  static void createTopics() {
    try (
        var snsClient = SnsClient.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SNS))
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .build()) {
      snsClient.createTopic(r -> r.name("customer-registrations"));
      snsClient.createTopic(r -> r.name("default-topic"));
      snsClient.createTopic(r -> r.name("customers"));
    }
  }

  @Test
  void publishesToSns(CapturedOutput output) {
    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> {
          assertLogMessage(
              output,
              "SnsOutboxHandler",
              "Successfully sent outbox record to SNS"
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


