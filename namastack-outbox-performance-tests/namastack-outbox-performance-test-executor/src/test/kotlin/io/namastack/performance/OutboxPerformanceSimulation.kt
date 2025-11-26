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

    val idFeeder =
        generateSequence {
            mapOf("uuid" to UUID.randomUUID().toString())
        }.iterator()

    val scn =
        CoreDsl
            .scenario("POST Scenario")
            .feed(idFeeder)
            .exec(
                HttpDsl
                    .http("POST Request")
                    .post("/outbox/record/#{uuid}")
                    .check(HttpDsl.status().`is`(200)),
            )

    init {
        setUp(
            scn.injectOpen(
                constantUsersPerSec(2000.0).during(Duration.ofSeconds(120)),
            ),
        ).protocols(httpProtocol)
    }
}
