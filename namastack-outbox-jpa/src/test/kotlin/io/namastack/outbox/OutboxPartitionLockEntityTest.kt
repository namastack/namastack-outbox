package io.namastack.outbox

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
class OutboxPartitionLockEntityTest {
    @Autowired
    private lateinit var testEntityManager: TestEntityManager

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `sentinel lock can be persisted and retrieved`() {
        val lockEntity = OutboxPartitionLockEntity()
        testEntityManager.persistAndFlush(lockEntity)
        testEntityManager.clear()

        val found = entityManager.find(OutboxPartitionLockEntity::class.java, OutboxPartitionLockEntity.SENTINEL_ID)

        assertThat(found).isNotNull
    }

    @Test
    fun `can acquire pessimistic write lock on sentinel row`() {
        val lockEntity = OutboxPartitionLockEntity()
        testEntityManager.persistAndFlush(lockEntity)
        testEntityManager.clear()

        val lockedEntity =
            entityManager.find(
                OutboxPartitionLockEntity::class.java,
                OutboxPartitionLockEntity.SENTINEL_ID,
                LockModeType.PESSIMISTIC_WRITE,
            )

        assertThat(lockedEntity).isNotNull
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
