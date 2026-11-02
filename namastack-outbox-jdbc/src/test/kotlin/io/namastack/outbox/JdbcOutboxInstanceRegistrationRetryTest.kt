package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

class JdbcOutboxInstanceRegistrationRetryTest {
    private val jdbc = mockk<JdbcClient>()
    private val statement = mockk<JdbcClient.StatementSpec>()
    private val transactions = mockk<PlatformTransactionManager>(relaxUnitFun = true)
    private val repository =
        JdbcOutboxInstanceRepository(jdbc, TransactionTemplate(transactions), DefaultJdbcTableNameResolver())
    private val instance =
        OutboxInstance.create(
            "instance",
            "localhost",
            8080,
            OutboxInstanceStatus.ACTIVE,
            Clock.systemUTC(),
        )

    init {
        every { jdbc.sql(any<String>()) } returns statement
        every { statement.param(any<String>(), any()) } returns statement
        every { transactions.getTransaction(any()) } answers { SimpleTransactionStatus() }
    }

    @AfterEach
    fun clearTransactionState() = TransactionSynchronizationManager.clear()

    @Test
    fun `a registration deadlock rolls back before retrying in a new transaction`() {
        every { statement.update() } throws CannotAcquireLockException("deadlock") andThen 0 andThen 1

        assertThat(repository.save(instance)).isSameAs(instance)
        verifyOrder {
            transactions.getTransaction(any())
            transactions.rollback(any())
            transactions.getTransaction(any())
            transactions.commit(any())
        }
    }

    @Test
    fun `registration does not retry inside a caller transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        val failure = CannotAcquireLockException("deadlock")
        every { statement.update() } throws failure

        assertThatThrownBy { repository.save(instance) }.isSameAs(failure)
        verify(exactly = 1) { transactions.getTransaction(any()) }
        verify(exactly = 1) { transactions.rollback(any()) }
    }

    @Test
    fun `registration does not replay a non-lock failure`() {
        val failure = DataIntegrityViolationException("invalid metadata")
        every { statement.update() } throws failure

        assertThatThrownBy { repository.save(instance) }.isSameAs(failure)
        verify(exactly = 1) { transactions.getTransaction(any()) }
    }

    @Test
    fun `a persistent registration lock failure still aborts startup`() {
        val failure = CannotAcquireLockException("persistent lock")
        every { statement.update() } throws failure

        assertThatThrownBy { repository.save(instance) }.isSameAs(failure)
        verify(exactly = 5) { transactions.getTransaction(any()) }
        verify(exactly = 5) { transactions.rollback(any()) }
        verify(exactly = 0) { transactions.commit(any()) }
    }
}
