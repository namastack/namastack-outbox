plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa") version "2.2.20"
    `maven-publish`
}

description = "spring-outbox-jpa"

dependencies {
    // Minimal JPA dependencies
    api("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.hibernate.orm:hibernate-core:6.6.3.Final")
    compileOnly("org.springframework:spring-orm:6.2.11")

    implementation(project(":spring-outbox-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.5.6")
    compileOnly("org.springframework.boot:spring-boot:3.5.6")

    // Test dependencies with full Spring Boot test setup
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.6")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.6")
    testImplementation("org.springframework.boot:spring-boot-testcontainers:3.5.6")
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.hibernate.orm:hibernate-core:6.6.3.Final")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
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
