# Performance and Minimum-Count Packing

## Reproduce the demonstration

Select JDK 21 and run from the repository root:

```sh
./mvnw -Dtest=AuditPerformanceScenario '-DargLine=-Xms256m -Xmx512m' test
```

The opt-in scenario is separate from the normal correctness suite. It writes
`target/audit-performance.csv` and `target/audit-performance-environment.txt`.
Current results are retained in [performance-results.csv](performance-results.csv)
and [performance-environment.txt](performance-environment.txt).

Optional sample settings:

```sh
./mvnw -Dtest=AuditPerformanceScenario '-DargLine=-Xms256m -Xmx512m' \
  -Dtracker.performance.warmups=5 -Dtracker.performance.iterations=15 test
```

## Workloads and method

Every scenario runs at 1,000 and 100,000 transactions. Input generation occurs
before timing, using deterministic IDs and alternating credit/debit signs.

| Scenario | Magnitudes |
| --- | --- |
| Uniform | Seeded whole-pence random values from GBP 200 through GBP 500,000 inclusive; seed 62831. |
| Minimum amount | Every magnitude is GBP 200. |
| Maximum amount | Every magnitude is GBP 500,000. |
| Six-item fixture | All but six magnitudes are GBP 500,000; the final six are GBP 440,000, 250,000, 200,000, 390,000, 340,000 and 270,000. |
| Extended fixture | All but 26 magnitudes are GBP 500,000; the same six values are followed by 20 GBP 200 items. |

There are three warmup iterations and seven measured iterations per scenario and
size. Two measurements use `System.nanoTime()`:

1. **Packing:** the actual default service receives an existing immutable
   submission. Timing includes validation, weighted views, sorting, candidate
   construction, bound calculation, local repair, pair lookahead and complete
   certification whenever those stages are needed.
2. **Pipeline:** a single caller submits a burst through the real synchronized
   account service, collector, bounded hand-off, background optimiser, output
   DTO mapping and JSON serialization to an in-memory UTF-8 stream. Timing ends
   when the output handler completes, including its diagnostic logging.

Each pipeline sample uses a fresh account, collector and dispatcher. Their
creation and worker startup happen before timing; worker shutdown happens after.
Producer pacing, UUID generation, application startup, HTTP traffic and printing
the JSON payload to a terminal are excluded. The production JSON handler is
used directly and its payload is captured in memory.

Correctness checks outside timed regions verify the multiset of original
transaction occurrences, exact absolute totals, every batch capacity and the
final account balance. The packing scenario also requires a proven minimum on
every result. Pipeline JSON is parsed to verify the complete window and totals.
A ten-second completion timeout detects a stalled worker; it is not a latency SLA.

P50 and P95 use nearest-rank selection. With seven samples, they are the fourth
and largest sorted observations respectively. These are local observations,
not a production load test or a statistically rigorous microbenchmark. JIT,
garbage collection, scheduling and other machine activity affect timings.
Packing and pipeline percentiles are independent measurements and must not be
subtracted to infer individual stage costs.

## Recorded results

Run date: 21 November 2026. Java 21.0.10, x86_64, eight available logical
processors and a 512 MiB maximum heap. All ten workloads completed successfully,
and every packing reached its certified minimum count.

| Scenario | Transactions | Packing P50 ms | Packing P95 ms | Pipeline P50 ms | Pipeline P95 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Uniform | 1,000 | 2.786 | 3.427 | 3.805 | 10.406 |
| Uniform | 100,000 | 2822.975 | 3175.674 | 2724.975 | 2935.282 |
| Minimum amount | 1,000 | 0.271 | 0.476 | 0.937 | 1.490 |
| Minimum amount | 100,000 | 14.684 | 23.392 | 18.400 | 25.201 |
| Maximum amount | 1,000 | 0.263 | 1.129 | 1.086 | 2.244 |
| Maximum amount | 100,000 | 21.517 | 26.516 | 39.145 | 105.015 |
| Six-item fixture | 1,000 | 0.659 | 1.031 | 1.376 | 2.206 |
| Six-item fixture | 100,000 | 26.460 | 36.045 | 47.153 | 56.530 |
| Extended fixture | 1,000 | 0.427 | 0.931 | 1.148 | 2.147 |
| Extended fixture | 100,000 | 27.844 | 33.944 | 48.139 | 53.409 |

The uniform 100,000-item case uses pair lookahead to construct 24,969 batches,
matching its volume lower bound. Its extra candidate construction is included
in both measurements. The other recorded scenarios establish their optima using
initial bounds or smaller exact/local improvements.

## Packing quality

| Scenario | Transactions | Minimum batches | Certified lower bound | Optimality proven | Fill % | Initial best-fit batches | Arrival next-fit batches |
| --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| Uniform | 1,000 | 248 | 248 | Yes | 99.768 | 248 | 290 |
| Uniform | 100,000 | 24,969 | 24,969 | Yes | 99.999 | 24,970 | 29,800 |
| Minimum amount | 1,000 | 1 | 1 | Yes | 20.000 | 1 | 1 |
| Minimum amount | 100,000 | 20 | 20 | Yes | 100.000 | 20 | 20 |
| Maximum amount | 1,000 | 500 | 500 | Yes | 100.000 | 500 | 500 |
| Maximum amount | 100,000 | 50,000 | 50,000 | Yes | 100.000 | 50,000 | 50,000 |
| Six-item fixture | 1,000 | 499 | 499 | Yes | 99.978 | 500 | 499 |
| Six-item fixture | 100,000 | 49,999 | 49,999 | Yes | 100.000 | 50,000 | 49,999 |
| Extended fixture | 1,000 | 489 | 489 | Yes | 99.978 | 490 | 490 |
| Extended fixture | 100,000 | 49,989 | 49,989 | Yes | 100.000 | 49,990 | 49,990 |

Fill is total absolute value divided by aggregate capacity. For 1,000 minimum
amounts, GBP 200,000 fits into one batch, so 20% fill is already minimum-count.
Percentages are rounded to three decimal places. The arrival-order next-fit
column is a simple comparison that opens a new batch when the next transaction
does not fit; it is not used to establish a minimum.

All current default results have `optimality_proven=true` and
`excess_over_bound=0`. The initial best-fit column identifies the count before
optimisation. The largest captured JSON document is 3,350,029 UTF-8 bytes for the
100,000-item maximum-amount scenario, including the platform newline.

## Independent exact verification

An independent recursive assignment oracle compares the complete iterative
solver against 80 seeded small cases, checking both feasibility at the exact
minimum and infeasibility below it. Existing known-optimum, subset-solver and
bounded-repair tests provide separate comparisons. Capacity and occurrence
preservation are checked independently of the optimiser's internal state.

A complete 1,000-item proof fixture contains 972 GBP 500,000 items, four GBP
450,000 items, three GBP 60,000 items and 21 GBP 200 items. Its initial bounds
are 488, but a 488-bin packing would force all 976 large items into pairs. Only
two 450,000/450,000 pairs have space for a 60,000 item, so the third cannot fit.
The complete solver establishes the minimum of 489, including when local repair
is disabled. This checks a proof obtained by ruling out a smaller candidate,
in addition to proofs obtained by attaining a lower bound.

Direct exact search also handles 100,000 repeated occurrences without recursive
stack depth. Interruption raises a typed processing failure and preserves the
interrupt flag; provisional assignments do not become console submissions.

## Scaling characteristics

Initial best-fit packing and fixed-probe pair lookahead use O(N log N) time and
O(N) additional space. Small-set dynamic programming and bounded local repair
supply candidate improvements. Their limits apply only to those accelerators.
Complete certification continues until the minimum is established, using
mathematically valid pruning and canonical infeasible-state memoization.

Exact bin packing has exponential worst-case search cost. Traversal arrays use
O(N + B) space for N transactions and B candidate batches. The memo cache has a
16 MiB entry-accounting budget; eviction permits recomputation and preserves
completeness. The measurements above demonstrate the actual configured service
on the specified workloads with a 512 MiB heap; they do not measure peak heap
or guarantee identical timings for arbitrary input distributions.

Collection copies a complete window while processing its boundary transaction.
Packing and serialization run on a separate audit worker. At the target rate of
50 transactions per second, collecting 100,000 items takes approximately 2,000
seconds; the burst-input demonstration isolates processing from that wait.

Four queued windows, one in-flight window and one partial window can retain
references to roughly 600,000 transactions with a 100,000-item setting, plus
packing and JSON allocations. This demonstration processes one complete window
per pipeline sample. It excludes sustained queue pressure, concurrent HTTP
latency and external transport/storage. Dedicated correctness tests separately
cover queue rejection, failure retention and exact account arithmetic.
