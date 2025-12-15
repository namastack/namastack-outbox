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
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("io.namastack:namastack-outbox-starter-jpa:0.5.0-SNAPSHOT")
//    implementation("io.namastack:namastack-outbox-tracing:0.5.0-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-opentelemetry")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
