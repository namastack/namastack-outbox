plugins {
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.springframework.boot") version "3.5.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0" apply false
    kotlin("jvm") version "2.2.0"
}

allprojects {
    group = "com.beisel"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply { plugin("org.jlleitschuh.gradle.ktlint") }
}
dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
kotlin {
    jvmToolchain(8)
}
