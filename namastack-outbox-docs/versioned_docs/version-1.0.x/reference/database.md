---
title: Database Support
description: Supported databases and schema management.
sidebar_position: 12
---

# Database Support

## Supported Databases

Any JPA/JDBC-compatible database is supported. Automatic schema creation (JDBC module only) is available for:

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server

---

## Schema Management

### JDBC Module

The JDBC module automatically creates its schema on startup by default. You can disable it:

```yaml
namastack:
  outbox:
    jdbc:
      schema-initialization:
        enabled: false
```

### JPA Module

The JPA module does **not** support automatic schema creation. Use one of these approaches:

**Option 1: Hibernate DDL Auto (Development)**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create  # or create-drop, update
```

**Option 2: Flyway/Liquibase (Production)**

Create tables manually using migration scripts. Database-specific schemas are available:
👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

Then configure Hibernate to validate the schema:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```
