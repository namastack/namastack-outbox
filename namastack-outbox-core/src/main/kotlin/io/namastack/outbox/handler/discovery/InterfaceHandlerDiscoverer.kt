package io.namastack.outbox.handler.discovery

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.ReflectionUtils
import org.springframework.util.ClassUtils

/**
 * Discovers primary methods implemented through the public handler interfaces.
 *
 * Interface configuration, including stable IDs, aliases, and generic scheduling support, is
 * copied into the resulting candidates for later assembly.
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal object InterfaceHandlerDiscoverer {
    /**
     * Discovers primary declarations implemented through the handler interfaces.
     *
     * The concrete implementation method and generic payload type are resolved against the bean's
     * user class. Stable IDs, aliases, and scheduling support are retained on the candidates.
     *
     * @param bean Bean to inspect for typed and generic handler interfaces
     * @param beanName Spring name of the inspected bean
     * @return Unvalidated interface-based primary declarations
     */
    fun discover(
        bean: Any,
        beanName: String,
    ): List<HandlerCandidate> {
        val lambdaId = beanName.takeIf { ClassUtils.isLambdaClass(ReflectionUtils.getTargetClass(bean)) }
        return buildList {
            if (bean is OutboxTypedHandler<*>) {
                val payloadType = ReflectionUtils.resolveInterfacePayloadType(bean, OutboxTypedHandler::class.java)
                val identity = bean.getTypedHandlerIdentity()
                add(
                    HandlerCandidate(
                        beanName = beanName,
                        bean = bean,
                        method =
                            ReflectionUtils.findInterfaceMethod(
                                bean = bean,
                                handlerInterface = OutboxTypedHandler::class.java,
                                methodName = "handle",
                                contextType = OutboxRecordMetadata::class.java,
                            ),
                        payloadType = payloadType,
                        source = HandlerSource.TYPED_INTERFACE,
                        configuredId = identity?.id,
                        configuredAliases = identity?.aliases.orEmpty(),
                        lambdaBeanNameId = lambdaId,
                        supportsPayload = { _, _ -> true },
                    ),
                )
            }
            if (bean is OutboxHandler) {
                val identity = bean.getGenericHandlerIdentity()
                add(
                    HandlerCandidate(
                        beanName = beanName,
                        bean = bean,
                        method =
                            ReflectionUtils.findInterfaceMethod(
                                bean = bean,
                                handlerInterface = OutboxHandler::class.java,
                                methodName = "handle",
                                contextType = OutboxRecordMetadata::class.java,
                            ),
                        payloadType = Any::class.java,
                        source = HandlerSource.GENERIC_INTERFACE,
                        configuredId = identity?.id,
                        configuredAliases = identity?.aliases.orEmpty(),
                        lambdaBeanNameId = lambdaId,
                        supportsPayload = bean::supports,
                    ),
                )
            }
        }
    }
}
