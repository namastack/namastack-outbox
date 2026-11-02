# JDBC benchmark results — 2 November 2026

The read optimizations were implemented on `feature/jdbc-query-index-optimization`.
Actual processing was measured separately on `feature/150k-strict-throughput`, with its existing
experimental completion batcher. The read branch does **not** include completion batching.
These are the highest confirmed local rates found in this investigation, not database engine limits.

## Read throughput

One-minute confirmations, eight concurrent readers, strict eligibility and String payload decoding.
The same fixture is read repeatedly; these numbers are **loaded records/s**, not unique deliveries.

| Database | Loaded records/s | Selection median / p95 | Loading median / p95 |
| --- | ---: | ---: | ---: |
| PostgreSQL | 1,097,126 | 19.6 / 22.4 ms | 108.5 / 136.1 ms |
| MySQL | 336,190 | 137.0 / 169.5 ms | 325.8 / 358.9 ms |
| SQL Server | 191,766 | 84.8 / 206.7 ms | 710.2 / 830.6 ms |
| Oracle Free | 221,012 | 127.2 / 843.9 ms | 223.8 / 972.2 ms |
| MariaDB | 352,339 | 32.6 / 35.6 ms | 413.0 / 435.0 ms |
| H2 | 406,776 | 2.6 / 6.4 ms | 285.8 / 621.0 ms |

The fixture contains one million completed history rows on inactive keys and 256,000 mixed-state
rows, ten per active key. Failed and future-retry heads block 20% of active keys. Each reader owns
disjoint partitions and selects at most 2,000 eligible keys per iteration. The first iteration
audits eligibility, every returned record ID and per-key creation-time/ID order. Query latency
is measured per reader; throughput is aggregated across readers. Startup and seeding are excluded;
JIT compilation and the initial audit are included. History on repeatedly reopened active keys
and large application payloads need separate measurements. Keys with thousands of pending
records are not represented by this uniform ten-record workload.

## Processing throughput

Five-minute strict processing runs in the **other checkout with completion batching**, deleting
completed rows. Rates below include the final count-query time. Produced input is counted only
after commit; processed records are committed input minus physically remaining run rows.

| Database | Requested input/s | Processed/s | Produced | Processed at snapshot | Pending | Failed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PostgreSQL | 200,000 | 199,721 | 60,000,000 | 59,978,628 | 21,372 | 0 |
| MySQL | 20,000 | 19,995 | 6,000,000 | 5,998,818 | 1,182 | 0 |
| SQL Server | 17,000 | 16,900 | 5,100,000 | 5,095,163 | 4,837 | 0 |
| Oracle Free | 8,000 | 7,990 | 2,400,000 | 2,397,314 | 2,686 | 0 |
| MariaDB | 40,000 | 39,735 | 12,000,000 | 11,935,569 | 64,431 | 0 |
| H2 | 15,000 | 14,980 | 4,500,000 | 4,494,255 | 5,745 | 0 |

MariaDB's 40k run left 64,431 pending records (1.61 seconds of input). It passed the bounded
screen, but this is not evidence of a flat backlog indefinitely. Its 50k and 60k confirmations failed.

A passing capacity screen requires at least 99% of requested input, at least 99% of the requested
actual producer rate, no FAILED rows, at most two seconds of input pending, and conservative
processing throughput of at least 97% of target. This is a bounded measurement, not a guarantee
of an indefinitely flat backlog. The snapshot includes some completions during the count query;
its elapsed time is included in the denominator. Deleted rows cannot prove zero retry attempts.

PostgreSQL retained four million pre-existing completed rows; the other databases retained one
million. Eight consumer JVMs use virtual threads, 2,000 keys per poll, 10-ms polls and 12 database
connections each. Oracle uses two consumers. The existing batcher uses up to 2,048 completions,
one writer per instance and a 1-ms wait; Oracle uses 5 ms. A three-second warm-up is fully drained.
The payment handler does no business work. Producers use 16 connections, 1,000 records per
commit and ten records per key, keeping each key in one transaction. PostgreSQL input uses
binary COPY; other input uses JDBC batching. This does not measure the public scheduling API.

### Higher rates that were not held

Short screens overstated sustainable processing, particularly for MySQL. The five-minute
failures below are retained as evidence rather than presented as successful capacity.

| Database | Requested/s | Actual input/s | Processed/s | Pending |
| --- | ---: | ---: | ---: | ---: |
| MariaDB | 50,000 | 49,766 | 41,341 | 2,355,168 |
| MariaDB | 60,000 | 59,512 | 30,537 | 8,360,950 |
| MySQL | 25,000 | 24,997 | 23,621 | 325,678 |
| MySQL | 30,000 | 29,995 | 19,585 | 3,087,300 |
| MySQL | 35,000 | 34,995 | 18,906 | 4,726,279 |
| MySQL | 40,000 | 39,988 | 17,527 | 6,585,714 |
| SQL Server | 20,000 | 18,534 | 18,052 | 76,600 |
| SQL Server | 25,000 | 18,807 | 18,155 | 79,165 |

PostgreSQL reached 200k input/s in the long confirmation. SQL Server was also screened with
150k and 200k requested input/s, but the generator and database delivered only about 32k input/s
in those short runs. Increasing the requested rate does not establish that it was produced.
For the other engines, lower-rate overload screens were used before choosing long confirmations.

### Read branch without completion batching

The current read branch passed a separate one-minute PostgreSQL control at 20k input/s:
1,200,000 produced, all 1,200,000 persisted as COMPLETED, zero pending or FAILED. Conservative
processing was 19,925 records/s, including the final count. This control retained completed rows
and four million historical rows. It establishes that the clean harness runs against this branch;
it is not its maximum throughput.

## Changes that made the difference

| Database | Read changes |
| --- | --- |
| PostgreSQL | Retained the reference global-head selection and bulk read queries, with the balanced shipped processing and maintenance indexes. |
| MySQL / MariaDB | Select candidate keys through a completed-time-leading covering index, then probe each global incomplete head before applying readiness and the key limit. Use subsecond timestamps in fresh schemas. |
| SQL Server | Probe heads with `CROSS APPLY TOP (1)`. For larger bulk requests, match IDs through the narrow pending index before primary-key payload lookups. Preserve Unicode binding; fresh IDs use NVARCHAR. |
| Oracle | Probe global heads with `CROSS APPLY`, use index column hints to avoid broad pending scans, and preserve UTC timestamp ordering. Bulk loading uses the key index. |
| H2 | Bind keys as an array-backed table, select distinct matching pending IDs, then load payloads by primary key. Avoid redundant outer filters that reverse the optimizer join order. |

Dialect details live in the query classes, keeping the repository flow shared. Strict selection
checks the first incomplete row globally, including FAILED rows, future retries and predecessors
in another partition. Eligibility is checked before limiting keys. Bulk pages remain ordered by
creation time and ID; future retries stay in the page for the worker to evaluate. No ordering
barriers were relaxed to obtain these numbers. Completed-status repository calls retain their
previous status-filtering behavior.

Concurrent consumers also exposed metadata registration deadlocks and schema creation races.
Bounded retries now handle those bootstrap operations; handler work is not replayed.

Window-function selection, wider MySQL head indexes, disabling Unicode parameters globally,
larger batch waits and H2 CLOB casts were rejected. A MySQL ORDER BY prefix experiment showed
a cold-cache advantage that disappeared on warm repetition and was not adopted.

## Environment and limits

Docker ran on ARM64 with 18 CPUs and 33,596,223,488 bytes of memory. SQL Server used an amd64
container under emulation. Oracle Free is limited to 2 CPUs, 2 GB RAM and 12 GB user data.
H2 was a durable TCP database with immediate writes, not embedded or in-memory H2. These are
not hardware-normalized database rankings. Detected versions were PostgreSQL 17.11, MySQL 9.2,
SQL Server 2022 (16.00.4265), Oracle 26ai Free (23.26.3), MariaDB 11.5.2 and H2 2.4.240.

All runs used fresh shipped schemas. Existing installations retain old indexes and column types
until migrated, so their write costs and access plans can differ. The library does not silently
drop user indexes or alter existing columns. See the [database upgrade guidance](../namastack-outbox-docs/docs/reference/database.md).
Normal ANALYZE/statistics collection was used; no extended statistics or benchmark-only indexes
were installed. Commit durability remained enabled. Database settings are documented in the
[README](README.md) and Docker Compose configuration.

MySQL long-run diagnostics showed increasing head-selection work as backlog grew; adding a
constant ORDER BY prefix did not fix it. SQL Server diagnostics showed log-flush waits and CPU
pressure alongside reads. High read-only rates therefore do not imply equal end-to-end rates.
The remaining bottlenecks include writes, commits, producer cost and read amplification under
backlog; completion batching on other engines needs its own investigation.

One Oracle setup failed before measurement because old dropped fixtures exhausted the Free
data quota. The dedicated disposable Oracle volume was reset, and the harness now purges its
own benchmark recycle bin and drops reset tables with PURGE. This was an infrastructure failure,
not a zero-throughput measurement.

## Verification and evidence

The complete JDBC suites passed: 396 tests on the read branch and 474 in the comparison checkout.
The final SQL Server retention index also passed a focused native schema/loading follow-up.
Tooling tests (14), Python tests (5), Kotlin formatting and shell syntax checks passed. A controlled
lock test confirmed that both worktrees refuse to initialize a database while the shared portable
benchmark lock is held. Ordering, retry barriers, completed-status compatibility, Unicode/large
payloads, schema upgrades and concurrent initialization are covered by native database tests.

Compact evidence, including exact counts, fixture metadata, versions, parameters and artifact
hashes, is committed in [results/2026-11-02.json](results/2026-11-02.json). Full logs remain in
local ignored `reports/` directories. The comparison checkout contains pre-existing uncommitted
experimental completion code: its HEAD alone does not reproduce those batching measurements.
The core processing scheduler is identical between checkouts. The comparison additionally logs
an optimistic bootstrap conflict at debug level rather than as a generic bootstrap error; both
paths return from the current bootstrap attempt. The ten query files and six schemas match.
Use the recorded artifact hashes and settings when comparing future implementations.
