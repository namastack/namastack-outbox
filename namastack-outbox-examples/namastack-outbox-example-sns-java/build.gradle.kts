plugins {
    java
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.namastack"
version = "0.1.0"
description = "namastack-outbox-example-sns-java"

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
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sns:3.4.0")
    implementation("io.namastack:namastack-outbox-starter-jpa:1.4.0-SNAPSHOT")
    implementation("io.namastack:namastack-outbox-sns:1.4.0-SNAPSHOT")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
