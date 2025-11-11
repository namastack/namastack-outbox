plugins {
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.kotlin.jvm)
}

allprojects {
    group = "io.namastack"
    version = "0.1.0"

    repositories {
        mavenLocal()
        mavenCentral()
    }
}

kotlin {
    jvmToolchain(21)
}
