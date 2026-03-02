---
title: Serialization
description: Flexible payload serialization with Jackson or custom serializers.
sidebar_position: 6
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import VersionedCode from '@site/src/components/VersionedCode';

# Payload Serialization

Records are serialized before storage and deserialized during processing. The library provides flexible serialization through the `OutboxPayloadSerializer` interface.

## Jackson Module (Default)

The `namastack-outbox-jackson` module is the default for JSON serialization, leveraging Jackson 3.x. It is automatically included when you use either the JDBC or JPA starter.

**Supported Features:**

- JSON serialization/deserialization
- Custom Jackson modules and mixins
- Standard Jackson configuration via `spring.jackson.*` properties

**Example Jackson Configuration:**

```yaml
spring:
  jackson:
    default-property-inclusion: NON_NULL
    serialization:
      write-dates-as-timestamps: false
      indent-output: true
    deserialization:
      fail-on-unknown-properties: false
```

---

## OutboxPayloadSerializer Interface

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
interface OutboxPayloadSerializer {
    fun serialize(payload: Any): String
    fun <T> deserialize(data: String, targetType: Class<T>): T
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public interface OutboxPayloadSerializer {
    String serialize(Object payload);
    <T> T deserialize(String data, Class<T> targetType);
}
```

</TabItem>
</Tabs>

---

## Custom Serializer Implementation

<Tabs>
<TabItem value="kotlin" label="Kotlin - Protocol Buffers Example">

```kotlin
@Configuration
class OutboxSerializationConfig {
    @Bean
    fun protobufSerializer(): OutboxPayloadSerializer {
        return object : OutboxPayloadSerializer {
            override fun serialize(payload: Any): String {
                // Implement Protobuf serialization
                require(payload is Message) { "Payload must be Protobuf Message" }
                return Base64.encoder.encodeToString(payload.toByteArray())
            }
            override fun <T> deserialize(data: String, targetType: Class<T>): T {
                // Implement Protobuf deserialization
                val bytes = Base64.decoder.decode(data)
                val method = targetType.getMethod("parseFrom", ByteArray::class.java)
                @Suppress("UNCHECKED_CAST")
                return method.invoke(null, bytes) as T
            }
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java - Protocol Buffers Example">

```java
@Configuration
public class OutboxSerializationConfig {
    @Bean
    public OutboxPayloadSerializer protobufSerializer() {
        return new OutboxPayloadSerializer() {
            @Override
            public String serialize(Object payload) {
                if (!(payload instanceof Message)) {
                    throw new IllegalArgumentException("Payload must be Protobuf Message");
                }
                byte[] bytes = ((Message) payload).toByteArray();
                return Base64.getEncoder().encodeToString(bytes);
            }
            @Override
            public <T> T deserialize(String data, Class<T> targetType) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(data);
                    java.lang.reflect.Method method = targetType.getMethod("parseFrom", byte[].class);
                    return (T) method.invoke(null, bytes);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
```

</TabItem>
</Tabs>

**Important:** When you provide a custom serializer as a Spring bean, it automatically replaces the default Jackson serializer.
