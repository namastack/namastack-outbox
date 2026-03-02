---
title: Monitoring
description: Built-in metrics with Micrometer and Spring Boot Actuator integration.
sidebar_position: 9
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

# Monitoring & Observability

## Metrics Module

The `namastack-outbox-metrics` module provides automatic integration with Spring Boot Actuator and Micrometer:

<Tabs>
<TabItem value="gradle" label="Gradle">

<VersionedCode language="kotlin" template= {`dependencies {
      implementation("io.namastack:namastack-outbox-starter-jpa:{{versionLabel}}")
      implementation("io.namastack:namastack-outbox-metrics:{{versionLabel}}")
      // For Prometheus endpoint (optional)
      implementation("io.micrometer:micrometer-registry-prometheus")
}`} />

</TabItem>
<TabItem value="maven" label="Maven">

<VersionedCode language="xml" template= {`<dependency>
      <groupId>io.namastack</groupId>
      <artifactId>namastack-outbox-metrics</artifactId>
      <version>{{versionLabel}}</version>
</dependency>`} />

</TabItem>
</Tabs>

---

## Built-in Metrics

| Metric                                    | Description                              | Tags                            |
|-------------------------------------------|------------------------------------------|---------------------------------|
| `outbox.records.count`                    | Number of outbox records                 | `status=new\|failed\|completed` |
| `outbox.partitions.assigned.count`        | Partitions assigned to this instance     | -                               |
| `outbox.partitions.pending.records.total` | Total pending records across partitions  | -                               |
| `outbox.partitions.pending.records.max`   | Maximum pending records in any partition | -                               |
| `outbox.cluster.instances.total`          | Total active instances in cluster        | -                               |

:::info Endpoints

- `/actuator/metrics/outbox.records.count`
- `/actuator/metrics/outbox.partitions.assigned.count`
- `/actuator/prometheus` (if Prometheus enabled)

:::

---

## Programmatic Monitoring

<Tabs>
<TabItem value="kotlin" label="Kotlin">

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

</TabItem>
<TabItem value="java" label="Java">

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

</TabItem>
</Tabs>
