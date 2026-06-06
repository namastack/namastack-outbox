plugins {
    java
    id("org.springframework.boot") version "4.0.6"
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
    implementation(platform(libs.namastack.outbox.bom))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("io.namastack:namastack-outbox-starter-jpa")
    implementation("io.namastack:namastack-outbox-rabbit")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
