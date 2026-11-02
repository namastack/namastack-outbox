package bench

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) { println("commands: gen prepare bench writecost concurrency churn equiv hashcheck"); return }
    val o = Opts(args.drop(1))
    o.opt("pg-plan-cache-mode")?.let { m ->
        require(m in setOf("auto", "force_generic_plan", "force_custom_plan")) { "bad plan_cache_mode $m" }
        Dialect.pgSessionSql = listOf("SET plan_cache_mode = $m")
    }
    when (args[0]) {
        "gen" -> Loader.createSnapshot(Dialect.of(o.get("dialect")), o.get("scenario"), o.opt("seed")?.toLong() ?: 42L)
        "prepare" -> Bench.prepare(Common(o))
        "bench" -> { val cm = Common(o); if (o.has("prepare")) Bench.prepare(cm); Bench.run(cm, o) }
        "writecost" -> WriteCost.run(Common(o), o)
        "concurrency" -> Concurrency.run(Common(o), o)
        "analyze" -> Analyze.run(Common(o), o)
        "churn" -> Churn.run(Common(o), o)
        "equiv" -> Equiv.main(o)
        "hashcheck" -> { // prints key -> partition for the first keys; compared against PartitionHasher by repo-bench hashcheck
            val n = o.int("n", 1000)
            File(o.get("out")).writeText((0 until n).joinToString("\n") { k -> Realisation.pad(k * 7919 % 2_000_000).let { "$it ${Hasher.partition(it)}" } } + "\n")
        }
        else -> error("unknown command ${args[0]}")
    }
}
