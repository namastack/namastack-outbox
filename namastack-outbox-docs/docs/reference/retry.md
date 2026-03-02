---
title: Retry Mechanisms
description: Sophisticated retry strategies with exponential backoff, jitter, and exception filtering.
sidebar_position: 7
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Retry Mechanisms

The library provides sophisticated retry strategies to handle transient failures gracefully. You can configure a default retry policy for all handlers and optionally override it per handler.

## Default Retry Policy

The default retry policy applies to all handlers unless overridden. Configure it via `application.yml` or by providing a custom `OutboxRetryPolicy` bean.

### Built-in Retry Policies

#### Fixed Delay

Retry with a constant delay between attempts:

```yaml
namastack:
  outbox:
    retry:
      policy: "fixed"
      max-retries: 5
      fixed:
        delay: 5000  # 5 seconds between retries
```

**Use Case:** Simple scenarios with consistent retry intervals

**Example Retry Schedule:** 0s → 5s → 5s → 5s → 5s → 5s → Failed

#### Linear Backoff

Retry with linearly increasing delays:

```yaml
namastack:
  outbox:
    retry:
      policy: "linear"
      max-retries: 5
      linear:
        initial-delay: 2000    # Start with 2 seconds
        increment: 2000        # Add 2 seconds each retry
        max-delay: 60000       # Cap at 1 minute
```

**Use Case:** Gradually increasing delays for services that need time to recover

**Example Retry Schedule:** 0s → 2s → 4s → 6s → 8s → 10s → Failed

#### Exponential Backoff

Retry with exponentially increasing delays:

```yaml
namastack:
  outbox:
    retry:
      policy: "exponential"
      max-retries: 3
      exponential:
        initial-delay: 1000    # Start with 1 second
        max-delay: 60000       # Cap at 1 minute
        multiplier: 2.0        # Double each time
```

**Use Case:** Handles transient failures gracefully without overwhelming downstream services

**Retry Schedule:** 0s → 1s → 2s → 4s → 8s → 16s → 32s (capped at 60s)

#### Jittered Retry

Add random jitter to prevent thundering herd problems. Jitter can be applied to any base policy (fixed, linear, or exponential):

```yaml
namastack:
  outbox:
    retry:
      policy: "exponential"  # Can also be "fixed" or "linear"
      max-retries: 7
      exponential:
        initial-delay: 2000
        max-delay: 60000
        multiplier: 2.0
      jitter: 1000  # Add [-1000ms, 1000ms] random delay
```

**Benefits:** Prevents coordinated retry storms when multiple instances retry simultaneously

---

## Exception Filtering

<Tabs>
<TabItem value="yaml" label="YAML Config">

```yaml
namastack:
  outbox:
    retry:
      policy: exponential
      max-retries: 3
      exponential:
        initial-delay: 1000
        max-delay: 60000
        multiplier: 2.0
      # Only retry these exceptions
    include-exceptions:
      - java.net.SocketTimeoutException
      - org.springframework.web.client.ResourceAccessException
      - java.io.IOException
    # Never retry these exceptions
    exclude-exceptions:
      - java.lang.IllegalArgumentException
      - javax.validation.ValidationException
      - com.example.BusinessException
```

</TabItem>
<TabItem value="rules" label="Rules">

**Rules:**

- If `include-exceptions` is set, **only** listed exceptions are retryable
- If `exclude-exceptions` is set, listed exceptions are **never** retried
- If both are set, `exclude-exceptions` takes precedence
- If neither is set, all exceptions are retryable (default behavior)

</TabItem>
<TabItem value="usecases" label="Use Cases">

**Use Cases:**

- **include-exceptions**: Whitelist transient errors (network, timeout, rate limiting)
- **exclude-exceptions**: Blacklist permanent errors (validation, business rules, auth failures)

</TabItem>
</Tabs>

---

## Custom Default Retry Policy

Implement the `OutboxRetryPolicy` interface and register it as a bean named `outboxRetryPolicy`:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Configuration
class OutboxConfig {
    @Bean("outboxRetryPolicy")
    fun customRetryPolicy(): OutboxRetryPolicy {
        return object : OutboxRetryPolicy {
            override fun shouldRetry(exception: Throwable): Boolean {
                // Don't retry validation errors
                if (exception is IllegalArgumentException) return false
                // Don't retry permanent failures
                if (exception is PaymentDeclinedException) return false
                // Retry transient failures
                return exception is TimeoutException || 
                       exception is IOException ||
                       exception.cause is TimeoutException
            }
            override fun nextDelay(failureCount: Int): Duration {
                // Exponential backoff: 1s → 2s → 4s → 8s (capped at 60s)
                val delayMillis = 1000L * (1L shl failureCount)
                return Duration.ofMillis(minOf(delayMillis, 60000L))
            }
            override fun maxRetries(): Int = 5
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class OutboxConfig {
    @Bean("outboxRetryPolicy")
    public OutboxRetryPolicy customRetryPolicy() {
        return new OutboxRetryPolicy() {
            @Override
            public boolean shouldRetry(Throwable exception) {
                // Don't retry validation errors
                if (exception instanceof IllegalArgumentException) return false;
                // Don't retry permanent failures
                if (exception instanceof PaymentDeclinedException) return false;
                // Retry transient failures
                return exception instanceof TimeoutException || 
                       exception instanceof IOException ||
                       (exception.getCause() instanceof TimeoutException);
            }
            @Override
            public Duration nextDelay(int failureCount) {
                // Exponential backoff: 1s → 2s → 4s → 8s (capped at 60s)
                long delayMillis = 1000L * (1L << failureCount);
                return Duration.ofMillis(Math.min(delayMillis, 60000L));
            }
            @Override
            public int maxRetries() {
                return 5;
            }
        };
    }
}
```

</TabItem>
</Tabs>

**Key Methods:**

- `shouldRetry(exception: Throwable): Boolean` - Decide if this error should be retried
- `nextDelay(failureCount: Int): Duration` - Calculate delay before next retry
- `maxRetries(): Int` - Maximum number of retry attempts

**Important:** The bean must be named `outboxRetryPolicy` to override the default policy configured in `application.yml`.

---

## Handler-Specific Retry Policies

Override the default retry policy for specific handlers using `@OutboxRetryable` annotation or by implementing the `OutboxRetryAware` interface.

You can configure retry behavior per handler, allowing different handlers to have different retry strategies.

### Interface-Based Approach

Implement the `OutboxRetryAware` interface to specify a retry policy programmatically:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class PaymentHandler(
    private val aggressiveRetryPolicy: AggressiveRetryPolicy
) : OutboxTypedHandler<PaymentEvent>, OutboxRetryAware {
    override fun handle(payload: PaymentEvent, metadata: OutboxRecordMetadata) {
        paymentGateway.process(payload)
    }
    override fun getRetryPolicy(): OutboxRetryPolicy = aggressiveRetryPolicy
}

@Component
class NotificationHandler(
    private val conservativeRetryPolicy: ConservativeRetryPolicy
) : OutboxTypedHandler<NotificationEvent>, OutboxRetryAware {
    override fun handle(payload: NotificationEvent, metadata: OutboxRecordMetadata) {
        emailService.send(payload)
    }
    override fun getRetryPolicy(): OutboxRetryPolicy = conservativeRetryPolicy
}

@Component
class AggressiveRetryPolicy : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable) = true
    override fun nextDelay(failureCount: Int) = Duration.ofMillis(500)
    override fun maxRetries() = 10
}

@Component
class ConservativeRetryPolicy : OutboxRetryPolicy {
    override fun shouldRetry(exception: Throwable) = 
        exception !is IllegalArgumentException
    override fun nextDelay(failureCount: Int) = Duration.ofSeconds(10)
    override fun maxRetries() = 2
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class PaymentHandler implements OutboxTypedHandler<PaymentEvent>, OutboxRetryAware {
    private final AggressiveRetryPolicy aggressiveRetryPolicy;
    
    public PaymentHandler(AggressiveRetryPolicy aggressiveRetryPolicy) {
        this.aggressiveRetryPolicy = aggressiveRetryPolicy;
    }
    
    @Override
    public void handle(PaymentEvent payload, OutboxRecordMetadata metadata) {
        paymentGateway.process(payload);
    }
    
    @Override
    public OutboxRetryPolicy getRetryPolicy() {
        return aggressiveRetryPolicy;
    }
}

@Component
public class NotificationHandler implements OutboxTypedHandler<NotificationEvent>, OutboxRetryAware {
    private final ConservativeRetryPolicy conservativeRetryPolicy;
    
    public NotificationHandler(ConservativeRetryPolicy conservativeRetryPolicy) {
        this.conservativeRetryPolicy = conservativeRetryPolicy;
    }
    
    @Override
    public void handle(NotificationEvent payload, OutboxRecordMetadata metadata) {
        emailService.send(payload);
    }
    
    @Override
    public OutboxRetryPolicy getRetryPolicy() {
        return conservativeRetryPolicy;
    }
}

@Component
public class AggressiveRetryPolicy implements OutboxRetryPolicy {
    @Override
    public boolean shouldRetry(Throwable exception) {
        return true;
    }
    
    @Override
    public Duration nextDelay(int failureCount) {
        return Duration.ofMillis(500);
    }
    
    @Override
    public int maxRetries() {
        return 10;
    }
}

@Component
public class ConservativeRetryPolicy implements OutboxRetryPolicy {
    @Override
    public boolean shouldRetry(Throwable exception) {
        return !(exception instanceof IllegalArgumentException);
    }
    
    @Override
    public Duration nextDelay(int failureCount) {
        return Duration.ofSeconds(10);
    }
    
    @Override
    public int maxRetries() {
        return 2;
    }
}
```

</TabItem>
</Tabs>

### Annotation-Based Approach

Use the `@OutboxRetryable` annotation for method-level retry policy configuration:

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Component
class PaymentHandler {
    // Critical handler - aggressive retries
    @OutboxHandler
    @OutboxRetryable(AggressiveRetryPolicy::class)
    fun handlePayment(payload: PaymentEvent) {
        paymentGateway.process(payload)
    }
    
    // Less critical handler - conservative retries
    @OutboxHandler
    @OutboxRetryable(ConservativeRetryPolicy::class)
    fun handleNotification(payload: NotificationEvent) {
        emailService.send(payload)
    }
    
    // Uses default retry policy
    @OutboxHandler
    fun handleAudit(payload: AuditEvent) {
        auditService.log(payload)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Component
public class PaymentHandler {
    // Critical handler - aggressive retries
    @OutboxHandler
    @OutboxRetryable(AggressiveRetryPolicy.class)
    public void handlePayment(PaymentEvent payload) {
        paymentGateway.process(payload);
    }
    
    // Less critical handler - conservative retries
    @OutboxHandler
    @OutboxRetryable(ConservativeRetryPolicy.class)
    public void handleNotification(NotificationEvent payload) {
        emailService.send(payload);
    }
    
    // Uses default retry policy
    @OutboxHandler
    public void handleAudit(AuditEvent payload) {
        auditService.log(payload);
    }
}
```

</TabItem>
</Tabs>

**Policy Resolution Order:**

1. **Handler-specific policy via interface** (`OutboxRetryAware.getRetryPolicy()`) - highest priority
2. **Handler-specific policy via annotation** (`@OutboxRetryable`)
3. **Global custom policy** (bean named `outboxRetryPolicy`)
4. **Default policy** (from `application.yml`)

!!! tip "Interface vs Annotation"
    - **Interface (`OutboxRetryAware`)**: Best when handler class is dedicated to single payload type, and you want type safety
    - **Annotation (`@OutboxRetryable`)**: Best for method-level handlers or multiple handlers in one class

---

## OutboxRetryPolicy.Builder API

Use the fluent builder to compose robust retry policies without implementing the interface yourself.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Configuration
class OutboxConfig {
    @Bean
    fun customRetryPolicy(): OutboxRetryPolicy {
        return OutboxRetryPolicy.builder()
            .maxRetries(5)
            .exponentialBackoff(
                initialDelay = Duration.ofSeconds(10),
                multiplier = 2.0,
                maxDelay = Duration.ofMinutes(5)
            )
            .jitter(Duration.ofSeconds(2))
            .retryOn(TimeoutException::class.java, IOException::class.java)
            .noRetryOn(IllegalArgumentException::class.java)
            .build()
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class OutboxConfig {
    @Bean
    public OutboxRetryPolicy customRetryPolicy() {
        return OutboxRetryPolicy.builder()
            .maxRetries(5)
            .exponentialBackoff(
                Duration.ofSeconds(10),
                2.0,
                Duration.ofMinutes(5)
            )
            .jitter(Duration.ofSeconds(2))
            .retryOn(TimeoutException.class, IOException.class)
            .noRetryOn(IllegalArgumentException.class)
            .build();
    }
}
```

</TabItem>
</Tabs>

**Builder at a glance:**

- Defaults: maxRetries = 3, fixedBackOff = 5s, jitter = 0, retry on all exceptions
- Immutability: each method returns a new Builder; the original instance isn't mutated
- Validation: durations must be > 0 (except jitter, which can be 0), multiplier > 1.0

### Using the autoconfigured Builder

A bean named `outboxRetryPolicyBuilder` is auto-configured from your `namastack.outbox.retry.*` application properties. Inject it to retain property-driven defaults and add programmatic customizations.

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
@Configuration
class OutboxConfig {
    // Inject the autoconfigured builder from application.yml
    @Bean
    fun customRetryPolicy(
        builder: OutboxRetryPolicy.Builder
    ): OutboxRetryPolicy {
        // Start from property-based defaults, then refine
        return builder
            .retryOn(TimeoutException::class.java, IOException::class.java)
            .noRetryOn(IllegalArgumentException::class.java)
            .build()
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class OutboxConfig {
    // Inject the autoconfigured builder from application.yml
    @Bean
    public OutboxRetryPolicy customRetryPolicy(
        OutboxRetryPolicy.Builder builder
    ) {
        // Start from property-based defaults, then refine
        return builder
            .retryOn(TimeoutException.class, IOException.class)
            .noRetryOn(IllegalArgumentException.class)
            .build();
    }
}
```

</TabItem>
</Tabs>

### Builder configuration options

<Tabs>
<TabItem value="kotlin" label="Kotlin">

**Backoff strategies:**

- Fixed: same delay for all retries  
  `fixedBackOff(Duration.ofSeconds(30))`

- Linear: incrementally increasing delay  
  `linearBackoff(initialDelay = Duration.ofSeconds(5), increment = Duration.ofSeconds(5), maxDelay = Duration.ofMinutes(2))`

- Exponential: exponentially increasing delay  
  `exponentialBackoff(initialDelay = Duration.ofSeconds(10), multiplier = 2.0, maxDelay = Duration.ofMinutes(5))`

- Custom: provide your own strategy  
  `backOff(myCustomBackOffStrategy)`

**Jitter:**

Jitter randomizes the computed delay within [base - jitter, base + jitter] to avoid thundering herds; delays never go below zero.

```kotlin
OutboxRetryPolicy.builder()
    .fixedBackOff(Duration.ofSeconds(30))
    .jitter(Duration.ofSeconds(5))  // Actual delay: ~25-35 seconds
```

**Exception rules and priority:**

- noRetryOn(): these exceptions are never retried (highest priority)
- retryOn(): if specified, only these exceptions (or subclasses) are retried
- retryIf(): predicate for advanced logic
- Default: if neither retryOn() nor retryIf() is configured, all exceptions are retried; if any rule is configured but none match, do not retry

```kotlin
// Retry only on specific exceptions
OutboxRetryPolicy.builder()
    .retryOn(TimeoutException::class.java, IOException::class.java)
    .build()

// Retry all except specific exceptions
OutboxRetryPolicy.builder()
    .noRetryOn(IllegalArgumentException::class.java, PaymentDeclinedException::class.java)
    .build()

// Custom predicate for complex logic
OutboxRetryPolicy.builder()
    .retryIf { exception ->
        exception is RetryableException ||
        (exception.cause is TimeoutException)
    }
    .build()

// Combine multiple rules (noRetryOn takes precedence)
OutboxRetryPolicy.builder()
    .retryOn(IOException::class.java)
    .noRetryOn(FileNotFoundException::class.java)
    .retryIf { exception -> exception.message?.contains("transient") == true }
    .build()
```

</TabItem>
<TabItem value="java" label="Java">

**Backoff strategies:**

- Fixed: same delay for all retries  
  `fixedBackOff(Duration.ofSeconds(30))`

- Linear: incrementally increasing delay  
  `linearBackoff(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(2))`

- Exponential: exponentially increasing delay  
  `exponentialBackoff(Duration.ofSeconds(10), 2.0, Duration.ofMinutes(5))`

- Custom: provide your own strategy  
  `backOff(myCustomBackOffStrategy)`

**Jitter:**

Jitter randomizes the computed delay within [base - jitter, base + jitter] to avoid thundering herds; delays never go below zero.

```java
OutboxRetryPolicy.builder()
    .fixedBackOff(Duration.ofSeconds(30))
    .jitter(Duration.ofSeconds(5))  // Actual delay: ~25-35 seconds
```

**Exception rules and priority:**

- noRetryOn(): these exceptions are never retried (highest priority)
- retryOn(): if specified, only these exceptions (or subclasses) are retried
- retryIf(): predicate for advanced logic
- Default: if neither retryOn() nor retryIf() is configured, all exceptions are retried; if any rule is configured but none match, do not retry

```java
// Retry only on specific exceptions
OutboxRetryPolicy.builder()
    .retryOn(TimeoutException.class, IOException.class)
    .build();

// Retry all except specific exceptions
OutboxRetryPolicy.builder()
    .noRetryOn(IllegalArgumentException.class, PaymentDeclinedException.class)
    .build();

// Custom predicate for complex logic
OutboxRetryPolicy.builder()
    .retryIf(exception ->
        exception instanceof RetryableException ||
        (exception.getCause() instanceof TimeoutException)
    )
    .build();

// Combine multiple rules (noRetryOn takes precedence)
OutboxRetryPolicy.builder()
    .retryOn(IOException.class)
    .noRetryOn(FileNotFoundException.class)
    .retryIf(exception ->
        exception.getMessage() != null &&
        exception.getMessage().contains("transient")
    )
    .build();
```

</TabItem>
</Tabs>

### Complete examples

<Tabs>
<TabItem value="kotlin" label="Kotlin">

```kotlin
// Simple policy with exponential backoff
val simplePolicy = OutboxRetryPolicy.builder()
    .maxRetries(5)
    .exponentialBackoff(
        initialDelay = Duration.ofSeconds(10),
        multiplier = 2.0,
        maxDelay = Duration.ofMinutes(5)
    )
    .build()

// Advanced policy with all features
val advancedPolicy = OutboxRetryPolicy.builder()
    .maxRetries(10)
    .linearBackoff(
        initialDelay = Duration.ofSeconds(5),
        increment = Duration.ofSeconds(5),
        maxDelay = Duration.ofMinutes(2)
    )
    .jitter(Duration.ofSeconds(2))
    .retryOn(TimeoutException::class.java, IOException::class.java)
    .noRetryOn(IllegalArgumentException::class.java)
    .retryIf { exception ->
        exception.message?.contains("retry", ignoreCase = true) == true
    }
    .build()
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Simple policy with exponential backoff
OutboxRetryPolicy simplePolicy = OutboxRetryPolicy.builder()
    .maxRetries(5)
    .exponentialBackoff(
        Duration.ofSeconds(10),
        2.0,
        Duration.ofMinutes(5)
    )
    .build();

// Advanced policy with all features
OutboxRetryPolicy advancedPolicy = OutboxRetryPolicy.builder()
    .maxRetries(10)
    .linearBackoff(
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMinutes(2)
    )
    .jitter(Duration.ofSeconds(2))
    .retryOn(TimeoutException.class, IOException.class)
    .noRetryOn(IllegalArgumentException.class)
    .retryIf(exception ->
        exception.getMessage() != null &&
        exception.getMessage().toLowerCase().contains("retry")
    )
    .build();
```

</TabItem>
</Tabs>

