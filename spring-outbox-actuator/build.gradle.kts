plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "spring-outbox-actuator"

dependencies {
    implementation(project(":spring-outbox-core"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.actuator)
}
