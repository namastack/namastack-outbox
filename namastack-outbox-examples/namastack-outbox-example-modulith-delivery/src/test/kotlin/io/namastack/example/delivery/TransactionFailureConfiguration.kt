package io.namastack.example.delivery

import io.namastack.example.delivery.billing.internal.InvoiceHandler
import io.namastack.example.delivery.orders.OrderPlaced
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.StaticMethodMatcherPointcut
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.DefaultTransactionStatus
import java.io.IOException
import java.lang.reflect.Method
import java.util.UUID
import javax.sql.DataSource

/** Faults are applied inside the real handler transaction or at the transaction manager's commit boundary. */
@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement(order = 0)
class TransactionFailureConfiguration {
    @Bean
    fun failures() = DeliveryFailures()

    @Bean
    fun transactionManager(
        dataSource: DataSource,
        failures: DeliveryFailures,
    ) = FailingCommitTransactionManager(dataSource, failures)

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun handlerFailureAdvisor(failures: DeliveryFailures): DefaultPointcutAdvisor {
        val pointcut =
            object : StaticMethodMatcherPointcut() {
                override fun matches(
                    method: Method,
                    targetClass: Class<*>,
                ): Boolean = InvoiceHandler::class.java.isAssignableFrom(targetClass) && method.name == "issueInvoice"
            }
        return DefaultPointcutAdvisor(
            pointcut,
            MethodInterceptor { invocation ->
                // The handler has already saved the invoice and published its follow-up event.
                val result = invocation.proceed()
                val event = invocation.arguments.first() as OrderPlaced
                if (event.orderId == failures.orderId) {
                    when (failures.point) {
                        FailurePoint.HANDLER_RUNTIME, FailurePoint.RETRY_COMMIT ->
                            throw IllegalStateException("Simulated handler failure after publishing InvoiceIssued")

                        FailurePoint.HANDLER_CHECKED ->
                            throw IOException("Simulated checked handler failure after publishing InvoiceIssued")

                        FailurePoint.HANDLER_COMMIT -> failures.failNextCommit()

                        FailurePoint.HANDLER_COMMIT_ACK_LOST -> failures.failNextCommit(afterCommit = true)

                        else -> Unit
                    }
                }
                result
            },
        ).apply { order = 1 }
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun recordFailureAdvisor(failures: DeliveryFailures): DefaultPointcutAdvisor {
        val pointcut =
            object : StaticMethodMatcherPointcut() {
                override fun matches(
                    method: Method,
                    targetClass: Class<*>,
                ): Boolean = OutboxRecordRepository::class.java.isAssignableFrom(targetClass) && method.name == "save"
            }
        return DefaultPointcutAdvisor(
            pointcut,
            MethodInterceptor { invocation ->
                val record = invocation.arguments.first() as OutboxRecord<*>
                if (record.handlerId == "billing.issue-invoice" && record.key.endsWith("order:${failures.orderId}")) {
                    val completing = record.status == OutboxRecordStatus.COMPLETED
                    val retrying = record.status == OutboxRecordStatus.NEW && record.failureCount > 0
                    when {
                        completing && failures.point == FailurePoint.COMPLETION_COMMIT -> failures.failNextCommit()

                        completing && failures.point == FailurePoint.COMPLETION_COMMIT_ACK_LOST ->
                            failures.failNextCommit(afterCommit = true)

                        retrying && failures.point == FailurePoint.RETRY_COMMIT -> failures.failNextCommit()
                    }
                }
                invocation.proceed()
            },
        )
    }
}

enum class FailurePoint {
    NONE,
    HANDLER_RUNTIME,
    HANDLER_CHECKED,
    HANDLER_COMMIT,
    HANDLER_COMMIT_ACK_LOST,
    COMPLETION_COMMIT,
    COMPLETION_COMMIT_ACK_LOST,
    RETRY_COMMIT,
}

class DeliveryFailures {
    @Volatile var point = FailurePoint.NONE

    @Volatile var orderId: UUID? = null
    private val commitFailure = ThreadLocal<CommitFailure?>()

    fun failNextCommit(afterCommit: Boolean = false) {
        commitFailure.set(if (afterCommit) CommitFailure.AFTER_COMMIT else CommitFailure.BEFORE_COMMIT)
    }

    fun takeCommitFailure(): CommitFailure? {
        val fail = commitFailure.get()
        commitFailure.remove()
        return fail
    }

    fun reset() {
        point = FailurePoint.NONE
        orderId = null
        commitFailure.remove()
    }
}

enum class CommitFailure {
    BEFORE_COMMIT,
    AFTER_COMMIT,
}

/** Simulates both a failed commit with rollback and a committed transaction whose acknowledgement is lost. */
class FailingCommitTransactionManager(
    dataSource: DataSource,
    private val failures: DeliveryFailures,
) : JdbcTransactionManager(dataSource) {
    init {
        isRollbackOnCommitFailure = true
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        when (failures.takeCommitFailure()) {
            CommitFailure.BEFORE_COMMIT -> throw TransactionSystemException("Simulated database commit failure")

            CommitFailure.AFTER_COMMIT -> {
                super.doCommit(status)
                throw TransactionSystemException("Simulated lost commit acknowledgement")
            }

            null -> super.doCommit(status)
        }
    }
}
