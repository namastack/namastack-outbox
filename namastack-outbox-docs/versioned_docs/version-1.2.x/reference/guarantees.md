---
title: Reliability Guarantees
description: What the library guarantees and what it does not.
sidebar_position: 13
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Reliability Guarantees

<Tabs>
<TabItem value="guarantees" label="What is Guaranteed">

- **At-least-once delivery**: Records will be processed at least once
- **Ordering per key**: Records with the same key are processed in order
- **Failure recovery**: System failures don't result in lost records
- **Horizontal scalability**: Multiple instances process different partitions
- **Consistency**: Database transactions ensure data integrity
- **Automatic retry**: Failed records are automatically retried
- **Automatic rebalancing**: Partitions redistribute on topology changes
- **Linear scaling**: Performance scales with instance count

</TabItem>
<TabItem value="not-guaranteed" label="What is NOT Guaranteed">

- **Exactly-once delivery**: Records may be processed multiple times (handlers should be idempotent)
- **Global ordering**: No ordering guarantee across different keys
- **Real-time processing**: Records are processed asynchronously with configurable delays

</TabItem>
</Tabs>

:::tip Next Steps
Ready to get started? Check out the [Quick Start Guide](../quickstart) to integrate Namastack Outbox for Spring Boot into your application.
:::
