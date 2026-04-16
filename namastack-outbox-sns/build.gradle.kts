plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    jacoco
}

description = "namastack-outbox-sns"

dependencies {
    implementation(project(":namastack-outbox-api"))

    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.aws.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.cloud.aws.sns)
    implementation(libs.spring.cloud.aws.autoconfigure)
    implementation(libs.slf4j.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
