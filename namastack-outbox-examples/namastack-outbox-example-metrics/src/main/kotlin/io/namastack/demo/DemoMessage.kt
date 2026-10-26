package io.namastack.demo

/** A message that either succeeds or deliberately fails to demonstrate retry and failure metrics. */
data class DemoMessage(
    val id: String,
    val fail: Boolean,
)
