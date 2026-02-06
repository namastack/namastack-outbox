plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-kafka"

dependencies {
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.logging)

    compileOnly(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
