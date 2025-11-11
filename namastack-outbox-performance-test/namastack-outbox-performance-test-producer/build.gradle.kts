plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
}

description = "namastack-outbox-performance-test-producer"

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation("io.namastack:namastack-outbox-starter-jpa:0.3.0-SNAPSHOT")
    runtimeOnly(libs.postgresql)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
