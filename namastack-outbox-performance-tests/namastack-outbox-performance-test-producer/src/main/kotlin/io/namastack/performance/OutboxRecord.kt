package io.namastack.performance

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

/**
 * JPA entity representing an outbox record in the database.
 *
 * This entity maps to the outbox_record table and contains all the necessary
 * fields for tracking outbox events including status, retry information,
 * and partition assignment.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */

@Table(name = "outbox_record")
data class OutboxRecord(
    @Id
    @Column("id")
    val entityId: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val status: String = "NEW",
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val partitionNo: Int,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime? = null,
    val failureCount: Int = 0,
    val failureReason: String? = null,
    val nextRetryAt: OffsetDateTime,
    val handlerId: String,
) : Persistable<String> {
    override fun getId(): String = entityId

    override fun isNew(): Boolean = true
}
