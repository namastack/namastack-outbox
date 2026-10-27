package io.namastack.example.delivery.notifications.internal

import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface NotificationRepository : CrudRepository<Notification, UUID>
