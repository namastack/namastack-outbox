package io.namastack.example.delivery.billing.internal

import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface InvoiceRepository : CrudRepository<Invoice, UUID>
