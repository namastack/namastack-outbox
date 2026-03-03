---
title: Polling Strategies
description: Learn about fixed and adaptive polling strategies for outbox processing, including configuration options and use cases.
sidebar_position: 4
---

# Polling Strategies

Namastack Outbox supports two polling strategies for processing outbox records:

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

<Tabs>
<TabItem value="fixed" label="Fixed Polling (Default)">

**How it works:**

- Polls the database at a constant, fixed interval, regardless of workload.

**Use case:**

- Suitable for predictable workloads or when a steady polling rate is desired.

**Configuration Options:**

| Property                                  | Default   | Description                                  |
|-------------------------------------------|-----------|----------------------------------------------|
| `namastack.outbox.polling.trigger`        | `fixed`   | Selects polling strategy                     |
| `namastack.outbox.polling.fixed.interval` | `2000` ms | Interval in milliseconds between poll cycles |
| `namastack.outbox.polling.batch-size`     | `10`      | Max record keys to process per poll          |

</TabItem>
<TabItem value="adaptive" label="Adaptive Polling (since v1.1.0)">

**How it works:**

- Dynamically adjusts the polling interval based on workload:
  - If fewer than 25% of the batch size is processed, the delay doubles (up to max).
  - If a full batch is processed, the delay halves (down to min).
  - Otherwise, the delay remains unchanged.

**Use case:**

- Reduces unnecessary database queries during idle periods, while maintaining responsiveness under load.

**Configuration Options:**

| Property                                         | Default    | Description                             |
|--------------------------------------------------|------------|-----------------------------------------|
| `namastack.outbox.polling.trigger`               | `adaptive` | Selects polling strategy                |
| `namastack.outbox.polling.adaptive.min-interval` | `1000` ms  | Minimum interval between polling cycles |
| `namastack.outbox.polling.adaptive.max-interval` | `8000` ms  | Maximum interval between polling cycles |
| `namastack.outbox.polling.batch-size`            | `10`       | Max record keys to process per poll     |

</TabItem>
</Tabs>
