# Performance and Packing Quality

## Reproduce the demonstration

Select JDK 21 and run from the repository root:

```sh
./mvnw -Dtest=AuditPerformanceScenario '-DargLine=-Xms256m -Xmx512m' test
```

This explicitly selects the opt-in scenario under `src/test/java`. Ordinary
`./mvnw clean verify` runs the correctness suite without timing it. The
demonstration uses existing test dependencies and adds no runtime libraries.

The generated files are:

- `target/audit-performance.csv`: timing and packing-quality results.
- `target/audit-performance-environment.txt`: runtime and sampling settings.

To change the sample count:

```sh
./mvnw -Dtest=AuditPerformanceScenario '-DargLine=-Xms256m -Xmx512m' \
  -Dtracker.performance.warmups=5 -Dtracker.performance.iterations=15 test
```

You can also run the scenario's test method in the IDE. Set its VM options to
`-Xms256m -Xmx512m` to use the same heap settings. Timing is descriptive: there is
no pass/fail latency threshold. A ten-second completion timeout catches a stalled
audit worker; it is not a performance target.

## Method

Each scenario runs at 1,000 and 100,000 transactions. Inputs are prepared before
timing, with deterministic IDs and alternating credit/debit signs:

| Scenario | Amount magnitude |
| --- | --- |
| `uniform` | Seeded whole-pence random values, GBP 200.00 through GBP 500,000.00 inclusive; seed 62831. |
| `minimum_amount` | Every transaction has magnitude GBP 200.00. |
| `maximum_amount` | Every transaction has magnitude GBP 500,000.00. |
| `repair_case` | All but six items have magnitude GBP 500,000; the final six are GBP 440,000, 250,000, 200,000, 390,000, 340,000 and 270,000. |

Every case has three warmup iterations followed by seven measured iterations.
Two elapsed-time measurements use `System.nanoTime()`:

1. **Packing:** the production packing service receives an existing immutable
   submission. Timing includes weighted-view construction, sorting, tree-based
   placement, immutable batch construction, lower-bound calculation and the
   default service's bounded exact repair when applicable.
2. **Pipeline:** a single caller supplies a burst of transactions to the real
   synchronized account service. Timing includes balance arithmetic, audit
   collection, snapshot creation, queue hand-off, background packing, DTO mapping
   and JSON serialization to an in-memory UTF-8 stream. It ends after the output
   handler completes, including its normal diagnostic logging.

Each pipeline sample uses a fresh account, collector and dispatcher. Worker and
service creation happen before timing; worker shutdown happens afterward. There
is one complete submission per sample, so this is not a sustained queue-pressure
test. Producer scheduling, random ID generation, application startup, HTTP
requests and terminal output of the JSON payload are excluded. The production JSON handler is
used directly, but its output is captured in memory rather than printed to a
terminal. Larger JSON documents still incur allocation, encoding and copying.

Correctness checks run outside timed regions. Packing checks preserve the
multiset of original transactions, exact absolute totals and every batch limit.
Pipeline checks parse the actual JSON, verify transaction counts, capacity and
absolute totals, and compare the final exact account balance with the input sum.

P50 and P95 use nearest-rank selection. With seven samples, P50 is the fourth
sorted sample and P95 is the largest. These are local observations from one JVM,
not statistically rigorous microbenchmarks or production service-level promises.
JIT compilation, garbage collection, scheduling, scenario order and other machine
activity can affect the samples. Packing and pipeline percentiles come from
separate measurements and should not be subtracted as a stage breakdown.

## Recorded results

Run date: 17 November 2026. Runtime: Java 21.0.10, x86_64, eight available logical
processors, maximum heap 512 MiB. The run completed all eight cases successfully.
The default `OptimizingAuditBatchingService` uses best-fit decreasing followed by
bounded exact repair. The earlier six-case CSV for the unmodified best-fit
decreasing implementation is retained as
[performance-before-optimisation.csv](performance-before-optimisation.csv).
These separate runs are context rather than a controlled statistical comparison.
CSV results and environment settings are retained alongside this report in
[performance-results.csv](performance-results.csv) and
[performance-environment.txt](performance-environment.txt).

| Scenario | Transactions | Packing P50 ms | Packing P95 ms | Pipeline P50 ms | Pipeline P95 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Uniform | 1,000 | 2.844 | 8.787 | 4.141 | 4.575 |
| Uniform | 100,000 | 89.090 | 92.167 | 91.181 | 108.758 |
| Minimum amount | 1,000 | 0.163 | 0.184 | 0.647 | 0.757 |
| Minimum amount | 100,000 | 14.195 | 31.575 | 22.051 | 28.018 |
| Maximum amount | 1,000 | 0.278 | 0.667 | 1.386 | 3.693 |
| Maximum amount | 100,000 | 19.170 | 28.806 | 45.592 | 75.732 |
| Repair case | 1,000 | 0.605 | 0.886 | 1.325 | 2.336 |
| Repair case | 100,000 | 23.584 | 26.881 | 39.180 | 43.376 |

Some cases have visible tail samples. Seven observations are too few to
characterize or attribute them. The demonstration shows that the
larger window completes with the configured heap cap; it does not measure peak
heap usage or establish an asymptotic running-time guarantee experimentally.

## Packing quality

The comparison baseline fills one batch at a time in original arrival order,
opening a new batch when the next transaction cannot fit. It never revisits an
earlier batch. This simple next-fit baseline is not an exact optimizer.

| Scenario | Transactions | Packed batches | Lower bound | Above bound | Optimality proven | Fill % | Arrival next-fit batches |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| Uniform | 1,000 | 248 | 248 | 0 | Yes | 99.768 | 290 |
| Uniform | 100,000 | 24,970 | 24,969 | 1 | No | 99.995 | 29,800 |
| Minimum amount | 1,000 | 1 | 1 | 0 | Yes | 20.000 | 1 |
| Minimum amount | 100,000 | 20 | 20 | 0 | Yes | 100.000 | 20 |
| Maximum amount | 1,000 | 500 | 500 | 0 | Yes | 100.000 | 500 |
| Maximum amount | 100,000 | 50,000 | 50,000 | 0 | Yes | 100.000 | 50,000 |
| Repair case | 1,000 | 499 | 499 | 0 | Yes | 99.978 | 499 |
| Repair case | 100,000 | 49,999 | 49,999 | 0 | Yes | 100.000 | 49,999 |

The CSV additionally reports `initial_bfd_batches` and `batches_saved`. The repair
case improves from 500 to 499 batches at 1,000 items, and from 50,000 to 49,999 at
100,000 items. Both reach their lower bound and are therefore proven optimal.
The other recorded inputs retain their original batch counts. Fill percentages
are rounded to three decimal places.

`fill_percent` is the total absolute value divided by the capacity of all emitted
batches. A partially filled batch is unavoidable for the minimum-amount 1,000-item
case: all GBP 200,000 fits into one batch, which is already the minimum count.

`excess_over_bound` compares the result with a lower bound, not a known optimum.
For the 100,000-item uniform case, the true optimum is either 24,969 or 24,970;
this run does not determine which. High utilization alone is not proof of
optimality. The bounded strategy remains heuristic on larger submissions whenever
its global bound is not reached.

The largest captured JSON document in this run was 3,350,029 UTF-8 bytes for the
100,000-item maximum-amount case. Output volume and terminal throughput can matter
even when packing is fast. JSON byte counts include the final platform newline.

## Independent exact checks

`AuditPackingQualityTests` compares production packing with independent exhaustive
bin assignments on four known cases and 32 seeded small cases. The test oracle
assigns each item to every feasible bin, pruning symmetric equal-load choices and
branches that cannot improve the best count. It is limited to 16 items because
the search is exponential. Production uses a different subset dynamic program
and never invokes the test oracle.

These tests check that lower bounds do not exceed the exact minimum, feasible
counts do not fall below it, and every proven-optimal claim matches it. In
particular:

- Magnitudes `[440000, 250000, 200000, 390000, 340000, 270000]` have an exact
  minimum of two batches; the default service now reaches it. Padding the case
  to 1,000 items with GBP 500,000 transactions also reaches its minimum of 499.
- Five magnitudes of GBP 340,000 need three batches, despite a volume lower bound
  of two. Whole-submission exact solving tightens the reported bound to three.

A separate 1,000-item case still emits 490 batches when 489 suffice because an
underfilled repair candidate exceeds the 18-item search budget. See the
[requirements review](requirements-review.md#cost-minimisation-status). This
remaining limitation is tested without falsely claiming a global optimum.

## Scaling limits

Initial packing uses O(N log N) time and O(N) additional memory by construction.
Repair uses O(K * 2^K) time and O(2^K) state space with K capped at 18, roughly
3.25 MiB for state arrays at that limit. A large submission can invoke at most
two such local searches, each on up to six underfilled batches; it never runs
the exponential solver on the entire large window. Linear volume checks skip
candidate sorting or exact search when the permitted group cannot reduce count.
Collection
uses O(N) snapshot copying when a window fills, inside the account operation.
Packing and serialization run on the separate audit worker. A larger configured
window therefore increases both audit work and the boundary-copy cost.

The normal target generation rate is 50 transactions per second. Collecting
100,000 transactions at that rate takes about 2,000 seconds before packing starts;
the burst-input demonstration avoids that wait and does not change production
rates. With the default four queued windows, one in-flight window and one partial
window, a 100,000-item configuration can retain references to roughly 600,000
transactions, plus packing and JSON allocation. No peak-memory claim is made.

The demonstration does not cover sustained overload, queue rejection under load,
HTTP latency under contention, durable recovery or an exact optimum for arbitrary
large inputs. Dedicated correctness tests cover the bounded queue and failure
policies. A guaranteed minimum batch count still requires a different optimization
strategy with separately assessed computational costs.
