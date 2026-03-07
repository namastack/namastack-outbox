plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-tracing"

dependencies {
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.tracing)
    implementation(libs.micrometer.tracing)
    implementation(libs.micrometer.observation)
    implementation(libs.slf4j.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
