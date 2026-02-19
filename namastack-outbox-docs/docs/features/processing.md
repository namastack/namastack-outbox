# Processing Chain

!!! info "Internal Processing Pipeline"
    The library uses a **Chain of Responsibility** pattern to process outbox records through multiple stages. Each processor in the chain handles a specific concern and can delegate to the next processor when needed.

## Chain Architecture

The processing chain consists of four processors, executed in this exact order:

```mermaid
flowchart LR
    A((Start)) --> B[Invoke Handler]
    B --> C{Success?}

    C -- Yes --> Z[Mark COMPLETED]
    C -- No --> D{Can Retry?}

    D -- Yes --> E[Schedule Retry]

    D -- No --> F{Fallback Exists?}

    F -- Yes --> G[Invoke Fallback]
    G --> H{Fallback Success?}

    H -- Yes --> Z
    H -- No --> Y[Mark FAILED]

    F -- No --> Y

    Y --> S((Stop))
    Z --> S((Stop))
```

**Processing Flow:**

1. **Primary Handler Processor** - Invokes the registered handler for the payload type. On success, marks record as `COMPLETED`. On exception, passes to Retry Processor.

2. **Retry Processor** - Evaluates if the exception is retryable and if retry limit is not exceeded. Schedules next retry with calculated delay or passes to Fallback Processor.

3. **Fallback Processor** - Invokes registered fallback handler if available. On success, marks record as `COMPLETED`. On failure or if no fallback exists, passes to Permanent Failure Processor.

4. **Permanent Failure Processor** - Marks the record as permanently `FAILED`. Final state - no further processing.

---

## Processing Flow Examples

### Scenario 1: Retries Exhausted with Successful Fallback

Order processing with 3 max retries and fallback handler:

```mermaid
sequenceDiagram
    participant S as Scheduler
    participant P as Primary Processor
    participant R as Retry Processor
    participant F as Fallback Processor
    participant DB as Database
    
    Note over S,DB: Attempts 1-3: Retries
    loop 3 times
        S->>P: Process Record
        P->>P: SocketTimeoutException
        P->>R: Handle Exception
        R->>R: Can Retry? ✓
        R->>DB: Schedule Retry (1s→2s→4s)
    end
    
    Note over S,DB: Attempt 4: Fallback
    S->>P: Process Record
    P->>P: SocketTimeoutException
    P->>R: Handle Exception
    R->>R: Retries Exhausted
    R->>F: Invoke Fallback
    F->>F: Publish to DLQ
    F->>DB: Mark COMPLETED ✓
```

### Scenario 2: Non-Retryable Exception

Order processing with non-retryable exception:

```mermaid
sequenceDiagram
    participant S as Scheduler
    participant P as Primary Processor
    participant R as Retry Processor
    participant F as Fallback Processor
    participant DB as Database
    
    S->>P: Process Record
    P->>P: IllegalArgumentException
    P->>R: Handle Exception
    R->>R: Non-Retryable Exception
    R->>F: Invoke Fallback
    F->>F: Publish to DLQ
    F->>DB: Mark COMPLETED ✓
```

### Scenario 3: No Fallback Handler

Order processing without fallback handler:

```mermaid
sequenceDiagram
    participant S as Scheduler
    participant P as Primary Processor
    participant R as Retry Processor
    participant F as Fallback Processor
    participant PF as Permanent Failure Processor
    participant DB as Database
    
    Note over S,DB: Attempts 1-3: Retries
    loop 3 times
        S->>P: Process Record
        P->>P: SocketTimeoutException
        P->>R: Handle Exception
        R->>R: Can Retry? ✓
        R->>DB: Schedule Retry (1s→2s→4s)
    end
    
    Note over S,DB: Attempt 4: Permanent Failure
    S->>P: Process Record
    P->>P: SocketTimeoutException
    P->>R: Handle Exception
    R->>R: Retries Exhausted
    R->>F: Check Fallback
    F->>F: No Fallback Found
    F->>PF: Handle Permanent Failure
    PF->>DB: Mark FAILED ✗
```

