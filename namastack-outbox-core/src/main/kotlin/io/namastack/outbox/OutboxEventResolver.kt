package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxEvent
import org.springframework.context.ApplicationEvent
import org.springframework.context.PayloadApplicationEvent
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Spring application events into the scheduling values defined by [OutboxEvent].
 *
 * Direct and composed annotations use Spring merged-annotation semantics without inheriting an
 * annotation from a payload superclass. Key and context expressions are evaluated against the
 * concrete payload using Spring Expression Language.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
object OutboxEventResolver {
    private val spelParser = SpelExpressionParser()
    private val expressionCache = ConcurrentHashMap<String, Expression>()

    /**
     * Resolves an outbox event for programmatic scheduling.
     *
     * Non-payload and unannotated events return `null`. An annotated event requires an active
     * transaction. An empty key expression produces a generated UUID.
     *
     * @param event Spring application event to inspect
     * @return Resolved scheduling values, or `null` when the event is not an outbox event
     * @throws IllegalStateException if an outbox event is published without an active transaction
     * @throws IllegalArgumentException if a key or context expression cannot be resolved to a string
     */
    fun resolve(event: ApplicationEvent): ResolvedOutboxEvent? {
        if (event !is PayloadApplicationEvent<*>) return null

        val annotation = findAnnotation(event.payload) ?: return null

        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "OutboxEvent requires an active transaction"
        }

        return ResolvedOutboxEvent(
            payload = event.payload,
            key = resolveEventKey(event.payload, annotation) ?: UUID.randomUUID().toString(),
            context = resolveContext(event.payload, annotation) ?: emptyMap(),
        )
    }

    /**
     * Finds the directly declared or composed outbox annotation for a payload.
     *
     * @param payload Concrete event payload
     * @return Synthesized outbox annotation, or `null` when none is declared directly
     */
    private fun findAnnotation(payload: Any): OutboxEvent? =
        MergedAnnotations
            .from(payload.javaClass, MergedAnnotations.SearchStrategy.DIRECT)
            .get(OutboxEvent::class.java)
            .takeIf { it.isPresent }
            ?.synthesize()

    /**
     * Resolves the configured event key expression.
     *
     * @param payload Concrete event payload used as the expression root
     * @param annotation Outbox annotation containing the key expression
     * @return Resolved key, or `null` when the expression is empty
     */
    private fun resolveEventKey(
        payload: Any,
        annotation: OutboxEvent,
    ): String? {
        if (annotation.key.isEmpty()) return null

        return resolveValue(payload, annotation.key)
    }

    /**
     * Resolves the configured event context expressions.
     *
     * @param payload Concrete event payload used as the expression root
     * @param annotation Outbox annotation containing the context entries
     * @return Resolved context, or `null` when no entries are configured
     */
    private fun resolveContext(
        payload: Any,
        annotation: OutboxEvent,
    ): Map<String, String>? {
        if (annotation.context.isEmpty()) return null

        return annotation.context.associate { entry ->
            entry.key to resolveValue(payload, entry.value)
        }
    }

    /**
     * Evaluates one cached Spring Expression Language expression against a payload.
     *
     * @param payload Concrete event payload used as the expression root
     * @param value Expression to evaluate
     * @return Non-null string result
     * @throws IllegalArgumentException if parsing or evaluation fails or returns a non-string value
     */
    private fun resolveValue(
        payload: Any,
        value: String,
    ): String {
        try {
            val expression = expressionCache.computeIfAbsent(value, spelParser::parseExpression)
            val context = StandardEvaluationContext(payload)

            when (val result = expression.getValue(context)) {
                null -> throw IllegalArgumentException("SpEL expression returned null: '$value'")

                !is String -> throw IllegalArgumentException(
                    "SpEL expression must return String, but got ${result::class.simpleName}: '$value'",
                )

                else -> return result
            }
        } catch (exception: Exception) {
            throw IllegalArgumentException(
                "Failed to resolve value from SpEL: '$value'. " +
                    "Valid examples: 'id', '#this.id', '#root.id'",
                exception,
            )
        }
    }
}
