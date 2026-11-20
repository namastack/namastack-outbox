package example.eventmapping

import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.annotation.OutboxEvent.OutboxContextEntry
import io.namastack.outbox.mapping.EventMapper
import org.springframework.stereotype.Component

data class DomainEvent(
    val id: String,
)

@OutboxEvent(key = "'mapped:' + #this.id", context = [OutboxContextEntry(key = "origin", value = "'mapper'")])
data class IntegrationEvent(
    val id: String,
)

class Formatter {
    fun format(value: String): String = value.uppercase()
}

// Registered explicitly through a @Bean factory method.
class ConfiguredMapper(
    private val formatter: Formatter,
) : EventMapper<DomainEvent, IntegrationEvent> {
    override fun map(event: DomainEvent): IntegrationEvent = IntegrationEvent(formatter.format(event.id))
}

data class LocalSource(
    val id: String,
)

data class LocalTarget(
    val id: String,
)

@Component
class ComponentMapper : EventMapper<LocalSource, LocalTarget> {
    override fun map(event: LocalSource): LocalTarget = LocalTarget(event.id)
}
