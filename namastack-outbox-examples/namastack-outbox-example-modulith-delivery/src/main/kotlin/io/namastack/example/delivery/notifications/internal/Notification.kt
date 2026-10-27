package io.namastack.example.delivery.notifications.internal

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("NOTIFICATION")
data class Notification(
    @Id val id: UUID = UUID.randomUUID(),
    val orderId: UUID,
    val kind: String,
    @Version var version: Long? = null,
)
