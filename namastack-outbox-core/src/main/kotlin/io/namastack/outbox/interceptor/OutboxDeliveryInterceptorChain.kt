package io.namastack.outbox.interceptor

class OutboxDeliveryInterceptorChain(
    private val interceptors: List<OutboxDeliveryInterceptor>,
) {
    fun applyBeforeHandler(context: OutboxDeliveryInterceptorContext) {
        for (interceptor in interceptors) {
            interceptor.beforeHandler(context)
        }
    }

    fun applyAfterHandler(context: OutboxDeliveryInterceptorContext) {
        for (interceptor in interceptors) {
            interceptor.afterHandler(context)
        }
    }

    fun applyOnError(
        context: OutboxDeliveryInterceptorContext,
        error: Exception,
    ) {
        for (interceptor in interceptors) {
            interceptor.onError(context, error)
        }
    }

    fun applyAfterCompletion(context: OutboxDeliveryInterceptorContext) {
        for (interceptor in interceptors) {
            interceptor.afterCompletion(context)
        }
    }
}
