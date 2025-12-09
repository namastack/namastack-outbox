package io.namastack.demo.config

import io.micrometer.tracing.Tracer
import org.springframework.core.task.TaskDecorator
import org.springframework.stereotype.Component

@Component
class MicrometerContextTaskDecorator(
    private val tracer: Tracer,
) : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        if (tracer.currentSpan() == null) {
            return runnable
        }

        val newSpan = tracer.nextSpan()
        return Runnable {
            tracer.withSpan(newSpan.start()).use { _ ->
                try {
                    runnable.run()
                } finally {
                    newSpan.end()
                }
            }
        }
    }
}
