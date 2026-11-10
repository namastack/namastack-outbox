package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** Business data and outbox records must commit and roll back on the same JDBC connection. */
@OutboxIntegrationTest
@Import(JdbcTransactionIntegrationTest.Handler::class)
@TestPropertySource(properties = ["namastack.outbox.polling.fixed.interval=1h"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTransactionIntegrationTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var jdbc: JdbcClient

    @BeforeEach
    fun createBusinessTable() {
        jdbc.sql("CREATE TABLE IF NOT EXISTS transaction_customer (id VARCHAR(255) PRIMARY KEY)").update()
    }

    @Test
    fun `JDBC business data and outbox record commit together`() {
        assertThat(transactionManager).isInstanceOf(JdbcTransactionManager::class.java)
        transactions.executeWithoutResult {
            insertCustomer("committed")
            outbox.schedule(CustomerCreated("committed"), "committed")
        }
        assertThat(customerCount("committed")).isEqualTo(1)
        assertThat(recordCount("committed")).isEqualTo(1)
    }

    @Test
    fun `failure after scheduling rolls back both business data and outbox record`() {
        assertThatThrownBy {
            transactions.executeWithoutResult {
                insertCustomer("rolled-back")
                outbox.schedule(CustomerCreated("rolled-back"), "rolled-back")
                assertThat(customerCount("rolled-back")).isEqualTo(1)
                assertThat(recordCount("rolled-back")).isEqualTo(1)
                error("abort business transaction")
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("abort business transaction")
        assertThat(customerCount("rolled-back")).isZero()
        assertThat(recordCount("rolled-back")).isZero()
    }

    private fun insertCustomer(id: String) {
        jdbc.sql("INSERT INTO transaction_customer (id) VALUES (:id)").param("id", id).update()
    }

    private fun customerCount(id: String): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM transaction_customer WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private fun recordCount(key: String): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM outbox_record WHERE record_key = :key")
            .param("key", key)
            .query(Long::class.java)
            .single()

    data class CustomerCreated(
        val id: String,
    )

    class Handler {
        @OutboxHandler
        fun handle(event: CustomerCreated) = Unit
    }

    @SpringBootApplication
    class TestApplication
}
