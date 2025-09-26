import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    `maven-publish`
}

description = "spring-outbox-core"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    compileOnly("org.springframework.boot:spring-boot")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.assertj:assertj-core")
    testImplementation("io.mockk:mockk:1.13.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.getByName<BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = "com.beisel"
            artifactId = "spring-outbox-core"
            version = project.version.toString()

            pom {
                name.set("Spring Outbox Core")
                description.set("Core components for Spring Outbox")
            }
        }
    }
}
