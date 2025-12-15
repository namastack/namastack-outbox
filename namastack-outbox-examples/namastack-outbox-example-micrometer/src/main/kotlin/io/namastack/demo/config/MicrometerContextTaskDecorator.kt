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

        val stackTrace = Thread.currentThread().stackTrace
        val callerClassName = stackTrace
            .firstOrNull {
                it.className != this::class.java.name
                    && !it.className.startsWith("java.lang.Thread")
                    && !it.className.startsWith("org.springframework")
            }
            ?.className

        newSpan.name("AsyncTask#$callerClassName")
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
