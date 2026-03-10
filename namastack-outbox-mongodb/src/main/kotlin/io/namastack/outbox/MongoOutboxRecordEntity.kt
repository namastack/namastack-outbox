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
 * @since 1.1.0
 */
@Document(collection = MongoOutboxRecordEntity.COLLECTION_NAME)
@CompoundIndexes(
    CompoundIndex(name = "record_key_created_idx", def = "{'recordKey': 1, 'createdAt': 1}"),
    CompoundIndex(name = "partition_status_retry_idx", def = "{'partitionNo': 1, 'status': 1, 'nextRetryAt': 1}"),
    CompoundIndex(name = "status_retry_idx", def = "{'status': 1, 'nextRetryAt': 1}"),
    CompoundIndex(name = "record_key_completed_created_idx", def = "{'recordKey': 1, 'completedAt': 1, 'createdAt': 1}")
)
internal data class MongoOutboxRecordEntity(
    @Id
    val id: String,
    
    @Indexed
    val status: OutboxRecordStatus,
    
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val context: String?,
    val partitionNo: Int,
    
    @Indexed
    val createdAt: Instant,
    
    val completedAt: Instant?,
    val failureCount: Int,
    val failureReason: String?,
    
    @Indexed
    val nextRetryAt: Instant,
    
    val handlerId: String,
) {
    companion object {
        const val COLLECTION_NAME = "outbox_records"
    }
}
