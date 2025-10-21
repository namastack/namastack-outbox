package io.namastack.outbox

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.OffsetDateTime

@Entity
@Table(name = "outbox_lock")
internal data class OutboxLockEntity(
    @Id
    val aggregateId: String,
    val acquiredAt: OffsetDateTime,
    var expiresAt: OffsetDateTime,
    @Version
    val version: Long? = null,
)
