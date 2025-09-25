package com.beisel.springoutbox

import com.beisel.springoutbox.application.TestApplication
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(classes = [TestApplication::class])
@ImportAutoConfiguration(
    JpaOutboxAutoConfiguration::class,
    TaskSchedulingAutoConfiguration::class,
)
@Import(PostgresTestContainerConfig::class)
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxJpaTest
