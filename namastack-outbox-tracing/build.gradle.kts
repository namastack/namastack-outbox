plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-tracing"

dependencies {
    api(project(":namastack-outbox-observability-api"))
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.tracing)
    implementation(libs.micrometer.tracing)
    implementation(libs.micrometer.observation)
    implementation(libs.slf4j.api)

    testImplementation(project(":namastack-outbox-starter-jpa"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.micrometer.opentelemetry)
    testImplementation(libs.spring.boot.opentelemetry)
    testImplementation(libs.micrometer.tracing.bridge.otel)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.h2)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
