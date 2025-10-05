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

description = "spring-outbox-actuator"

dependencies {

    implementation(project(":spring-outbox-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    implementation("org.springframework.boot:spring-boot-actuator:3.5.6")
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
            artifactId = "spring-outbox-actuator"
            version = project.version.toString()

            pom {
                name.set("Spring Outbox Actuator")
                description.set("Actuator Endpoints for Spring Outbox")
            }
        }
    }
}
