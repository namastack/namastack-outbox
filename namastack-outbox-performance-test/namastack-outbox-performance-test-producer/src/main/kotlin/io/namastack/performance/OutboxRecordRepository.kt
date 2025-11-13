package io.namastack.performance
import org.springframework.data.repository.reactive.ReactiveCrudRepository

interface OutboxRecordRepository : ReactiveCrudRepository<OutboxRecord, String>
