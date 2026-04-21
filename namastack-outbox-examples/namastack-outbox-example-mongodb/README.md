# Namastack Outbox - Custom Collection Prefix Example (MongoDB)

This example demonstrates how to use **custom collection prefixes** with the Namastack Outbox MongoDB module.

## What This Example Shows

- Configuring a custom collection prefix (`app_`) for all outbox collections
- All outbox collections are automatically prefixed in MongoDB

## Key Configuration

```yaml
namastack:
  outbox:
    mongodb:
      collection-prefix: app_
```

This configuration results in collection names like:
- `app_outbox_records`
- `app_outbox_instances`
- `app_outbox_partition_assignments`

## Prerequisites

- A running MongoDB instance on `localhost:27017`

You can start one with Docker:

```bash
docker run -d --name mongodb -p 27017:27017 mongo:8
```

## Running the Example

```bash
./gradlew :namastack-outbox-example-collection-prefix-mongodb:bootRun
```

Or from the examples directory:

```bash
cd namastack-outbox-examples/namastack-outbox-example-collection-prefix-mongodb
./gradlew bootRun
```

## When to Use Custom Collection Prefixes

Use custom collection prefixes when:
- You need to avoid collection name collisions with existing collections
- Multiple applications share the same MongoDB database
- Your organization has naming conventions for database objects
- You want to clearly identify outbox-related collections
