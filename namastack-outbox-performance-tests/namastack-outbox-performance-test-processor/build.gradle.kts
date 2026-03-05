plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "namastack-outbox-performance-test-processor"

dependencies {
    implementation("io.namastack:namastack-outbox-starter-jpa:1.1.0-SNAPSHOT")
    implementation("io.namastack:namastack-outbox-metrics:1.1.0-SNAPSHOT")

    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
