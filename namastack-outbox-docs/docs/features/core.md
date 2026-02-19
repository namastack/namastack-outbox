# Core Features

## Transactional Outbox Pattern

!!! success "Zero Message Loss"
    The library ensures that domain records are **never lost** by storing them in the same database transaction as your business data. This guarantees consistency between your domain state and persisted records.

### Benefits

- **ACID Compliance**: Records are saved atomically with business data
- **Consistency Guarantee**: No partial updates or lost records
- **Failure Recovery**: System crashes don't result in data loss
- **Reliable Processing**: Records are processed with automatic retry logic

### How it Works

```mermaid
sequenceDiagram
    participant A as Application
    participant DB as Entity Table
    participant O as Outbox Table
    participant S as Scheduler
    participant P as Handler
    
    A->>DB: Begin Transaction
    A->>DB: Save Business Data
    A->>O: Save Record
    DB->>A: Commit Transaction
    S->>O: Poll Records
    S->>P: Invoke Handler
    P->>P: Process Record
    S->>O: Mark Complete
```

---

## Record Ordering

!!! note "Guaranteed Processing Order"
    Records with the same key are **always processed in creation order**, ensuring business logic consistency and preventing race conditions.

**Key Benefits:**

- :material-arrow-right: **Aggregate Consistency**: Records with the same key maintain order
- :material-arrow-right: **Business Logic Safety**: Dependent records process in correct sequence  
- :material-arrow-right: **Parallel Processing**: Different keys process independently
- :material-arrow-right: **Scalable Design**: No global ordering bottlenecks

### Controlling Failure Behavior

Control how the scheduler handles failures within a key sequence:

!!! example "Stop on First Failure Configuration"

    === "Enabled (Default)"
        ```yaml
        namastack:
          outbox:
            processing:
              stop-on-first-failure: true
        ```
        
        **Behavior:**
        - When one record fails, processing stops for remaining records with the same key
        - Maintains strict ordering within key sequences
        - Prevents cascading issues from dependent records
        - **Recommended**: When records within a key have dependencies

    === "Disabled"
        ```yaml
        namastack:
          outbox:
            processing:
              stop-on-first-failure: false
        ```
        
        **Behavior:**
        - Failed records don't block independent records with the same key
        - Maximizes throughput for independent records
        - **Recommended**: When records within a key are independent

**Behavior Comparison:**

| Configuration    | Record 1                 | Record 2               | Record 3                 | Result                           |
|------------------|--------------------------|------------------------|--------------------------|----------------------------------|
| `true` (default) | :material-check: Success | :material-close: Fails | :material-pause: Skipped | Record 2 retried, Record 3 waits |
| `false`          | :material-check: Success | :material-close: Fails | :material-check: Success | Record 2 retried independently   |

---

## Hash-based Partitioning

Instead of distributed locking, the library uses **hash-based partitioning** to enable horizontal scaling across multiple instances while maintaining strict record ordering per key. This approach eliminates lock contention and provides better performance.

### How Partitioning Works

```mermaid
graph TB
    A["Key: order-123"] --> H[MurmurHash3]
    H --> P[Partition 42]
    P --> I1[Instance 1]
    
    B["Key: user-456"] --> H2[MurmurHash3]
    H2 --> P2[Partition 128]
    P2 --> I2[Instance 2]
    
    C["Key: order-789"] --> H3[MurmurHash3]
    H3 --> P3[Partition 42]
    P3 --> I1
    
    subgraph "256 Fixed Partitions"
        P
        P2
        P3["...other partitions"]
    end
    
    subgraph "Dynamic Instance Assignment"
        I1["Instance 1: Partitions 0-127"]
        I2["Instance 2: Partitions 128-255"]
    end
```

### Key Benefits

- **Consistent Hashing**: Each key always maps to the same partition using MurmurHash3
- **No Lock Contention**: Eliminates distributed lock overhead and deadlock risks
- **Horizontal Scaling**: Partitions automatically redistribute when instances join/leave
- **Load Balancing**: Even distribution of partitions across all active instances
- **Ordering Guarantee**: Records within the same key process in strict order
- **Better Performance**: No lock acquisition/renewal overhead

### Partition Assignment

256 fixed partitions provide fine-grained load distribution. Partitions are automatically distributed among active instances. Each key always maps to the same partition.

=== "Kotlin"

    ```kotlin
    val partition = PartitionHasher.getPartitionForAggregate("order-123")
    ```

=== "Java"

    ```java
    int partition = PartitionHasher.getPartitionForAggregate("order-123");
    ```

### Instance Coordination

Instances automatically coordinate partition assignments and rebalance when topology changes. Configuration controls how aggressive this coordination is:

```yaml
namastack:
  outbox:
    instance:
      heartbeat-interval-seconds: 5            # How often each instance sends a heartbeat
      stale-instance-timeout-seconds: 30       # When an instance is considered stale and removed
      graceful-shutdown-timeout-seconds: 0     # Optional: propagation window on shutdown (default: 0)
      rebalance-interval: 10000                # How often partitions are recalculated
```

!!! example "Scaling Behavior"

    === "3 Instances with 256 Partitions"
        ```
        Instance 1: Partitions 0-84   (85 partitions)
        Instance 2: Partitions 85-169 (85 partitions) 
        Instance 3: Partitions 170-255 (86 partitions)
        ```

    === "Instance 2 Goes Down"
        ```
        Instance 1: Partitions 0-84, 170-211   (127 partitions)
        Instance 3: Partitions 85-169, 212-255 (129 partitions)
        ```
        
        **🔄 Automatic Rebalancing**: Partitions from failed instances are redistributed

    === "New Instance Joins"
        ```
        Instance 1: Partitions 0-63    (64 partitions)
        Instance 2: Partitions 64-127  (64 partitions)
        Instance 3: Partitions 128-191 (64 partitions)
        Instance 4: Partitions 192-255 (64 partitions)
        ```
        
        **⚖️ Load Balancing**: Partitions are redistributed evenly

