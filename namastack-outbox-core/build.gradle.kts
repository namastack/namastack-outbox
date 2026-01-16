plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-core"

dependencies {
    implementation(project(":namastack-outbox-api"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.logging)
    implementation(libs.spring.tx)

    implementation(libs.commons.codec)
    implementation(libs.jakarta.annotation.api)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
