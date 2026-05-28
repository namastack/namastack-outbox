package io.namastack.outbox.event

internal object LogicalNameValidator {
    private val RESERVED_CHARS = setOf('#', ',', '(', ')')

    fun validate(
        name: String,
        context: String,
    ) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "$context: logical name must not be blank" }
        require(!trimmed.any { it.isWhitespace() }) {
            "$context: logical name '$trimmed' must not contain whitespace"
        }
        val bad = trimmed.filter { it in RESERVED_CHARS }
        require(bad.isEmpty()) {
            "$context: logical name '$trimmed' contains reserved characters: $bad"
        }
    }
}
