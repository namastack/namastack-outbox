import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa") version "2.2.20"
    `maven-publish`
    jacoco
}

description = "spring-outbox-metrics"

dependencies {

    implementation(project(":spring-outbox-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    compileOnly("io.micrometer:micrometer-core:1.15.4")
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        exceptionFormat = FULL
        showStandardStreams = true
        events(PASSED, SKIPPED, FAILED)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = "io.namastack"
            artifactId = "spring-outbox-metrics"
            version = project.version.toString()

            pom {
                name.set("Spring Outbox Metrics")
                description.set("Metrics Implementation for Spring Outbox")
            }
        }
    }
}
