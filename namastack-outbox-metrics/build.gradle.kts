plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-metrics"

dependencies {
    implementation(project(":namastack-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.micrometer.core)
}
