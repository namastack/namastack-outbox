package io.namastack.outbox.handler.registry

import io.namastack.outbox.handler.ReflectionUtils
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod

/**
 * Projects invocable handler methods into public operational descriptors.
 *
 * Descriptors expose the canonical handler ID only; routing aliases remain an internal lookup
 * concern.
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal object OutboxHandlerDescriptorFactory {
    /**
     * Creates an operational descriptor for a primary handler.
     *
     * @param handler Registered primary handler to describe
     * @return Read-only descriptor containing the canonical ID and reflected method metadata
     */
    fun create(handler: OutboxHandlerMethod): OutboxHandlerDescriptor =
        OutboxHandlerDescriptor(
            id = handler.id,
            kind = if (handler is TypedHandlerMethod) OutboxHandlerKind.TYPED else OutboxHandlerKind.GENERIC,
            payloadType = (handler as? TypedHandlerMethod)?.paramType?.java?.name,
            beanClass = ReflectionUtils.getTargetClass(handler.bean).name,
            methodName = handler.method.name,
            methodSignature = handler.method.toGenericString(),
            parameterTypes = handler.method.parameterTypes.map { it.name },
        )
}
