package io.namastack.demo

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class MessageController(
    private val messageService: MessageService,
    meterRegistry: MeterRegistry,
) {
    private val pingCounter = meterRegistry.counter("demo.pings")

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun enqueue(
        @RequestParam(defaultValue = "10") count: Int,
        @RequestParam(defaultValue = "false") fail: Boolean,
    ): Map<String, Any> {
        if (count !in 1..1000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and 1000")
        }
        messageService.enqueue(count, fail)

        return mapOf("scheduled" to count, "fail" to fail)
    }

    /** Remains usable during a database outage and increments a database-independent counter. */
    @GetMapping("/ping")
    fun ping(): String {
        pingCounter.increment()
        return "pong"
    }
}
