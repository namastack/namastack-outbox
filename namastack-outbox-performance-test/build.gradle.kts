import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

val javaVersion = 17
val jvmTargetVersion = JvmTarget.fromTarget(javaVersion.toString())

allprojects {
    group = "io.namastack"
    version = "1.6.0-SNAPSHOT"

    repositories {
        mavenLocal()
        mavenCentral()
    }
}

subprojects {
    tasks.withType<JavaCompile> {
        options.release.set(javaVersion)
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(jvmTargetVersion)
        }
    }
}
