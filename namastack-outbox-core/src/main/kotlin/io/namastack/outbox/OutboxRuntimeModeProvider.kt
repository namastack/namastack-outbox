package io.namastack.outbox

/**
 * Declares support for one [OutboxRuntimeMode].
 *
 * A supporting module exposes one provider as a Spring bean. Providers only participate in
 * bootstrap validation; they do not construct or manage runtimes.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
fun interface OutboxRuntimeModeProvider {
    /**
     * Returns the supported runtime mode.
     *
     * @return Supported runtime mode
     */
    fun getMode(): OutboxRuntimeMode
}
