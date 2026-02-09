package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AnnotationSelectorTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    @Test
    fun `matches returns true for directly annotated class`() {
        val selector = OutboxPayloadSelector.annotation(TestAnnotation::class.java)
        val payload = AnnotatedPayload()

        assertThat(selector.matches(payload, metadata)).isTrue()
    }

    @Test
    fun `matches returns false for non-annotated class`() {
        val selector = OutboxPayloadSelector.annotation(TestAnnotation::class.java)
        val payload = NonAnnotatedPayload()

        assertThat(selector.matches(payload, metadata)).isFalse()
    }

    @Test
    fun `matches returns true for subclass of annotated class`() {
        val selector = OutboxPayloadSelector.annotation(TestAnnotation::class.java)
        val payload = SubclassOfAnnotatedPayload()

        assertThat(selector.matches(payload, metadata)).isTrue()
    }

    @Test
    fun `matches returns true for class with meta-annotation`() {
        val selector = OutboxPayloadSelector.annotation(TestAnnotation::class.java)
        val payload = MetaAnnotatedPayload()

        assertThat(selector.matches(payload, metadata)).isTrue()
    }

    @Test
    fun `matches returns false for different annotation`() {
        val selector = OutboxPayloadSelector.annotation(OtherAnnotation::class.java)
        val payload = AnnotatedPayload()

        assertThat(selector.matches(payload, metadata)).isFalse()
    }

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    annotation class TestAnnotation

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    annotation class OtherAnnotation

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
    @TestAnnotation
    annotation class MetaTestAnnotation

    @TestAnnotation
    open class AnnotatedPayload

    class NonAnnotatedPayload

    class SubclassOfAnnotatedPayload : AnnotatedPayload()

    @MetaTestAnnotation
    class MetaAnnotatedPayload
}
