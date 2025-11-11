package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class JacksonEventOutboxSerializerTest {
    private val mapper = mockk<JsonMapper>()
    private val serializer = JacksonEventOutboxSerializer(mapper)

    @Test
    fun `serialize delegates to mapper writeValueAsString`() {
        val event = Any()
        val expectedJson = "{\"test\":\"value\"}"
        every { mapper.writeValueAsString(event) } returns expectedJson

        val result = serializer.serialize(event)

        assertThat(result).isEqualTo(expectedJson)
        verify { mapper.writeValueAsString(event) }
    }

    @Test
    fun `serialize returns mapper result`() {
        val event = "test-event"
        val json = "\"test-event\""
        every { mapper.writeValueAsString(event) } returns json

        val result = serializer.serialize(event)

        assertThat(result).isEqualTo(json)
    }

    @Test
    fun `serialize throws exception when mapper fails`() {
        val event = Any()
        every { mapper.writeValueAsString(event) } throws RuntimeException("Serialization failed")

        assertThatThrownBy {
            serializer.serialize(event)
        }.isInstanceOf(RuntimeException::class.java).hasMessage("Serialization failed")
    }

    @Test
    fun `deserialize delegates to mapper readValue`() {
        val json = "{\"id\":\"123\"}"
        val targetType = String::class.java
        val expected = "deserialized"
        every { mapper.readValue(json, targetType) } returns expected

        val result = serializer.deserialize(json, targetType)

        assertThat(result).isEqualTo(expected)
        verify { mapper.readValue(json, targetType) }
    }

    @Test
    fun `deserialize returns mapper result`() {
        val json = "[1,2,3]"
        val targetType = List::class.java
        val expected = listOf(1, 2, 3)
        every { mapper.readValue(json, targetType) } returns expected

        val result = serializer.deserialize(json, targetType)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `deserialize throws exception when mapper fails`() {
        val json = "invalid"
        val targetType = String::class.java
        every { mapper.readValue(json, targetType) } throws RuntimeException("Deserialization failed")

        assertThatThrownBy {
            serializer.deserialize(json, targetType)
        }.isInstanceOf(RuntimeException::class.java).hasMessage("Deserialization failed")
    }

    @Test
    fun `deserialize with different types`() {
        val json1 = "{\"name\":\"test\"}"
        val json2 = "[1,2,3]"

        every { mapper.readValue(json1, String::class.java) } returns "result1"
        every { mapper.readValue(json2, List::class.java) } returns listOf(1, 2, 3)

        val result1 = serializer.deserialize(json1, String::class.java)
        val result2 = serializer.deserialize(json2, List::class.java)

        assertThat(result1).isEqualTo("result1")
        assertThat(result2).isEqualTo(listOf(1, 2, 3))
    }
}
