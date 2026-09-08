package io.namastack.outbox.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Enables a bean or configuration only for the default single-runtime mode.
 *
 * A missing mode property preserves the OSS single-runtime default.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
annotation class ConditionalOnSingleRuntimeMode
