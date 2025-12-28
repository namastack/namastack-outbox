package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.retry.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import java.time.Duration

@Suppress("ktlint:standard:max-line-length")
object HandlerBeanFactory {
    fun createGenericInterfaceHandler(): io.namastack.outbox.handler.OutboxHandler = GenericInterfaceHandler()

    fun createGenericInterfaceHandlerWithFallback(): OutboxHandlerWithFallback = GenericInterfaceHandlerWithFallback()

    fun createTypedInterfaceHandler(): OutboxTypedHandler<String> = TypedInterfaceHandler()

    fun createTypedInterfaceHandlerWithFallback(): OutboxTypedHandlerWithFallback<String> =
        TypedInterfaceHandlerWithFallback()

    fun createAnnotatedTypedHandler(): AnnotatedTypedHandler = AnnotatedTypedHandler()

    fun createAnnotatedGenericHandler(): AnnotatedGenericHandler = AnnotatedGenericHandler()

    fun createAnnotatedTypedHandlerWithFallback(): AnnotatedTypedHandlerWithFallback =
        AnnotatedTypedHandlerWithFallback()

    fun createAnnotatedTypedHandlerWithGenericFallback(): AnnotatedTypedHandlerWithGenericFallback =
        AnnotatedTypedHandlerWithGenericFallback()

    fun createAnnotatedGenericHandlerWithFallback(): AnnotatedGenericHandlerWithFallback =
        AnnotatedGenericHandlerWithFallback()

    fun createMultiAnnotatedHandlerBean(): MultiAnnotatedHandlerBean = MultiAnnotatedHandlerBean()

    fun createInheritedHandler(): InheritedHandler = InheritedHandler()

    fun createAnnotatedHandlerBeanWithWrongSignature(): AnnotatedHandlerBeanWithWrongSignature =
        AnnotatedHandlerBeanWithWrongSignature()

    fun createAnnotatedHandlerBeanWithMultipleMatchingFallbacks(): AnnotatedHandlerBeanWithMultipleMatchingFallbacks =
        AnnotatedHandlerBeanWithMultipleMatchingFallbacks()

    fun createGenericInterfaceHandlerWithRetryPolicy(): GenericInterfaceHandlerWithRetryPolicy =
        GenericInterfaceHandlerWithRetryPolicy()

    fun createTypedInterfaceHandlerWithRetryPolicy(): TypedInterfaceHandlerWithRetryPolicy =
        TypedInterfaceHandlerWithRetryPolicy()

    fun createGenericAnnotatedHandlerWithRetryPolicyByClass(): GenericAnnotatedHandlerWithRetryPolicyByClass =
        GenericAnnotatedHandlerWithRetryPolicyByClass()

    fun createTypedAnnotatedHandlerWithRetryPolicyByClass(): TypedAnnotatedHandlerWithRetryPolicyByClass =
        TypedAnnotatedHandlerWithRetryPolicyByClass()

    fun createGenericAnnotatedHandlerWithRetryPolicyByName(): GenericAnnotatedHandlerWithRetryPolicyByName =
        GenericAnnotatedHandlerWithRetryPolicyByName()

    fun createTypedAnnotatedHandlerWithRetryPolicyByName(): TypedAnnotatedHandlerWithRetryPolicyByName =
        TypedAnnotatedHandlerWithRetryPolicyByName()

    fun createAnnotatedHandlerBeanWithNonMatchingFallback(): AnnotatedHandlerBeanWithNonMatchingFallback =
        AnnotatedHandlerBeanWithNonMatchingFallback()

    fun createAnnotatedHandlerBeanWithInvalidFallbackSignature(): AnnotatedHandlerBeanWithInvalidFallbackSignature =
        AnnotatedHandlerBeanWithInvalidFallbackSignature()

    fun createMultipleAnnotatedTypedHandlersWithMultipleFallbacks(): MultipleAnnotatedTypedHandlersWithMultipleFallbacks =
        MultipleAnnotatedTypedHandlersWithMultipleFallbacks()
}

class GenericInterfaceHandler : io.namastack.outbox.handler.OutboxHandler {
    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

class GenericInterfaceHandlerWithFallback : OutboxHandlerWithFallback {
    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }

    override fun handleFailure(
        payload: Any,
        context: OutboxFailureContext,
    ) {
    }
}

class TypedInterfaceHandler : OutboxTypedHandler<String> {
    override fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {}
}

class TypedInterfaceHandlerWithFallback : OutboxTypedHandlerWithFallback<String> {
    override fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {}

    override fun handleFailure(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedTypedHandler {
    @OutboxHandler
    fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedGenericHandler {
    @OutboxHandler
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedTypedHandlerWithFallback {
    @OutboxHandler
    fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class MultipleAnnotatedTypedHandlersWithMultipleFallbacks {
    @OutboxHandler
    fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxHandler
    fun handle(
        payload: Int,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(
        payload: Int,
        context: OutboxFailureContext,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedTypedHandlerWithGenericFallback {
    @OutboxHandler
    fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(
        payload: Any,
        context: OutboxFailureContext,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedGenericHandlerWithFallback {
    @OutboxHandler
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(
        payload: Any,
        context: OutboxFailureContext,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class MultiAnnotatedHandlerBean {
    @OutboxHandler
    fun handleString(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxHandler
    fun handleInt(
        payload: Int,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedHandlerBeanWithWrongSignature {
    @OutboxHandler
    fun handleString() {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedHandlerBeanWithNonMatchingFallback {
    @OutboxHandler
    fun handleString(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(
        payload: Int,
        context: OutboxFailureContext,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedHandlerBeanWithInvalidFallbackSignature {
    @OutboxHandler
    fun handleString(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure(payload: String) {
    }
}

@Suppress("UNUSED_PARAMETER")
class AnnotatedHandlerBeanWithMultipleMatchingFallbacks {
    @OutboxHandler
    fun handleString(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure1(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }

    @OutboxFallbackHandler
    fun handleFailure2(
        payload: String,
        context: OutboxFailureContext,
    ) {
    }
}

class GenericInterfaceHandlerWithRetryPolicy :
    io.namastack.outbox.handler.OutboxHandler,
    OutboxRetryAware {
    override fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }

    override fun getRetryPolicy(): OutboxRetryPolicy = CustomerOutboxRetryPolicy()
}

class TypedInterfaceHandlerWithRetryPolicy :
    OutboxTypedHandler<String>,
    OutboxRetryAware {
    override fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }

    override fun getRetryPolicy(): OutboxRetryPolicy = CustomerOutboxRetryPolicy()
}

@Suppress("UNUSED_PARAMETER")
class GenericAnnotatedHandlerWithRetryPolicyByClass {
    @OutboxHandler
    @OutboxRetryable(CustomerOutboxRetryPolicy::class)
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class TypedAnnotatedHandlerWithRetryPolicyByClass {
    @OutboxHandler
    @OutboxRetryable(CustomerOutboxRetryPolicy::class)
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class GenericAnnotatedHandlerWithRetryPolicyByName {
    @OutboxHandler
    @OutboxRetryable(name = "CustomerOutboxRetryPolicy")
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

@Suppress("UNUSED_PARAMETER")
class TypedAnnotatedHandlerWithRetryPolicyByName {
    @OutboxHandler
    @OutboxRetryable(name = "CustomerOutboxRetryPolicy")
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

open class BaseHandler {
    @OutboxHandler
    open fun handle(
        payload: String,
        metadata: OutboxRecordMetadata,
    ) {
    }
}

class InheritedHandler : BaseHandler()

class CustomerOutboxRetryPolicy : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable): Boolean = true

    override fun nextDelay(failureCount: Int): Duration = Duration.ofSeconds(1)

    override fun maxRetries(): Int = 3
}
