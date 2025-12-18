plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-jackson"

dependencies {
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.jackson.databind)
    compileOnly(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
