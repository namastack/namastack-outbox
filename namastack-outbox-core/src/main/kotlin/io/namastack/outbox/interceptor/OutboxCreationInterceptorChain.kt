package io.namastack.outbox.interceptor

class OutboxCreationInterceptorChain(
    private val interceptors: List<OutboxCreationInterceptor>,
) {
    fun applyBeforePersist(attributes: MutableMap<String, String>) {
        for (interceptor in interceptors) {
            interceptor.beforePersist(attributes)
        }
    }
}
