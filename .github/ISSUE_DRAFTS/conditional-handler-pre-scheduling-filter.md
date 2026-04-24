# Add scheduling-time handler support checks for generic outbox handlers

## Problem

Messaging integrations (Kafka/SNS/Rabbit) are registered as generic `OutboxHandler`s. During scheduling, the outbox currently creates one record per discovered handler, even when the handler will later skip the payload based on routing filter/configuration.

This causes avoidable outbox records and unnecessary processing overhead.

## Goal

Introduce a scheduling-time support check so handlers can opt out *before* records are persisted.

## Proposed Solution

1. Extend `OutboxHandler` with:
   - `supports(payload: Any, metadata: OutboxRecordMetadata): Boolean`
   - default implementation returns `true` to preserve backward compatibility.
2. During handler collection in `OutboxService`, evaluate support before persisting a record for a handler.
3. Implement `supports(...)` in messaging handlers (Kafka, SNS, Rabbit) and delegate to routing/filter logic.
4. Keep runtime safety guard in `handle(...)` for defensive behavior.

## Backward Compatibility

- Existing custom handlers remain compatible because `supports(...)` defaults to `true`.
- Existing typed handlers are unaffected.

## Acceptance Criteria

- [ ] Outbox does not persist records for generic handlers when `supports(...)` is `false`.
- [ ] Kafka/SNS/Rabbit handlers expose `supports(...)` and align with routing `filter` outcomes.
- [ ] Existing handlers compile and behave as before when they do not override `supports(...)`.
- [ ] Unit tests cover positive/negative support cases.

## Notes

This improves performance and reduces noise in outbox tables, especially for applications with multiple enabled messaging modules.
