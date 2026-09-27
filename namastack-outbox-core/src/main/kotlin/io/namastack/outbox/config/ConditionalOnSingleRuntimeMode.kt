package io.namastack.outbox.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Enables auto-configuration that belongs to the standard single-runtime mode.
 *
 * A missing `namastack.outbox.mode` property selects the single-runtime mode for backward
 * compatibility.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
annotation class ConditionalOnSingleRuntimeMode
