---
hide:
  - navigation
  - toc
---

<figure markdown="span">
    ![Namastack Logo](assets/namastack_outbox_light.svg#only-light){ width="80%" }
    ![Namastack Logo](assets/namastack_outbox_dark.svg#only-dark){ width="80%" }
    [Getting Started :octicons-rocket-24:](quickstart.md){ .md-button .quickstart-button }
    [View on GitHub :simple-github:](https://github.com/namastack/spring-outbox){ .md-button .quickstart-button target=blank}
</figure>

# Welcome!

## Why should you use Namastack Outbox for Spring Boot?

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

## :material-head-question: How It Works

![Namastack Logo](assets/diagram_light.svg#only-light){ width="60%", align=right }
![Namastack Logo](assets/diagram_dark.svg#only-dark){ width="60%", align=right}

**Namastack Outbox for Spring Boot** brings bulletproof reliability to your event-driven systems —
combining
transactional integrity with seamless message delivery.

When your **application** writes data, both the **entity table** and the **outbox table**
are updated within a **single ACID transaction**. This guarantees that your domain state and
outgoing
events are always consistent — even if the system crashes mid-operation.

A background **outbox scheduler** polls the database for new outbox records and hands them off to
your **custom outbox processor** — a lightweight interface you implement to publish messages to
your **broker** (e.g. Kafka, RabbitMQ, SNS).

Once messages are successfully delivered, they’re marked as processed.  
This architecture ensures:

- **Zero message loss**, even under failure
- **Strict per-aggregate ordering** for deterministic processing
- **Horizontal scalability** with distributed locking
- **At-least-once delivery** with safe retry policies and observability

With **Namastack Outbox for Spring Boot**, you get the reliability of database transactions — and
the resilience of message-driven design.  
Build confidently, scale safely, and never lose an event again.
