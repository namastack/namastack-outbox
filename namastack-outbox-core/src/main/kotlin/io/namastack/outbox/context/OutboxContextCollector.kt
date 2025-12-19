package io.namastack.outbox.context

import org.slf4j.LoggerFactory

class OutboxContextCollector(
    private val providers: List<OutboxContextProvider>,
) {
    private val log = LoggerFactory.getLogger(OutboxContextCollector::class.java)

    fun collectContext(): Map<String, String> =
        providers
            .flatMap { provider ->
                try {
                    provider.provide().entries
                } catch (ex: Exception) {
                    log.warn("Context provider {} failed: {}", provider::class.simpleName, ex.message, ex)
                    emptyList()
                }
            }.associate { it.key to it.value }
}
