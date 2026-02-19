# Monitoring & Observability

## Metrics Module

The `namastack-outbox-metrics` module provides automatic integration with Spring Boot Actuator and Micrometer:

=== "Gradle"
    ```kotlin
    dependencies {
        implementation("io.namastack:namastack-outbox-starter-jpa:{{ outbox_version }}")
        implementation("io.namastack:namastack-outbox-metrics:{{ outbox_version }}")
        
        // For Prometheus endpoint (optional)
        implementation("io.micrometer:micrometer-registry-prometheus")
    }
    ```

=== "Maven"
    ```xml
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-metrics</artifactId>
        <version>{{ outbox_version }}</version>
    </dependency>
    ```

---

## Built-in Metrics

| Metric                                    | Description                              | Tags                            |
|-------------------------------------------|------------------------------------------|---------------------------------|
| `outbox.records.count`                    | Number of outbox records                 | `status=new\|failed\|completed` |
| `outbox.partitions.assigned.count`        | Partitions assigned to this instance     | -                               |
| `outbox.partitions.pending.records.total` | Total pending records across partitions  | -                               |
| `outbox.partitions.pending.records.max`   | Maximum pending records in any partition | -                               |
| `outbox.cluster.instances.total`          | Total active instances in cluster        | -                               |

**Endpoints:**

- `/actuator/metrics/outbox.records.count`
- `/actuator/metrics/outbox.partitions.assigned.count`
- `/actuator/prometheus` (if Prometheus enabled)

---

## Programmatic Monitoring

=== "Kotlin"

    ```kotlin
    @Service
    class OutboxMonitoringService(
        private val outboxRepository: OutboxRecordRepository,
        private val partitionMetricsProvider: OutboxPartitionMetricsProvider
    ) {
        fun getPendingRecordCount(): Long =
            outboxRepository.countByStatus(OutboxRecordStatus.NEW)
        
        fun getFailedRecordCount(): Long =
            outboxRepository.countByStatus(OutboxRecordStatus.FAILED)
        
        fun getPartitionStats(): PartitionProcessingStats =
            partitionMetricsProvider.getProcessingStats()
        
        fun getClusterStats(): PartitionStats =
            partitionMetricsProvider.getPartitionStats()
    }
    ```

=== "Java"

    ```java
    @Service
    public class OutboxMonitoringService {
        private final OutboxRecordRepository outboxRepository;
        private final OutboxPartitionMetricsProvider partitionMetricsProvider;

        public OutboxMonitoringService(OutboxRecordRepository outboxRepository,
                                       OutboxPartitionMetricsProvider partitionMetricsProvider) {
            this.outboxRepository = outboxRepository;
            this.partitionMetricsProvider = partitionMetricsProvider;
        }
        
        public long getPendingRecordCount() {
            return outboxRepository.countByStatus(OutboxRecordStatus.NEW);
        }
        
        public long getFailedRecordCount() {
            return outboxRepository.countByStatus(OutboxRecordStatus.FAILED);
        }
        
        public PartitionProcessingStats getPartitionStats() {
            return partitionMetricsProvider.getProcessingStats();
        }
        
        public PartitionStats getClusterStats() {
            return partitionMetricsProvider.getPartitionStats();
        }
    }
    ```

