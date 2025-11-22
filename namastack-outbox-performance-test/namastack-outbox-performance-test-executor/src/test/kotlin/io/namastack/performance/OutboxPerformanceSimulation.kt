package io.namastack.performance

import io.gatling.javaapi.core.CoreDsl
import io.gatling.javaapi.core.CoreDsl.constantUsersPerSec
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl
import java.time.Duration
import java.util.UUID

class OutboxPerformanceSimulation : Simulation() {
    val httpProtocol =
        HttpDsl.http
            .disableWarmUp()
            .shareConnections()
            .baseUrl("http://localhost:8082")
            .header("Content-Type", "application/json")

    // Generate UUIDs
    private val uuids = (1..30).map { mapOf("uuid" to UUID.randomUUID().toString()) }

    // Create a random feeder from them
    private val uuidFeeder = CoreDsl.listFeeder(uuids).random()

    val scn =
        CoreDsl
            .scenario("POST Scenario")
            .feed(uuidFeeder)
            .exec(
                HttpDsl
                    .http("POST Request")
                    .post("/outbox/record/#{uuid}")
                    .check(HttpDsl.status().`is`(200)),
            )

    init {
        setUp(
            scn.injectOpen(
                constantUsersPerSec(200.0).during(Duration.ofSeconds(120)),
            ),
        ).protocols(httpProtocol)
    }
}
