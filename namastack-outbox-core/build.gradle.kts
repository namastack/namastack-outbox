plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-core"

dependencies {

    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.logging)
    implementation(libs.commons.codec)
    implementation(libs.jakarta.annotation.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
}
