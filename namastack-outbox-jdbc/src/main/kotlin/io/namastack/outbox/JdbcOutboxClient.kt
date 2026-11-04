package io.namastack.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcUtils
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/**
 * Associates the default JDBC client with the datasource executing its statements.
 *
 * Dialect detection is deferred until a query needs it. Custom JdbcClient implementations retain
 * portable queries and JDBC result limits without assuming which datasource they use.
 *
 * @param dataSource Datasource used for both statements and dialect detection
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class JdbcOutboxClient(
    dataSource: DataSource,
) : JdbcClient by JdbcClient.create(dataSource) {
    /** Resolves and caches query choices on first use, without opening a connection during construction. */
    val queryDialect: JdbcOutboxQueryDialect by lazy {
        val name = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName)
        JdbcOutboxQueryDialect.from(JdbcUtils.commonDatabaseName(name))
    }
}
