package benchrepo

import bench.Common
import bench.Opts
import bench.Dialect
import bench.Realisation
import bench.Hasher
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) { println("commands: extract-catalog bench hashcheck"); return }
    val o = Opts(args.drop(1))
    when (args[0]) {
        "extract-catalog" -> CatalogExtractor.run(o)
        "bench" -> RepoBench.run(Common(o), o)
        "hashcheck" -> {
            var bad = 0
            for (k in 0 until 20_000) {
                val key = Realisation.pad(k * 7919 % 2_000_000)
                if (Hasher.partition(key) != io.namastack.outbox.partition.PartitionHasher.getPartitionForRecordKey(key)) bad++
            }
            for (key in listOf("a", "order-1", "order-bench-00000000000000000001", "ü-ключ")) {
                if (Hasher.partition(key) != io.namastack.outbox.partition.PartitionHasher.getPartitionForRecordKey(key)) bad++
            }
            println("hashcheck: $bad mismatches of 20004 keys")
            if (bad > 0) System.exit(1)
        }
        else -> error("unknown command ${args[0]}")
    }
}
