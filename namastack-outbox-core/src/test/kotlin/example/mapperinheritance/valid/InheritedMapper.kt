package example.mapperinheritance.valid

import io.namastack.outbox.mapping.EventMapper

data class Source(
    val id: String,
)

abstract class GenericMapper<S : Any> : EventMapper<S, String> {
    final override fun map(event: S): String = event.toString()
}

class InheritedMapper : GenericMapper<Source>()
