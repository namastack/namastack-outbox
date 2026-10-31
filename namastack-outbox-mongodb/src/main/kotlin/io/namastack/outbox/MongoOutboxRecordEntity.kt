package io.namastack.outbox

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Entity representing an outbox record in MongoDB.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
@Document(collection = MongoOutboxRecordEntity.COLLECTION_NAME)
@CompoundIndexes(
    value = [
        CompoundIndex(name = "record_key_created_idx", def = "{'recordKey': 1, 'createdAt': 1}"),
        CompoundIndex(name = "partition_status_retry_idx", def = "{'partitionNo': 1, 'status': 1, 'nextRetryAt': 1}"),
        CompoundIndex(name = "status_retry_idx", def = "{'status': 1, 'nextRetryAt': 1}"),
        CompoundIndex(
            name = "incomplete_created_idx",
            def = "{'createdAt': 1, '_id': 1, 'partitionNo': 1, 'status': 1, 'nextRetryAt': 1, 'recordKey': 1}",
            partialFilter = "{'completedAt': null}",
        ),
        CompoundIndex(
            name = "incomplete_key_created_idx",
            def = "{'recordKey': 1, 'createdAt': 1, '_id': 1}",
            partialFilter = "{'completedAt': null}",
        ),
    ],
)
data class MongoOutboxRecordEntity(
    @Id
    val id: String,
    @Indexed
    val status: OutboxRecordStatus,
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val context: String?,
    val partitionNo: Int,
    val createdAt: Instant,
    val completedAt: Instant?,
    val failureCount: Int,
    val failureReason: String?,
    val nextRetryAt: Instant,
    val handlerId: String,
) {
    companion object {
        const val COLLECTION_NAME = "outbox_records"
    }
}
