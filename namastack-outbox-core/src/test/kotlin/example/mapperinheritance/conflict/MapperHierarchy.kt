package example.mapperinheritance.conflict

import io.namastack.outbox.mapping.EventMapper

data class Source(
    val id: String,
)

open class ParentMapper : EventMapper<Source, String> {
    override fun map(event: Source): String = event.id
}

class ChildMapper : ParentMapper()
