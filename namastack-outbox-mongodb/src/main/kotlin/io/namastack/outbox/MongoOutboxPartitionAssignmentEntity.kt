package io.namastack.outbox

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Entity representing a partition assignment in MongoDB.
 *
 * @author Stellar Hold
 * @since 1.1.0
 */
@Document(collection = "outbox_partition_assignments")
internal data class MongoOutboxPartitionAssignmentEntity(
    @Id
    val partitionNumber: Int,
    
    @Indexed
    val instanceId: String?,
    
    @Version
    val version: Long = 0,
    
    val updatedAt: Instant,
)
