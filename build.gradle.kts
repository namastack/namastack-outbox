import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    id("jacoco-report-aggregation")
    jacoco
}

dependencies {
    jacocoAggregation(project(":namastack-outbox-actuator"))
    jacocoAggregation(project(":namastack-outbox-core"))
    jacocoAggregation(project(":namastack-outbox-jpa"))
    jacocoAggregation(project(":namastack-outbox-metrics"))
    jacocoAggregation(project(":namastack-outbox-starter-jpa"))
}

val isRelease = project.hasProperty("release") && project.property("release") == "true"

allprojects {
    group = "io.namastack"
    version = "0.4.0" + if (!isRelease) "-SNAPSHOT" else ""

    repositories {
        mavenLocal()
        mavenCentral()
    }

    apply { plugin("org.jlleitschuh.gradle.ktlint") }
}

kotlin {
    jvmToolchain(21)
}

tasks.check {
    dependsOn(tasks.named<JacocoReport>("testCodeCoverageReport"))
}

subprojects {
    apply(plugin = "org.jetbrains.dokka-javadoc")
    apply(plugin = "com.vanniktech.maven.publish")

    tasks.withType<Test> {
        useJUnitPlatform()

        testLogging {
            exceptionFormat = FULL
            showStandardStreams = true
            events(PASSED, SKIPPED, FAILED)
        }
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JVM_21)
        }
    }

    afterEvaluate {
        java {
            withSourcesJar()
        }

        dokka {
            moduleName = rootProject.name
        }

        tasks.build {
            finalizedBy(tasks.named("publishToMavenLocal"))
        }

        mavenPublishing {
            publishToMavenCentral()
            signAllPublications()

            coordinates(project.group.toString(), project.name, project.version.toString())

            pom {
                name.set(project.name)
                description.set(project.description ?: "Namastack Outbox Module")
                url.set("https://github.com/namastack/namastack-outbox")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("rolandbeisel")
                        name.set("Roland Beisel")
                        email.set("info@rolandbeisel.de")
                        url.set("https://rolandbeisel.de")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/namastack/namastack-outbox.git")
                    developerConnection.set("scm:git:ssh://github.com/namastack/namastack-outbox.git")
                    url.set("https://github.com/namastack/namastack-outbox")
                }
            }
        }
    }
}
