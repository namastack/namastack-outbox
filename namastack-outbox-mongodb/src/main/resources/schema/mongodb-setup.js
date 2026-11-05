// =============================================================================
// Namastack Outbox - MongoDB Collections & Indexes Setup Script
// =============================================================================
//
// This script creates the required collections and indexes for the Namastack
// Outbox MongoDB module. Use this when `spring.data.mongodb.auto-index-creation`
// is set to `false` or when you want explicit control over index creation
// (recommended for production environments).
//
// Usage:
//   mongosh <connection-uri> schema/mongodb-setup.js
//
// Example:
//   mongosh mongodb://localhost:27017/outbox_example schema/mongodb-setup.js
//
// If using a custom collection prefix, set the OUTBOX_PREFIX variable before
// running:
//   mongosh --eval 'var OUTBOX_PREFIX="myapp_"' mongodb://localhost:27017/mydb schema/mongodb-setup.js
//
// =============================================================================

var prefix = (typeof OUTBOX_PREFIX !== "undefined") ? OUTBOX_PREFIX : "";

var recordsCollection = prefix + "outbox_records";
var instancesCollection = prefix + "outbox_instances";
var assignmentsCollection = prefix + "outbox_partition_assignments";

print("=== Namastack Outbox MongoDB Setup ===");
print("Collection prefix: '" + prefix + "'");
print("");

// -----------------------------------------------------------------------------
// 1. outbox_records
// -----------------------------------------------------------------------------
print("Creating collection: " + recordsCollection);
db.createCollection(recordsCollection);

print("Creating indexes on: " + recordsCollection);

db[recordsCollection].createIndex(
    { status: 1 },
    { name: "status_idx" }
);

db[recordsCollection].createIndex(
    { recordKey: 1, createdAt: 1 },
    { name: "record_key_created_idx" }
);

db[recordsCollection].createIndex(
    { partitionNo: 1, status: 1, nextRetryAt: 1 },
    { name: "partition_status_retry_idx" }
);

db[recordsCollection].createIndex(
    { status: 1, nextRetryAt: 1 },
    { name: "status_retry_idx" }
);

db[recordsCollection].createIndex(
    { recordKey: 1, completedAt: 1, createdAt: 1 },
    { name: "record_key_completed_created_idx" }
);

db[recordsCollection].createIndex(
    { partitionNo: 1, recordKey: 1, createdAt: 1 },
    { name: "fifo_pipeline_idx" }
);

// -----------------------------------------------------------------------------
// 2. outbox_instances
// -----------------------------------------------------------------------------
print("Creating collection: " + instancesCollection);
db.createCollection(instancesCollection);

print("Creating indexes on: " + instancesCollection);

db[instancesCollection].createIndex(
    { status: 1 },
    { name: "status_idx" }
);

db[instancesCollection].createIndex(
    { lastHeartbeat: 1 },
    { name: "lastHeartbeat_idx" }
);

db[instancesCollection].createIndex(
    { status: 1, lastHeartbeat: 1 },
    { name: "status_heartbeat_idx" }
);

// -----------------------------------------------------------------------------
// 3. outbox_partition_assignments
// -----------------------------------------------------------------------------
print("Creating collection: " + assignmentsCollection);
db.createCollection(assignmentsCollection);

print("Creating indexes on: " + assignmentsCollection);

db[assignmentsCollection].createIndex(
    { instanceId: 1 },
    { name: "instanceId_idx" }
);

print("");
print("=== Setup complete ===");

