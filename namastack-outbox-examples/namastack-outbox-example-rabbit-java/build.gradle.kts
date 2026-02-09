plugins {
    java
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.namastack"
version = "0.0.1-SNAPSHOT"
description = "namastack-outbox-example-rabbit-java"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("io.namastack:namastack-outbox-starter-jpa:1.0.0-SNAPSHOT")
    implementation("io.namastack:namastack-outbox-rabbit:1.0.0-SNAPSHOT")
    runtimeOnly("com.h2database:h2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
