package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate

class ExecuteNonNullTest {
    private val transactionTemplate = mockk<TransactionTemplate>()

    @Test
    fun `returns result when transaction returns non-null value`() {
        val expectedResult = "test-result"
        every { transactionTemplate.execute<String>(any()) } returns expectedResult

        val result = transactionTemplate.executeNonNull { "test-result" }

        assertThat(result).isEqualTo(expectedResult)
    }

    @Test
    fun `returns complex object when transaction returns non-null object`() {
        data class TestObject(
            val id: String,
            val value: Int,
        )
        val expectedResult = TestObject("test-id", 42)
        every { transactionTemplate.execute<TestObject>(any()) } returns expectedResult

        val result = transactionTemplate.executeNonNull { TestObject("test-id", 42) }

        assertThat(result).isEqualTo(expectedResult)
    }

    @Test
    fun `returns integer when transaction returns non-null integer`() {
        every { transactionTemplate.execute<Int>(any()) } returns 123

        val result = transactionTemplate.executeNonNull { 123 }

        assertThat(result).isEqualTo(123)
    }

    @Test
    fun `returns boolean when transaction returns non-null boolean`() {
        every { transactionTemplate.execute<Boolean>(any()) } returns true

        val result = transactionTemplate.executeNonNull { true }

        assertThat(result).isTrue()
    }

    @Test
    fun `returns long when transaction returns non-null long`() {
        every { transactionTemplate.execute<Long>(any()) } returns 456L

        val result = transactionTemplate.executeNonNull { 456L }

        assertThat(result).isEqualTo(456L)
    }

    @Test
    fun `throws IllegalStateException when transaction returns null`() {
        every { transactionTemplate.execute<String>(any()) } returns null

        assertThatThrownBy {
            transactionTemplate.executeNonNull { "should-not-matter" }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Transaction returned null unexpectedly")
    }

    @Test
    fun `throws IllegalStateException when transaction returns null for any type`() {
        every { transactionTemplate.execute<Any>(any()) } returns null

        assertThatThrownBy {
            transactionTemplate.executeNonNull { Any() }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Transaction returned null unexpectedly")
    }

    @Test
    fun `propagates exceptions from the transaction block`() {
        val expectedException = RuntimeException("Test exception")
        every { transactionTemplate.execute<String>(any()) } throws expectedException

        assertThatThrownBy {
            transactionTemplate.executeNonNull { "test" }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("Test exception")
    }

    @Test
    fun `handles nested calls correctly`() {
        every { transactionTemplate.execute<String>(any()) } returns "nested-result"

        val result =
            transactionTemplate.executeNonNull {
                transactionTemplate.executeNonNull { "nested-result" }
            }

        assertThat(result).isEqualTo("nested-result")
    }

    @Test
    fun `works with different return types in separate calls`() {
        val stringTemplate = mockk<TransactionTemplate>()
        val intTemplate = mockk<TransactionTemplate>()

        every { stringTemplate.execute<String>(any()) } returns "string-result"
        every { intTemplate.execute<Int>(any()) } returns 42

        val stringResult = stringTemplate.executeNonNull { "string-result" }
        val intResult = intTemplate.executeNonNull { 42 }

        assertThat(stringResult).isEqualTo("string-result")
        assertThat(intResult).isEqualTo(42)
    }
}
