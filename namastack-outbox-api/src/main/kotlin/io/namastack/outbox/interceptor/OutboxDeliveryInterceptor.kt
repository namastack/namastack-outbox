package io.namastack.outbox.interceptor

interface OutboxDeliveryInterceptor {
    fun beforeHandler(context: OutboxDeliveryInterceptorContext) {
        return
    }

    fun afterHandler(context: OutboxDeliveryInterceptorContext) {
        return
    }

    fun onError(
        context: OutboxDeliveryInterceptorContext,
        error: Exception,
    ) {
        return
    }

    fun afterCompletion(context: OutboxDeliveryInterceptorContext) {
        return
    }
}
