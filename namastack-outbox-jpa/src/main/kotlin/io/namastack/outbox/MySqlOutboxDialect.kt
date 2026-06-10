package io.namastack.outbox

import org.hibernate.dialect.MariaDBDialect
import org.hibernate.dialect.MySQLDialect
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo
import java.sql.Types

/**
 * MySQL reports LONGTEXT columns as LONGVARCHAR, even when they are declared
 * with utf8mb4 and used as Hibernate's nationalized long text equivalent.
 */
class MySqlOutboxDialect : MySQLDialect {
    @Suppress("unused")
    constructor() : super()

    @Suppress("unused")
    constructor(info: DialectResolutionInfo) : super(info)

    override fun equivalentTypes(
        typeCode1: Int,
        typeCode2: Int,
    ): Boolean =
        super.equivalentTypes(typeCode1, typeCode2) ||
            areMySqlLongTextTypes(typeCode1, typeCode2)
}

class MariaDbOutboxDialect : MariaDBDialect {
    @Suppress("unused")
    constructor() : super()

    @Suppress("unused")
    constructor(info: DialectResolutionInfo) : super(info)

    override fun equivalentTypes(
        typeCode1: Int,
        typeCode2: Int,
    ): Boolean =
        super.equivalentTypes(typeCode1, typeCode2) ||
            areMySqlLongTextTypes(typeCode1, typeCode2)
}

private fun areMySqlLongTextTypes(
    typeCode1: Int,
    typeCode2: Int,
): Boolean = isMySqlLongTextType(typeCode1) && isMySqlLongTextType(typeCode2)

private fun isMySqlLongTextType(typeCode: Int): Boolean =
    typeCode == Types.LONGVARCHAR ||
        typeCode == Types.LONGNVARCHAR ||
        typeCode == Types.NCLOB
