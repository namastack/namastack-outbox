package io.namastack.outbox.interceptor

import java.lang.reflect.Method
import java.time.OffsetDateTime

class OutboxDeliveryInterceptorContext(
    val key: String,
    val attributes: Map<String, String>,
    val handlerId: String,
    val handlerClass: Class<*>,
    val handlerMethod: Method,
    val failureCount: Int,
    val createdAt: OffsetDateTime,
) {
    private val state: MutableMap<String, Any> = mutableMapOf()

    fun <T> get(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return state[key] as? T
    }

    fun put(
        key: String,
        value: Any,
    ) {
        state[key] = value
    }
}
