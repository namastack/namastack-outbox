# Payload Serialization

Records are serialized before storage and deserialized during processing. The library provides flexible serialization through the `OutboxPayloadSerializer` interface.

## Jackson Module (Default)

The `namastack-outbox-jackson` module provides default JSON serialization using Jackson 3.x:

```yaml
dependencies {
  implementation("io.namastack:namastack-outbox-starter-jpa:{{ outbox_version }}")  # Includes Jackson by default
}
```

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

Implement custom serializers for alternative formats (Protobuf, Avro, XML, etc.):

=== "Kotlin"

    ```kotlin
    interface OutboxPayloadSerializer {
        fun serialize(payload: Any): String
        fun <T> deserialize(data: String, targetType: Class<T>): T
    }
    ```

=== "Java"

    ```java
    public interface OutboxPayloadSerializer {
        String serialize(Object payload);
        <T> T deserialize(String data, Class<T> targetType);
    }
    ```

---

## Custom Serializer Implementation

=== "Kotlin - Protocol Buffers Example"

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

=== "Java - Protocol Buffers Example"

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

**Important:** When you provide a custom serializer as a Spring bean, it automatically replaces the default Jackson serializer.

