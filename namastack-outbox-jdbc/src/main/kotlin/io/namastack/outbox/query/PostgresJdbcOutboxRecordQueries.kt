package io.namastack.outbox.query

import io.namastack.outbox.toSingleLine

/**
 * PostgreSQL statements with an explicit boundary around incomplete records of each key.
 *
 * The key and completion predicates remain inside each probe, so a partial key/boolean index
 * can serve it without retaining completed history. The WITH TIES boundary keeps status/retry predicates
 * outside that boundary. Both modes retain every incomplete predecessor before checking their
 * original eligibility rules; strict mode therefore keeps failed records and future retries as
 * barriers. The final creation/id ordering is unchanged and payload mapping remains deferred.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class PostgresJdbcOutboxRecordQueries(
    tableName: String,
) : DefaultJdbcOutboxRecordQueries(
        tableName,
        { "LIMIT :$it" },
        predecessorSource =
            """
            (
                SELECT key_records.record_key, NULL AS completed_at, key_records.created_at, key_records.id,
                       key_records.status, key_records.next_retry_at
                FROM $tableName key_records
                WHERE key_records.record_key = o.record_key
                  AND key_records.completed_at IS NULL
                ORDER BY (key_records.completed_at IS NULL) DESC
                FETCH FIRST 1 ROW WITH TIES
            ) earlier
            """.toSingleLine(),
    ) {
    override val findByKeysAndStatus: String =
        """
        SELECT o.*
        FROM (
          SELECT t.k
          FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k)
          ORDER BY t.k
        ) k
        CROSS JOIN LATERAL (
          SELECT r.*
          FROM (
            SELECT key_records.*
            FROM $tableName key_records
            WHERE key_records.record_key = k.k
              AND key_records.completed_at IS NULL
            ORDER BY (key_records.completed_at IS NULL) DESC
            FETCH FIRST 1 ROW WITH TIES
          ) r
          WHERE r.status = :status
          ORDER BY r.created_at, r.id
        ) o
        """.toSingleLine()
}
