package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class OutboxCompatibilityExceptionTest {
    @ParameterizedTest
    @CsvSource(
        value = [
            "order:42|order:42|default",
            "~ns.pg1~billing:order:42|order:42|billing",
            "~ns.pg2~billing:order:42|~ns.pg2~billing:order:42|default",
        ],
        delimiter = '|',
    )
    fun `missing handler diagnostics distinguish business identity from storage identity`(
        storedKey: String,
        businessKey: String,
        processingGroup: String,
    ) {
        val exception = OutboxHandlerNotFoundException("record-id", storedKey, "missing-handler")

        assertThat(exception.recordKey).isEqualTo(storedKey)
        assertThat(exception.businessKey).isEqualTo(businessKey)
        assertThat(exception.processingGroup).isEqualTo(processingGroup)
        assertThat(exception.message).contains(
            "No handler with id missing-handler",
            "record-id",
            "businessKey=$businessKey",
            "processingGroup=$processingGroup",
            "storageKey=$storedKey",
        )
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "order:42|order:42|default",
            "~ns.pg1~billing:order:42|order:42|billing",
            "~ns.pg2~billing:order:42|~ns.pg2~billing:order:42|default",
        ],
        delimiter = '|',
    )
    fun `missing payload diagnostics expose business identity without materializing a record`(
        storedKey: String,
        businessKey: String,
        processingGroup: String,
    ) {
        val cause = ClassNotFoundException("example.MissingPayload")
        val exception =
            OutboxPayloadTypeNotFoundException("record-id", storedKey, "example.MissingPayload", "handler", cause)

        assertThat(exception.recordKey).isEqualTo(storedKey)
        assertThat(exception.businessKey).isEqualTo(businessKey)
        assertThat(exception.processingGroup).isEqualTo(processingGroup)
        assertThat(exception.cause).isSameAs(cause)
        assertThat(exception.message).contains(
            "example.MissingPayload",
            "record-id",
            "businessKey=$businessKey",
            "processingGroup=$processingGroup",
            "storageKey=$storedKey",
        )
    }
}
