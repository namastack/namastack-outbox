plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-observability"

dependencies {
    api(project(":namastack-outbox-observability-api"))
    implementation(project(":namastack-outbox-api"))
    implementation(project(":namastack-outbox-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.tracing)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation)
    implementation(libs.slf4j.api)

    compileOnly(libs.micrometer.tracing)

    testImplementation(project(":namastack-outbox-starter-jpa"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.h2)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}
