package io.namastack.outbox

/**
 * Declares support for one [OutboxRuntimeMode].
 *
 * Providers only participate in bootstrap validation. They do not construct, discover, or manage
 * outbox runtimes. Supporting modules expose exactly one provider as a Spring bean for their mode.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
fun interface OutboxRuntimeModeProvider {
    /**
     * Returns the outbox runtime mode supported by this provider.
     *
     * @return Supported runtime mode
     */
    fun getMode(): OutboxRuntimeMode
}
