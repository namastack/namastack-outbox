---
hide:
  - navigation
  - toc
---

<figure markdown="span">
    ![Namastack Logo](assets/spring_outbox_light.svg#only-light){ width="80%" }
    ![Namastack Logo](assets/spring_outbox_dark.svg#only-dark){ width="80%" }
    [Getting Started :octicons-rocket-24:](quickstart.md){ .md-button style="margin: 2rem;" }
</figure>

# Welcome!

## Why should you use spring-outbox?

<div class="grid cards" markdown>

-   :material-shield-check:{ .lg .middle .yellow-icon } __Reliable Event Delivery__

    ---

    Ensure each event is delivered **at least once**, even if failures occur. Retries and transactional storage guarantee no events are lost.

    [:octicons-arrow-right-24: Learn more](#)

-   :material-database-sync:{ .lg .middle .yellow-icon } __Consistent State and Messages__

    ---

    Keep your **database and message broker in sync** by storing events within the same transaction as your business data.

    [:octicons-arrow-right-24: Learn more](#)

-   :material-rocket-launch:{ .lg .middle .yellow-icon } __Asynchronous Scalability__

    ---

    Offload message publishing to a background process, allowing your main workflow to remain **fast and responsive**.

    [:octicons-arrow-right-24: Learn more](#)

-   :material-cog-outline:{ .lg .middle .yellow-icon } __Simple and Transparent__

    ---

    Designed for **simplicity** — no complex infrastructure required. Just use your database and a reliable message dispatcher.

    [:octicons-arrow-right-24: Learn more](#)

-   :material-chart-bar:{ .lg .middle .yellow-icon } __Observability and Monitoring__

    ---

    Track the status of your outbox events and failures with **metrics, logs, and dashboards**, making it easier to debug and operate reliably.

    [:octicons-arrow-right-24: Learn more](#)

-   :material-lock:{ .lg .middle .yellow-icon } __Transactional Safety__

    ---

    Outbox ensures your business operations are **atomic** — either both the database update and message dispatch succeed, or neither does.

    [:octicons-arrow-right-24: Learn more](#)

</div>

## How It Works / Architecture Overview

![Namastack Logo](assets/diagram_light.svg#only-light){ width="60%", align=right }
![Namastack Logo](assets/diagram_dark.svg#only-dark){ width="60%", align=right}

**spring-outbox** solves the dual-write problem with database-backed reliability. Your component
writes to both the entity table and outbox table within a single ACID transaction — guaranteeing
atomicity between your domain state and message log.

**No more inconsistent state when transactions fail halfway**.

The outbox scheduler polls for unprocessed records and hands them to your implementation of an
outbox processor, which publishes to your message broker with **automatic retry logic**
(exponential backoff, jittered delays, configurable policies). **Distributed locking** ensures only
one instance processes events per aggregate, eliminating race conditions while enabling **horizontal
scaling**. **Event ordering per aggregate is guaranteed** — critical for state machines and
dependent operations.

**At-least-once delivery. Per-aggregate ordering. Zero data loss.**  

Built on Spring Boot and JPA, with Micrometer metrics, and production-grade retry mechanisms. 
Deploy with confidence across multiple instances — **spring outbox** handles **coordination**, 
**failure recovery**, and **thundering herd prevention** automatically.

**Transactional guarantees meet message-driven architecture.**
