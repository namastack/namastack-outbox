package io.namastack.outbox

import org.springframework.transaction.support.TransactionTemplate

inline fun <T> TransactionTemplate.executeNonNull(crossinline block: () -> T): T =
    this.execute { block() }
        ?: throw IllegalStateException("Transaction returned null unexpectedly")
