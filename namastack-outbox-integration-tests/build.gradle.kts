plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-integration-tests"

dependencies {
    testImplementation(project(":namastack-outbox-starter-jdbc"))
    testImplementation(project(":namastack-outbox-kafka"))
    testImplementation(libs.spring.kafka)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.mockk)
    testImplementation(libs.h2)

    testRuntimeOnly(libs.junit.platform.launcher)
}
