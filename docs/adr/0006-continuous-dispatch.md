# ADR-0006: Continuous Dispatch of Record Keys

- **Status:** Proposed
- **Date:** 2026-11-14
- **Decision owner:** @Alek96
- **Discussion:** [How should the scheduler poll again without waiting when records are pending?](https://github.com/orgs/namastack/discussions/537)
- **Related:** discussion #537, issue #545
- **Supersedes:** None

## Context

A processing run selects up to `polling.batch-size` record keys from the owned partitions,
submits one task per key to the processing executor, and waits for every task to finish
before the run ends. The next run starts after the polling trigger's delay.

Waiting for the whole batch is a barrier. When one key is slow, the other keys of the batch
finish, their execution capacity stays idle, and no new keys are selected until the slowest
key completes. Records that arrive in the meantime wait for that key as well, even when the
instance has free capacity.

Removing the barrier changes when keys are selected and how the polling trigger is consulted,
and it changes the public `OutboxPollingTrigger` interface. These choices affect ordering
guarantees, scheduling behavior, and configuration, so they are recorded here.

## Requirements and Constraints

- Free capacity is refilled while other keys of the run are still in flight.
- Per-key ordering and the strict and non-strict failure rules (`processing.stop-on-first-failure`)
  are unchanged. A key is never in flight twice on the same instance.
- With completion batching enabled, a key stays in flight until its completion commit is
  acknowledged.
- The number of keys held by an instance is bounded and matches batch processing for the same
  configuration.
- No schema change and no database-specific scheduling logic.
- Waiting is driven by completion signals and bounded timeouts, never by a busy loop. Query
  volume while waiting is not higher than regular polling.
- Batch processing stays the default and keeps its current behavior.
- Non-goal: preempting slow handlers, or helping when every slot is occupied.
- Non-goal: capacity isolation between processing groups (issue #532).
- Non-goal: bounding the run length and yielding to partition rebalancing. This ADR does not
  decide it; see Follow-up Work.

## Considered Options

### Configuration

**Option A1: A dedicated property group with an `enabled` flag** and its own in-flight limit.
This adds a second limit that can conflict with `polling.batch-size` and the executor size,
and it leaves no room for further dispatch strategies without more flags.

**Option A2: A `processing.dispatch-mode` selector** (`batch` or `continuous`) that reuses
the existing batch size and polling settings.

### In-flight limit

**Option B1: Derived from the executor size.** It matches thread capacity, but it cannot be
determined for a user-supplied executor, and it differs from what batch processing holds.

**Option B2: `polling.batch-size`.** Batch processing already holds `batch-size` keys per
run: with platform threads, `executor-core-pool-size` of them run and the rest wait in the
executor queue; with virtual threads, all of them run. Using the same limit keeps the load
profile identical when switching modes.

**Option B3: `batch-size + batch-size / 4`**, to keep a prefetched buffer. It is equivalent
to a 25% larger batch size, hides that increase from the user, holds more loaded records,
and lengthens the drain before a partition handoff. With platform threads the executor queue
already acts as a buffer whenever `batch-size` exceeds the core pool size.

### Selecting keys that are not in flight

**Option C1: A repository exclusion parameter** (`record_key NOT IN (...)`). The exclusion
list is bounded by database limits on `IN` lists and bind parameters (for example, 1000
expressions in an Oracle `IN` list and 2100 parameters per SQL Server statement), and it
needs an implementation in every persistence module.

**Option C2: Over-fetch and filter.** Query `batch-size` keys, drop the keys that are
already in flight, and dispatch the remaining keys. It reuses `findRecordKeysInPartitions`
unchanged.

### When to refill

**Option D1: After every completed key.** This gives the lowest latency, but it runs a key
query and a record load for each completed key.

**Option D2: After a threshold of completed keys, with a timeout.** Queries are grouped, and
the timeout prevents waiting on a slow key when new records arrive.

### Wait timeout for adaptive polling

**Option E1: `polling.adaptive.min-interval`.** It is constant, but it keeps querying at the
fastest rate while a slow key runs and nothing new arrives.

**Option E2: Feed each in-run fetch result to the trigger and ask it for its interval.** The
adaptive trigger backs off while fetches find nothing and speeds up when they are full,
inside a run as between runs.

## Decision

Option A2 for configuration, Option B2 for the in-flight limit, Option C2 for key selection,
Option D2 for refilling, and Option E2 for the wait timeout.

1. **Configuration.** `namastack.outbox.processing.dispatch-mode` accepts `batch` (default)
   and `continuous`. No other property is added.
2. **Limits.**
   - **In-flight limit:** at most `polling.batch-size` keys are in flight. A key is in flight
     from its reservation until its task finishes, including the acknowledged completion
     commit. The free slots are the batch size minus the keys in flight.
   - **Refill threshold:** `max(1, batch-size / 4)` keys, using integer division. It is internal
     and not configurable.
3. **Run algorithm.** Within one stable partition assignment:
   1. Query `findRecordKeysInPartitions` with the batch size as limit, skip keys that are in
      flight, and keep at most as many keys as there are free slots.
   2. Report the fetch to the trigger with `onPollResult(found, requested)`, where `requested`
      is the number of free slots and `found` the number of keys kept.
   3. Reserve each kept key, then load its records with `findIncompleteRecordPages` in the
      existing chunks, and submit one task per key. Records are loaded only after the
      reservation, so a key list from a slightly older query can cause wasted work but never
      repeated processing.
   4. If nothing is in flight and the fetch found no keys, end the run.
   5. Otherwise wait until at least the refill threshold of keys have finished since the last
      fetch, or until the wait timeout passes, whichever comes first, and continue with step 1.
4. **Wait timeout.** Before each wait, the scheduler calls the trigger's `nextExecution` with a
   context whose last completion is now, and uses the difference to now. The fixed trigger
   returns `polling.fixed.interval`; the adaptive trigger returns its current delay. A `null`
   result ends dispatching for the run; a delay below 1 ms is raised to 1 ms.
5. **Trigger API.** `OutboxPollingTrigger.onTaskComplete(recordCount: Int)` is replaced by
   `onPollResult(found: Int, requested: Int)`, with a no-op default. `AdaptivePollingTrigger`
   applies its rule relative to `requested`: a full result halves the delay down to
   `min-interval`, a result of at most `requested / 4` doubles it up to `max-interval`.
   - Batch mode calls `onPollResult(count, batch-size)` once at the end of each run, where
     `count` is the number of keys processed.
   - Continuous mode calls it after every fetch in the run and not at the end of the run.
6. **Errors and lifecycle.** A failed fetch or record load, a rejected submission, an
   interrupt, shutdown, or a compatibility cooldown raised by any task stops further dispatch
   for the run. A rejected submission releases its reservation immediately. All accepted tasks
   are drained before the assignment is released, as in batch processing.
7. **Implementation shape.** Batch and continuous dispatch are separate strategies behind an
   internal interface used by `OutboxProcessingScheduler`, so the batch path stays unchanged.

## Rationale

Reusing `batch-size` gives the setting one meaning in both modes: the number of keys an
instance holds at once. Switching to continuous dispatch changes only when free slots are
reused, not how many keys, threads, or loaded records an instance holds, and users tune it
exactly as before.

Over-fetching keeps the key query identical to batch processing and needs no schema change.
Because the in-flight set never exceeds the query limit, it guarantees progress without an
exclusion list.

The refill threshold groups key queries and record loads under load. The timeout covers the
case the barrier was causing: when only slow keys remain in flight, new records are found
within one polling interval rather than after the slowest key.

Asking the trigger for its interval keeps fixed and adaptive polling behaving the same way
inside a run as between runs, without a separate set of wait settings.

## Consequences

- Free capacity is reused while slow keys run, and records that arrive during a slow key are
  picked up within one polling interval.
- Under load, the instance runs more key queries and smaller record loads than batch
  processing, about one per refill threshold of completed keys. This must be measured.
- A run under sustained load does not end until the run-length decision below is made.
  Continuous dispatch must not be released before that.
- Renaming `onTaskComplete` to `onPollResult` and adding the `requested` parameter is a
  breaking change for custom `OutboxPollingTrigger` implementations. Release notes must
  describe the migration.
- With platform threads, queued keys are reserved and their records loaded before a thread is
  free, as in batch processing. Users keep `batch-size` above `executor-core-pool-size` to
  keep threads busy between refills; the defaults (10 and 4) already do so.
- The mode is only as fast as the free capacity allows. When every slot holds a slow key,
  it behaves like batch processing.

## Follow-up Work

- Decide how a continuous run ends under sustained load and yields to partition rebalancing:
  a maximum run duration bounded by `instance.rebalance-interval`, and a rebalance-due signal
  such as queued threads on the assignment lock or the coordinator's last rebalance time.
- Measure uniform and mixed handler durations, including a few slow keys among many fast keys
  and new arrivals after the backlog drains. Compare fast-key latency, executor usage, query
  volume, memory, and rebalance delay against batch processing, and exercise node joins,
  crashes, shutdown, retries, and task rejection with keys in flight.
- Revisit the refill threshold or an explicit prefetch buffer if measurements show idle
  threads while refill queries run.
- Document the mode in `namastack-outbox-docs/docs/reference/`.
