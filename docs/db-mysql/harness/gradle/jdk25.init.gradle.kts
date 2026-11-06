// Init script for building the measured worktrees (main, branch, experiments, final) on a host with JDK 25 only.
// The project pins a Java 17 toolchain; this script switches every project's toolchain to 25 without changing any worktree file.
// Bytecode stays Java 17: the project sets `options.release = 17` and the Kotlin `jvmTarget = 17` explicitly.
// usage: ./gradlew --init-script <harness>/gradle/jdk25.init.gradle.kts <tasks>
allprojects {
    afterEvaluate {
        extensions.findByType(JavaPluginExtension::class.java)?.toolchain?.languageVersion?.set(JavaLanguageVersion.of(25))
    }
}
