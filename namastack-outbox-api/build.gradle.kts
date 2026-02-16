plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

description = "namastack-outbox-api"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.core)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
