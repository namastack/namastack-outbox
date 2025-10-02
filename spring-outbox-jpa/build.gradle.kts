import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa") version "2.2.20"
    `maven-publish`
    jacoco
}

description = "spring-outbox-jpa"

dependencies {
    implementation("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.hibernate.orm:hibernate-core:7.1.2.Final")
    compileOnly("org.springframework:spring-orm:6.2.11")

    implementation(project(":spring-outbox-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    compileOnly("org.springframework.boot:spring-boot:3.5.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.6")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.6")
    testImplementation("com.h2database:h2:2.4.240")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.14.6")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        exceptionFormat = FULL
        showStandardStreams = true
        events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = "com.beisel"
            artifactId = "spring-outbox-jpa"
            version = project.version.toString()

            pom {
                name.set("Spring Outbox JPA")
                description.set("JPA implementation for Spring Outbox")
            }
        }
    }
}
