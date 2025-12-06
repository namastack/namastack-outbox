package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

class JacksonEventOutboxSerializerTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val serializer = JacksonOutboxPayloadSerializer(mapper)

    @Test
    fun `serialize converts object to json string`() {
        val event = TestEvent(id = "123", name = "test")

        val result = serializer.serialize(event)

        assertThat(result).contains("123").contains("test")
    }

    @Test
    fun `serialize handles strings`() {
        val event = "test-event"

        val result = serializer.serialize(event)

        assertThat(result).isEqualTo("\"test-event\"")
    }

    @Test
    fun `serialize handles numbers`() {
        val event = 42

        val result = serializer.serialize(event)

        assertThat(result).isEqualTo("42")
    }

    @Test
    fun `deserialize converts json string back to object`() {
        val json = """{"id":"123","name":"test"}"""

        val result = serializer.deserialize(json, TestEvent::class.java)

        assertThat(result).isInstanceOf(TestEvent::class.java)
        assertThat(result.id).isEqualTo("123")
        assertThat(result.name).isEqualTo("test")
    }

    @Test
    fun `deserialize handles strings`() {
        val json = "\"test-value\""

        val result = serializer.deserialize(json, String::class.java)

        assertThat(result).isEqualTo("test-value")
    }

    @Test
    fun `deserialize handles numbers`() {
        val json = "42"

        val result = serializer.deserialize(json, Int::class.java)

        assertThat(result).isEqualTo(42)
    }

    @Test
    fun `deserialize throws exception on invalid json`() {
        val invalidJson = "invalid json"

        assertThatThrownBy {
            serializer.deserialize(invalidJson, TestEvent::class.java)
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `roundtrip serialization and deserialization`() {
        val originalEvent = TestEvent(id = "456", name = "roundtrip")

        val serialized = serializer.serialize(originalEvent)
        val deserialized = serializer.deserialize(serialized, TestEvent::class.java)

        assertThat(deserialized.id).isEqualTo(originalEvent.id)
        assertThat(deserialized.name).isEqualTo(originalEvent.name)
    }

    private data class TestEvent(
        val id: String,
        val name: String,
    )
}
