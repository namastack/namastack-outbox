package io.namastack.performance.tooling.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Records the settings and built artifacts actually used by a contributor's run.
 *
 * @author Roland Beisel
 */
internal class JdbcBenchmarkEnvironment(
    private val database: JdbcBenchmarkDatabase,
) {
    fun write(options: Map<String, String>) {
        val environment = System.getenv()
        val settings =
            environment.filterKeys {
                (it.startsWith("PERF_") && !it.contains("PASSWORD") && !it.contains("DB_URL")) ||
                    it == "NAMASTACK_OUTBOX_PROCESSING_DELETECOMPLETEDRECORDS"
            }
        val paths =
            listOf(
                Path.of("tooling/build/install/tooling/lib"),
                Path.of("consumer/build/libs"),
            )
        val artifacts =
            paths.flatMap { directory ->
                Files.list(directory).use { files ->
                    files
                        .filter { it.fileName.toString().endsWith(".jar") }
                        .sorted()
                        .map { path ->
                            mapOf(
                                "path" to path.toString(),
                                "sha256" to
                                    MessageDigest
                                        .getInstance("SHA-256")
                                        .digest(Files.readAllBytes(path))
                                        .joinToString("") { "%02x".format(it) },
                            )
                        }.toList()
                }
            }
        database.write(
            "environment.json",
            mapOf(
                "options" to options.filterKeys { it != "report-dir" && it != "action" },
                "javaVersion" to System.getProperty("java.runtime.version"),
                "environment" to settings,
                "artifacts" to artifacts,
            ),
        )
    }
}
