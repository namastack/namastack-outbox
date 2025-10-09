import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.jvm)
    id("jacoco-report-aggregation")
    jacoco
}

dependencies {
    jacocoAggregation(project(":spring-outbox-actuator"))
    jacocoAggregation(project(":spring-outbox-core"))
    jacocoAggregation(project(":spring-outbox-jpa"))
    jacocoAggregation(project(":spring-outbox-metrics"))
    jacocoAggregation(project(":spring-outbox-starter-jpa"))
}

val isRelease = project.hasProperty("release") && project.property("release") == "true"

allprojects {
    group = "io.namastack"
    version = "0.1.0" + if (!isRelease) "-SNAPSHOT" else ""

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
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

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

        tasks.register<Jar>("dokkaHtmlJar") {
            dependsOn(tasks.named("dokkaHtml"))
            from(tasks.named<DokkaTask>("dokkaHtml").flatMap { it.outputDirectory })
            archiveClassifier.set("html-docs")
        }

        tasks.register<Jar>("dokkaJavadocJar") {
            dependsOn(tasks.named("dokkaJavadoc"))
            from(tasks.named<DokkaTask>("dokkaJavadoc").flatMap { it.outputDirectory })
            archiveClassifier.set("javadoc")
        }

        tasks.build {
            dependsOn(tasks.named<Jar>("dokkaJavadocJar"))
            finalizedBy(tasks.named("publishToMavenLocal"))
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    artifact(tasks["dokkaJavadocJar"])

                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()

                    pom {
                        name.set(project.name)
                        description.set(project.description ?: "Spring Outbox Module")
                        url.set("https://github.com/namastack/spring-outbox")

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
                            connection.set("scm:git:https://github.com/namastack/spring-outbox.git")
                            developerConnection.set("scm:git:ssh://github.com/namastack/spring-outbox.git")
                            url.set("https://github.com/namastack/spring-outbox")
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "OSSRH"
                    val releasesRepoUrl =
                        uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl =
                        uri("https://central.sonatype.com/repository/maven-snapshots/")
                    url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

                    credentials {
                        username = System.getenv("OSSRH_USERNAME")
                        password = System.getenv("OSSRH_PASSWORD")
                    }
                }
            }
        }

        configure<SigningExtension> {
            val signingKey = System.getenv("SIGNING_KEY")
            val signingPassword = System.getenv("SIGNING_PASSWORD")

            if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(the<PublishingExtension>().publications["maven"])
            }
        }
    }
}
