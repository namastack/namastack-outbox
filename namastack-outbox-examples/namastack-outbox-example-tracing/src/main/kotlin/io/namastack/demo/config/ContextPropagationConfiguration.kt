package io.namastack.demo.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator


@Configuration(proxyBeanMethods = false)
class ContextPropagationConfiguration {
    @Bean
    fun contextPropagatingTaskDecorator(): ContextPropagatingTaskDecorator {
        return ContextPropagatingTaskDecorator()
    }
}


