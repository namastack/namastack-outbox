import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint) apply false
}

val javaVersion = 21
val jvmTargetVersion = JvmTarget.fromTarget(javaVersion.toString())

allprojects {
    group = "io.namastack"
    version = "1.11.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
        workerMaxHeapSize.set("256m")
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        testLogging {
            exceptionFormat = FULL
            showStandardStreams = true
            events(PASSED, SKIPPED, FAILED)
        }
    }

    tasks.withType<JavaCompile> {
        options.release.set(javaVersion)
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(jvmTargetVersion)
        }
    }
}
