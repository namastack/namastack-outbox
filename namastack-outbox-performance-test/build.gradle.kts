plugins {
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

allprojects {
    group = "io.namastack"
    version = "0.1.0"

    repositories {
        mavenLocal()
        mavenCentral()
    }

    apply { plugin("org.jlleitschuh.gradle.ktlint") }
}

kotlin {
    jvmToolchain(21)
}
