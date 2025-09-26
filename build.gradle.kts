plugins {
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0" apply false
    kotlin("jvm") version "2.2.20"
    id("jacoco-report-aggregation")
    jacoco
}

dependencies {
    jacocoAggregation(project(":spring-outbox-core"))
    jacocoAggregation(project(":spring-outbox-jpa"))
}

allprojects {
    group = "com.beisel"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply { plugin("org.jlleitschuh.gradle.ktlint") }
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

tasks.check {
    dependsOn(tasks.named<JacocoReport>("testCodeCoverageReport"))
}
