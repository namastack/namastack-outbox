plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "spring-outbox-core"

dependencies {

    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
}
