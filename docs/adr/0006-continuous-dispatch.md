# ADR-0006: Continuous Dispatch of Record Keys

- **Status:** Accepted
- **Date:** 2026-11-14
- **Decision owner:** @Alek96
- **Discussion:** [How should the scheduler poll again without waiting when records are pending?](https://github.com/orgs/namastack/discussions/537)
- **Related:** issue #545
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
- Batch processing stays the default. Its key selection and processing are unchanged.
- A run ends when a partition rebalance is due, so a rebalance waits at most for the keys in
  flight, as in batch processing. No new configuration.
- Non-goal: preempting slow handlers, or helping when every slot is occupied.
- Non-goal: capacity isolation between processing groups (issue #532).
- Non-goal: bounding the rebalance delay when a handler hangs. Processing timeouts are a separate
  decision.

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

### Ending a run for a rebalance

**Option F1: The remaining delay of the scheduled rebalance** (`ScheduledFuture.getDelay()`).
Its meaning depends on the scheduler implementation, and it is unverified for user-supplied
schedulers.

**Option F2: The coordinator's own rebalance deadline**, plus threads queued on the assignment
lock. The coordinator records when its fixed-delay schedule makes the next rebalance due, which
works with any scheduler. The queued-thread check covers a rebalance that already waits for the
lock, as with virtual threads.

**Option F3: A configurable maximum run duration.** It duplicates the rebalance deadline and
adds a setting.

### Next run after a rebalance yield

**Option G1: Start the next run immediately.** It needs a trigger wrapper, a signal that work
remains, and a guard against the next run taking the assignment lock before the rebalance.

**Option G2: Schedule the next run by the polling trigger**, as after any other run.

## Decision

Option A2 for configuration, Option B2 for the in-flight limit, Option C2 for key selection,
Option D2 for refilling, Option E2 for the wait timeout, Option F2 for ending a run for a
rebalance, and Option G2 for the next run after a yield.

1. **Configuration.** `namastack.outbox.processing.dispatch-mode` accepts `batch` (default)
   and `continuous`. No other property is added.
2. **Limits.**
   - **In-flight limit:** at most `polling.batch-size` keys are in flight. A key is in flight
     from the reservation of its slot until its task finishes, including the acknowledged
     completion commit. The free slots are the batch size minus the keys in flight.
   - **Refill threshold:** `max(1, batch-size / 4)` free slots, using integer division. It is
     internal and not configurable.
3. **Fetch.** A fetch queries `findRecordKeysInPartitions` with the batch size as limit, skips
   keys that are in flight, keeps at most as many keys as there are free slots, and reports the
   result to the trigger with `onPollResult(found, requested)`, where `requested` is the number of
   free slots and `found` the number of keys kept. It then loads the records of the kept keys with
   `findIncompleteRecordPages` in chunks, reserves one slot per key, and submits one task per key.
   Only the dispatching thread fetches and reserves, and a key leaves the in-flight set only after
   its task has finished, so a key list from a slightly older query can cause wasted work but
   never repeated processing. With no free slot, the fetch skips the query and reports nothing.
4. **Run algorithm.** Within one stable partition assignment, the run fetches and continues by
   the size of the fetched batch:
   - **Empty batch** with nothing in flight: the run ends. The scheduler starts the next run when
     the trigger fires.
   - **Partial batch** (fewer keys than free slots, including an empty batch while keys are in
     flight): wait until every key in flight has finished, which ends the run, or until the wait
     timeout passes, and fetch again. A run whose keys all finish behaves like a batch run.
   - **Full batch** (a key for every free slot, or no free slot): wait until at least the refill
     threshold of slots is free, or until the wait timeout passes, and fetch again.
5. **Wait timeout.** Before each wait, the continuous dispatcher calls the trigger's
   `nextExecution` with a context whose last completion is now, and uses the difference to now.
   The fixed trigger returns `polling.fixed.interval`; the adaptive trigger returns its current
   delay. A `null` result ends dispatching for the run; a delay below 1 ms is raised to 1 ms.
6. **Trigger API.** `OutboxPollingTrigger.onTaskComplete(recordCount: Int)` is replaced by
   `onPollResult(found: Int, requested: Int)`, with a no-op default. `AdaptivePollingTrigger`
   applies its rule relative to `requested`: a full result halves the delay down to
   `min-interval`, a result of at most `requested / 4` doubles it up to `max-interval`, and a
   result with `requested` of zero leaves it unchanged.
   - Each dispatcher reports its own key queries; the scheduler reports nothing.
   - Batch mode calls `onPollResult(found, batch-size)` once per run, right after the key query,
     where `found` is the number of keys returned.
   - Continuous mode calls it after every fetch that runs a query.
   - A failed key query is reported as zero keys found. A run that never reaches a key query,
     because no partitions are assigned or the assignment lookup fails, reports nothing, and the
     trigger keeps its delay.
7. **Errors and lifecycle.** A failed key query or record load, a rejected submission, a key
   whose processing ends with an exception, an interrupt, shutdown, a due rebalance (item 8), a
   compatibility cooldown raised by any task, or a trigger that returns no next execution stops
   further dispatch for the run. A rejected submission releases its slot immediately. All
   accepted tasks are drained, ignoring interrupts, before the assignment is released, as in
   batch processing.
8. **Rebalance yield.** `PartitionCoordinator` reports a rebalance as due when the time since the
   last rebalance completed reaches `instance.rebalance-interval`, which is when its fixed-delay
   schedule runs it, or when a thread waits for the assignment lock. A stopped coordinator reports
   none. The scheduler combines this with shutdown into the stop signal the dispatcher checks
   before every fetch. Once it is set, the run stops fetching, drains its keys in flight, and
   releases the assignment. The rebalance runs, and the polling trigger schedules the next run.
   Batch processing ends after one batch and needs no check.
9. **Implementation shape.** Batch and continuous dispatch are separate strategies,
   `BatchKeyDispatcher` and `ContinuousKeyDispatcher`, behind the `OutboxKeyDispatcher`
   interface used by `OutboxProcessingScheduler`. The scheduler creates the strategy for the
   configured mode through `OutboxKeyDispatcherFactory`. The strategies are internal to the core
   module and not exposed as beans, so `processing.dispatch-mode` is the only way to select one.

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

Ending the run once a partial batch has finished hands the pause between polls back to the
trigger, exactly as in batch processing: a partial batch means the backlog is drained, so
there is nothing to refill. Ending the run when a key fails unexpectedly means a persistent
failure costs one key query per trigger interval, as in batch processing, without tracking
failed keys inside the run.

Letting each dispatcher report its own key queries gives the trigger one result per query in
both modes. Runs without a query report nothing, because they cost almost nothing: the
partition assignment is cached.

The rebalance deadline bounds the run length without a new setting, and the coordinator's own
schedule gives the same answer for every scheduler implementation. Scheduling the next run by
the trigger keeps one path for starting runs; removing the pause after a full run is the subject
of issue #540 for both modes.

## Consequences

- Free capacity is reused while slow keys run, and records that arrive during a slow key are
  picked up within one polling interval.
- Under load, the instance runs more key queries and smaller record loads than batch
  processing, about one per refill threshold of completed keys. This must be measured.
- Under sustained load, a run ends about once per rebalance interval, and the next run starts
  after one trigger delay: `polling.fixed.interval`, or the adaptive delay, which full fetches
  have reduced to `polling.adaptive.min-interval`. Issue #540 covers removing that pause.
- A rebalance waits for the slowest key in flight when it becomes due, as in batch processing.
  A hanging handler delays it without bound.
- Shutdown and a compatibility cooldown stop further fetches; batch processing finishes its
  whole batch in both cases. They are checked before each fetch, so a fetch already running when
  they start still submits its keys. A wait ends at the latest when every key in flight has
  finished, so the run takes no longer to drain than its keys in flight need.
- Renaming `onTaskComplete` to `onPollResult` and adding the `requested` parameter is a
  breaking change for custom `OutboxPollingTrigger` implementations. The batch-size
  parameter of the `AdaptivePollingTrigger` constructor is removed. Release notes must describe the migration.
- In batch mode, the adaptive trigger keeps its delay while no partitions are assigned,
  instead of backing off.
- With platform threads, queued keys are reserved and their records loaded before a thread is
  free, as in batch processing. Users keep `batch-size` above `executor-core-pool-size` to
  keep threads busy between refills; the defaults (10 and 4) already do so.
- The mode is only as fast as the free capacity allows. When every slot holds a slow key,
  it behaves like batch processing.

## Follow-up Work

- Measure uniform and mixed handler durations, including a few slow keys among many fast keys
  and new arrivals after the backlog drains. Compare fast-key latency, executor usage, query
  volume, memory, and rebalance delay under sustained load against batch processing, and
  exercise node joins, crashes, shutdown, retries, and task rejection with keys in flight.
- Revisit the refill threshold or an explicit prefetch buffer if measurements show idle
  threads while refill queries run.
- Document the mode in `namastack-outbox-docs/docs/reference/`.
