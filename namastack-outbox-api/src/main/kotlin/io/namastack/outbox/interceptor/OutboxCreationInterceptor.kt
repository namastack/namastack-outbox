package io.namastack.outbox.interceptor

interface OutboxCreationInterceptor {
    fun beforePersist(attributes: MutableMap<String, String>) {
        return
    }
}
