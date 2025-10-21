package io.namastack.outbox

/**
 * Enables Outbox functionality in the application.
 *
 * This annotation triggers the autoconfiguration for the outbox pattern implementation,
 * which helps ensure reliable message publishing and event handling.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class EnableOutbox
