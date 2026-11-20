package io.namastack.outbox.multicaster

import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.handler.ReflectionUtils
import io.namastack.outbox.mapping.EventMapper
import org.springframework.core.annotation.MergedAnnotations

/**
 * Registers mapper beans and selects one mapping for a published event payload.
 *
 * Generic source types are resolved using the same proxy-aware reflection utility as typed
 * outbox handlers. An exact match wins; otherwise only the unique most-specific assignable type
 * is selected. Unlike handler fan-out, incomparable mapper candidates are rejected.
 *
 * @param mappers Initialized Spring mapper beans to register
 * @throws IllegalArgumentException if a source declares [OutboxEvent] or is registered more than once
 * @throws IllegalStateException if a bean's generic source type cannot be resolved
 * @author Roland Beisel
 * @since 2.1.0
 */
internal class EventMapperRegistry(
    mappers: List<EventMapper<*, *>>,
) {
    /** Validated mapping functions indexed by their resolved generic source payload class. */
    private val mappings = mutableMapOf<Class<*>, (Any) -> Any>()

    init {
        mappers.forEach { register(it) }
    }

    /**
     * Maps a payload through its exact or unique most-specific registered source type.
     *
     * The runtime source type is validated before invoking a fallback mapper, preventing an
     * annotated subclass from bypassing the prohibition on mapping an outbox source payload.
     *
     * @param event Published source payload
     * @return The mapped payload, or `null` when no mapper applies
     * @throws IllegalStateException if incomparable mapper source types match the payload
     * @throws IllegalArgumentException if a matched source declares [OutboxEvent] or mapping returns null
     */
    fun mapIfSupported(event: Any): Any? {
        val eventType = event.javaClass
        mappings[eventType]?.let { return it(event) }

        val candidates = mappings.keys.filter { it.isAssignableFrom(eventType) }
        val mostSpecific =
            candidates.filter { candidate ->
                candidates.none { other -> candidate != other && candidate.isAssignableFrom(other) }
            }
        check(mostSpecific.size <= 1) {
            "Ambiguous EventMappers for ${eventType.name}: " + mostSpecific.map { it.name }.sorted().joinToString()
        }
        val sourceType = mostSpecific.singleOrNull() ?: return null

        requireUnannotatedSource(eventType)

        return mappings.getValue(sourceType)(event)
    }

    /**
     * Resolves and validates one mapper's generic source type before installing its mapping function.
     *
     * The class cast is confined to registration; subsequent payload casts use the source type
     * resolved from the bean's interface hierarchy. Invocations retain the original bean so that
     * Spring AOP advice remains active.
     *
     * @param mapper Mapper bean whose source type and mapping function are registered
     * @throws IllegalArgumentException if the source is annotated or already registered
     * @throws IllegalStateException if the generic source type cannot be resolved
     */
    private fun <S : Any, T : Any> register(mapper: EventMapper<S, T>) {
        @Suppress("UNCHECKED_CAST")
        val sourceType = ReflectionUtils.resolveInterfacePayloadType(mapper, EventMapper::class.java) as Class<S>

        requireUnannotatedSource(sourceType)
        require(sourceType !in mappings) {
            "Multiple EventMappers registered for: ${sourceType.name}"
        }

        mappings[sourceType] = { event ->
            requireNotNull(mapper.map(sourceType.cast(event))) {
                "EventMapper must return a non-null payload: ${sourceType.name}"
            }
        }
    }

    /**
     * Rejects directly declared or composed outbox annotations on a source payload class.
     *
     * Lookup matches [OutboxEventResolver] semantics and does not inherit superclass annotations.
     *
     * @param sourceType Registered or runtime source class to validate
     * @throws IllegalArgumentException if the class declares [OutboxEvent]
     */
    private fun requireUnannotatedSource(sourceType: Class<*>) {
        val annotations = MergedAnnotations.from(sourceType, MergedAnnotations.SearchStrategy.DIRECT)
        require(!annotations.isPresent(OutboxEvent::class.java)) {
            "EventMapper source must not declare @OutboxEvent: ${sourceType.name}"
        }
    }
}
