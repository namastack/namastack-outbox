plugins {
    id("org.springframework.boot") version "4.2.0-RC1"
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

group = "io.namastack"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.2.0-RC1")
    }
}

dependencies {
    implementation(enforcedPlatform(libs.namastack.outbox.bom))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("io.namastack:namastack-outbox-starter-jdbc")
    implementation("io.github.josipmusa:idempotency-spring-boot-starter:0.5.0")
    implementation("io.github.josipmusa:idempotency-jdbc:0.5.0")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
