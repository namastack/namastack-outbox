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

**Configuration:**

- `namastack.outbox.polling.trigger=fixed` (default)
- `namastack.outbox.polling.fixed.interval` (default: `2000` ms)  
  The interval in milliseconds between polling cycles.

</TabItem>
<TabItem value="adaptive" label="Adaptive Polling (since v1.1.0)">

**How it works:**

- Dynamically adjusts the polling interval based on workload:
  - If fewer than 25% of the batch size is processed, the delay doubles (up to max).
  - If a full batch is processed, the delay halves (down to min).
  - Otherwise, the delay remains unchanged.

**Use case:**

- Reduces unnecessary database queries during idle periods, while maintaining responsiveness under load.

**Configuration:**

- `namastack.outbox.polling.trigger=adaptive`
- `namastack.outbox.polling.adaptive.min-interval` (default: `1000` ms)  
  Minimum interval between polling cycles.
- `namastack.outbox.polling.adaptive.max-interval` (default: `8000` ms)  
  Maximum interval between polling cycles.

</TabItem>
</Tabs>
