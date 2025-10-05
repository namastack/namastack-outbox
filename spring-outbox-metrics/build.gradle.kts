plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "spring-outbox-metrics"

dependencies {
    implementation(project(":spring-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.micrometer.core)
}
