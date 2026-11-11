plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

description = "namastack-outbox-api"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
