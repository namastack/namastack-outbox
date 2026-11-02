package io.namastack.outbox.config

import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.jdbc.datasource.init.ScriptException
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Applies the additive outbox schema while tolerating another instance creating the same objects.
 *
 * An existence check and subsequent index creation are not atomic on every database. If another
 * instance wins that race, rerunning the idempotent script sees its newly created object. Other
 * failures retain Spring's fail-fast behavior, and a persistent naming conflict still fails startup.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxSchemaInitializer(
    dataSource: DataSource,
    settings: DatabaseInitializationSettings,
    private val databaseType: JdbcDatabaseType,
) : DataSourceScriptDatabaseInitializer(dataSource, settings) {
    override fun runScripts(scripts: Scripts) {
        repeat(3) { attempt ->
            try {
                super.runScripts(scripts)
                return
            } catch (failure: ScriptException) {
                if (attempt == 2 || !isConcurrentCreation(failure)) throw failure
            }
        }
    }

    private fun isConcurrentCreation(failure: ScriptException): Boolean {
        val sqlFailure =
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()
        return when (databaseType) {
            JdbcDatabaseType.MySQL -> sqlFailure?.errorCode == 1061
            JdbcDatabaseType.SQLServer -> sqlFailure?.errorCode in setOf(1913, 2714)
            JdbcDatabaseType.Oracle -> sqlFailure?.errorCode == 955
            else -> false
        }
    }
}
