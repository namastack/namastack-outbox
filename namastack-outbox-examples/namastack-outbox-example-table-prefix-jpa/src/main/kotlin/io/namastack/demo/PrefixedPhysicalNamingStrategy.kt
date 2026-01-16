package io.namastack.demo

import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment

class PrefixedPhysicalNamingStrategy : PhysicalNamingStrategySnakeCaseImpl() {
    override fun toPhysicalTableName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier = Identifier.toIdentifier("custom_prefix_" + logicalName.text)
}
