plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-tracing"

dependencies {
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.aspects)
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.spring.boot.opentelemetry)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
