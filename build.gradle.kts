import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kotlin.jvm)
    id("jacoco-report-aggregation")
    jacoco
}

dependencies {
    jacocoAggregation(project(":spring-outbox-core"))
    jacocoAggregation(project(":spring-outbox-jpa"))
}

allprojects {
    group = "io.namastack"
    version = "0.0.1-SNAPSHOT"

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
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // Centralize test configuration for all subprojects
    tasks.withType<Test> {
        useJUnitPlatform()

        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
            events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        }
    }

    afterEvaluate {
        val sourcesJar by tasks.registering(Jar::class) {
            archiveClassifier.set("sources")
            from(sourceSets.main.get().allSource)
        }

        val javadocJar by tasks.registering(Jar::class) {
            archiveClassifier.set("javadoc")
            from(tasks.named("javadoc"))
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])

                    artifact(sourcesJar)
                    artifact(javadocJar)

                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()

                    pom {
                        name.set(project.name)
                        description.set(project.description ?: "Spring Outbox module")
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
                    val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

                    credentials {
                        username = System.getenv("OSSRH_USERNAME")
                        password = System.getenv("OSSRH_PASSWORD")
                    }
                }
            }
        }
        /**
         configure<SigningExtension> {
         useInMemoryPgpKeys(
         System.getenv("SIGNING_KEY"),
         System.getenv("SIGNING_PASSWORD"),
         )
         sign(the<PublishingExtension>().publications["maven"])
         }**/
    }
}
