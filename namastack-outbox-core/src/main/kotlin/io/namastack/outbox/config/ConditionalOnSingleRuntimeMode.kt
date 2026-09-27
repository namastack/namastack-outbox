package io.namastack.outbox.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** Enables auto-configuration that belongs to the standard single-runtime mode. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["namastack.outbox.mode"], havingValue = "single", matchIfMissing = true)
annotation class ConditionalOnSingleRuntimeMode
