# Requirements Review

Review date: 21 November 2026.

The application implements concurrent transaction generation, exact account
balance tracking, REST balance enquiries and minimum-count audit batching.
The default packing service establishes the exact minimum before returning a
packing. This review records the implementation and its acceptance evidence.

## Requirement coverage

| Required behaviour | Status | Evidence |
| --- | --- | --- |
| Java and Maven application | Verified | Java 21, Maven Wrapper, managed framework dependencies and an executable application JAR. |
| Credible library choices | Documented | README dependency assessment and primary documentation; packing uses the Java standard library. |
| Random transaction ID and signed amount | Verified | Immutable `Transaction`, UUID generation and whole-pence random magnitudes. |
| Magnitudes GBP 200 to GBP 500,000 inclusive | Verified | Factory boundary tests cover both endpoints for credits and debits. |
| Separate credit and debit threads | Verified | Dedicated named single-thread scheduled executors; real-worker tests observe both names. |
| Target 25 credits and 25 debits per second | Verified | Both tasks use 40 ms fixed-rate scheduling. Actual delivery follows execution and operating-system scheduling. |
| One shared account-service instance | Verified | Default singleton service scope and constructor injection into producers and controller. |
| Required service interface | Verified | `processTransaction(Transaction)` and `double retrieveBalance()` retained; exact decimal accessor added. |
| Accurate, concurrent balance calculations | Verified | Exact decimal accumulation, synchronized reads/updates, penny scenarios and 8,000 concurrent updates. |
| REST balance enquiry | Verified | `GET /api/balance`, numeric GBP balance and no-store headers; real-server contract tests. |
| Exactly 1,000 transactions per default submission | Verified | Complete-window collector, immutable snapshots, boundary/concurrency tests and configured pipeline checks. |
| One or more nonempty batches per submission | Verified | Immutable batches retain the actual transaction occurrences and independently validate capacity. |
| Absolute batch value at most GBP 1,000,000 | Verified | Whole-pence placement, independent model validation and exact-capacity/one-penny-over tests. |
| Minimum batch count | Verified | Valid lower bounds or complete iterative feasibility search establish the minimum; independent oracle comparisons and full-window infeasibility regressions. |
| Console submission summary | Verified | One JSON document per completed submission with numeric totals and transaction counts. |
| Scaling to 100,000 transactions | Demonstrated | Functional occurrence-preservation checks and the reproducible packing/account-to-JSON performance scenario. |
| Generic domain content | Checked | Application examples and domain documentation use fictitious account data. Standard dependency names and upstream references identify the libraries used. |
| Public source repository | Checked | Public repository with Maven source, tests, setup instructions and supporting design/performance documentation. |

Swagger UI, typed failures and consistent HTTP problem responses are also
implemented and covered by real-server and domain tests.

## Minimum-count argument

The initial packing is a feasible upper bound. Exact integer arithmetic supplies
a volume lower bound. Sorted-prefix cardinality bounds additionally account for
how many large items can share a batch. Matching either bound proves optimality.

Small submissions can be solved completely by subset dynamic programming.
Larger submissions use local improvement and deterministic pair lookahead to
obtain a better feasible candidate. These steps do not decide that a candidate
is minimal merely because improvement stops.

When necessary, `ExactAuditBatchPacker` tests candidate counts from the valid
lower bound upward. Its iterative traversal considers every non-equivalent
feasible assignment, pruning only interchangeable equal-load bins, previously
proved-infeasible states and capacity violations. Residual space smaller than
the smallest remaining item supplies an additional valid infeasibility test.
The first feasible count is minimal. If every count below the incumbent is
infeasible, the incumbent is minimal.

The cache retains only proved-infeasible states. Eviction affects repeated work,
not search completeness. Explicit interruption raises `AuditProcessingException`
and the dispatcher retains the submission. A successful default packing returns
`optimalityProven() == true`.

## Acceptance examples

- The six-item case GBP 440,000, 250,000, 200,000, 390,000, 340,000 and 270,000
  fits in two batches. Padding with 994 GBP 500,000 items gives an exact minimum
  of 499 batches for a complete 1,000-item submission.
- The extended complete-window case with 974 GBP 500,000 items, those six
  amounts and 20 GBP 200 items has an exact minimum of 489 batches.
- A full-window proof case contains 972 GBP 500,000 items, four GBP 450,000
  items, three GBP 60,000 items and 21 GBP 200 items. Its initial volume and
  cardinality bounds are 488. In 488 bins, the 976 large items force two per bin.
  Only the two possible 450,000/450,000 pairs have room for a 60,000 item, so the
  third such item cannot fit. Complete search proves that 489 bins are required.
- The seeded uniform 100,000-item submission reaches 24,969 batches, equal to
  its volume bound. Every original occurrence and exact absolute total is retained.
- An independent recursive assignment oracle checks production exact feasibility
  on 80 seeded small inputs, alongside existing known-optimum, subset-solver and
  bounded-repair comparisons.
- Direct iterative search handles 100,000 repeated transaction occurrences without
  transaction-depth recursion. Cancellation tests verify the typed exception and
  preserved interrupt status.

## Validation

Java 21 clean verification passes 154 automated tests with no failures, errors
or skips. The tests cover model invariants, decimal arithmetic, concurrency,
producer lifecycle, complete audit windows, bounded asynchronous hand-off,
minimum-count packing, occurrence preservation, JSON output, HTTP errors and
OpenAPI/Swagger contracts.

The [performance report](performance.md) contains current 1,000- and
100,000-transaction measurements, input definitions and reproduction commands.
The [demonstration guide](demonstration-guide.md) provides the build/run sequence,
and the [design walkthrough](design.md) explains the component boundaries and
minimum-count proof.

## Runtime model

State is held in memory for the lifetime of the process. Complete submissions
are processed on a dedicated audit worker; an incomplete window remains with the
collector until it fills. Queue rejection occurs before the completing account
update is committed. A processing failure retains the in-flight and queued
submissions for inspection, and a partially written console line cannot be
rolled back. These behaviours are explicit and covered by failure-path tests.

Exact search has exponential worst-case computational cost. Its traversal uses
O(N + B) arrays and a separately budgeted memo cache; it runs outside the account
monitor. The performance report measures the specified local workloads, including
all correctness and minimum-count checks, and identifies what each timing includes.
