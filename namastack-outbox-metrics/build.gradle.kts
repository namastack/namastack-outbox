plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-metrics"

dependencies {
    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.micrometer.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
}
