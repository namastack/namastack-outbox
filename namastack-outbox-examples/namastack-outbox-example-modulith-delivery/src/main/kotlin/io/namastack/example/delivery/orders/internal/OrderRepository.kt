package io.namastack.example.delivery.orders.internal

import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface OrderRepository : CrudRepository<CustomerOrder, UUID>
