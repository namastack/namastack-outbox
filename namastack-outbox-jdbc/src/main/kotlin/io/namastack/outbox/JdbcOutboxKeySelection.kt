package io.namastack.outbox

/**
 * Shared SQL strategies for resolving the earliest incomplete record of each key.
 *
 * Every strategy applies ownership and readiness after checking the global predecessor barrier.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal enum class JdbcOutboxKeySelection {
    /** Ranks incomplete records globally before testing head eligibility. */
    WINDOW,

    /** Tests due candidates for an earlier incomplete record. */
    PREDECESSOR,

    /** Enumerates distinct partition/key groups before resolving their global heads. */
    DISTINCT_HEADS,
}
