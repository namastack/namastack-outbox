# Features Overview

<div class="grid cards" markdown>

- :material-database-check: **Core Features**
    
    ---
    
    Transactional outbox pattern, record ordering, and hash-based partitioning for horizontal scaling.
    
    [:octicons-arrow-right-24: Core Features](core.md)

- :material-calendar-clock: **Record Scheduling**
    
    ---
    
    Schedule records via the Outbox Service API or use Spring's event system with @OutboxEvent.
    
    [:octicons-arrow-right-24: Scheduling](scheduling.md)

- :material-cog-sync: **Processing Chain**
    
    ---
    
    Chain of Responsibility pattern for processing records through multiple stages.
    
    [:octicons-arrow-right-24: Processing](processing.md)

- :material-hand-pointing-right: **Handlers**
    
    ---
    
    Type-safe and generic handlers for processing outbox records, including fallback handlers for graceful degradation.
    
    [:octicons-arrow-right-24: Handlers](handlers.md)


- :material-transit-connection-variant: **Context Propagation**
    
    ---
    
    Preserve trace IDs, tenant info, and other metadata across async boundaries.
    
    [:octicons-arrow-right-24: Context Propagation](context-propagation.md)

- :material-code-json: **Serialization**
    
    ---
    
    Flexible payload serialization with Jackson or custom serializers.
    
    [:octicons-arrow-right-24: Serialization](serialization.md)

- :material-refresh: **Retry Mechanisms**
    
    ---
    
    Sophisticated retry strategies with exponential backoff, jitter, and exception filtering.
    
    [:octicons-arrow-right-24: Retry Mechanisms](retry.md)

- :material-database: **Persistence**
    
    ---
    
    Choose between JPA and JDBC persistence modules.
    
    [:octicons-arrow-right-24: Persistence](persistence.md)

- :material-chart-line: **Monitoring**
    
    ---
    
    Built-in metrics with Micrometer and Spring Boot Actuator integration.
    
    [:octicons-arrow-right-24: Monitoring](monitoring.md)

- :material-cogs: **Configuration**
    
    ---
    
    Complete reference of all configuration options.
    
    [:octicons-arrow-right-24: Configuration](configuration.md)

</div>

**Additional Topics**

- [Virtual Threads Support](virtual-threads.md) - Automatic virtual threads integration for better scalability
- [Database Support](database.md) - Supported databases and schema management
- [Reliability Guarantees](guarantees.md) - What the library guarantees and what it doesn't

---

!!! tip "Getting Started"
    If you're new to Namastack Outbox, start with the [Quick Start Guide](../quickstart.md) to get up and running quickly.

