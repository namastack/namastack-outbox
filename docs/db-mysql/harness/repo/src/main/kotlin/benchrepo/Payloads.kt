package io.namastack.outbox.bench

import io.namastack.outbox.OutboxPayloadSerializer

/** Payload types referenced by `record_type` of generated rows. Each holds the raw string of the generated JSON `d` field. */
open class BenchPayload(val d: String)

class OrderCreatedPayload(d: String) : BenchPayload(d)

class OrderShippedPayload(d: String) : BenchPayload(d)

class PaymentCapturedPayload(d: String) : BenchPayload(d)

class InvoiceIssuedPayload(d: String) : BenchPayload(d)

class CustomerUpdatedPayload(d: String) : BenchPayload(d)

/** Minimal serializer: `{"d":"..."}` for payloads, `{}` for contexts. */
class BenchSerializer : OutboxPayloadSerializer {
    override fun serialize(payload: Any): String =
        when (payload) {
            is BenchPayload -> """{"d":"${payload.d}"}"""
            is Map<*, *> -> "{}"
            else -> error("unsupported ${payload.javaClass}")
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T> deserialize(
        serialized: String,
        type: Class<T>,
    ): T {
        if (Map::class.java.isAssignableFrom(type)) return emptyMap<String, String>() as T
        val d = serialized.substringAfter("\"d\":\"").substringBeforeLast("\"}")
        return type.getConstructor(String::class.java).newInstance(d)
    }
}
