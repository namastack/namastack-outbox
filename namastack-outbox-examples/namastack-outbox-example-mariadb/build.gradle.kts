plugins {
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlin.plugin.jpa") version "2.2.21"
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.21"
}

group = "io.namastack"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("io.namastack:namastack-outbox-starter-jpa:1.0.0-RC1-SNAPSHOT")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
