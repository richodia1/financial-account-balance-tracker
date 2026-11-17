# Requirements Review

Review date: 17 November 2026. Code baseline: `42a9588`, plus the default-window
packing-quality regression test added with this review.

The main processing, balance-enquiry and audit-output paths are implemented and
tested. **A guaranteed minimum batch count remains an open requirement.** The
current best-fit decreasing strategy can emit more batches than necessary, even
for a valid 1,000-transaction window. Passing the current test suite does not
establish full compliance with a strict minimum-cost requirement.

## Behavior and evidence

Paths below are relative to `src/main/java/com/example/financial_account_balance_tracker`
or the matching package beneath `src/test/java`.

| Required behavior | Status | Implementation and evidence |
| --- | --- | --- |
| Java and Maven application | Verified | Java 21, Maven Wrapper, managed dependencies in `pom.xml`; clean verification produces an executable JAR. |
| Credible library choices | Documented | The README explains the framework and test dependencies, managed versions and primary documentation. |
| Signed `Transaction` with random ID | Verified | `model.Transaction` and `producer.RandomTransactionFactory`; model and generator tests cover identity, signs and validation. |
| Random magnitude GBP 200 to GBP 500,000 inclusive | Verified | Generator uses whole pennies with an inclusive upper boundary; deterministic generator tests exercise both endpoints. |
| Separate dedicated credit and debit threads | Verified | `producer.TransactionProducer` owns one scheduled executor per type; tests observe both real thread names. |
| Target 25 credits and 25 debits per second | Verified as scheduling policy | Each executor uses a 40 ms fixed-rate period. Tests verify scheduling arguments. Operating-system pauses or slow processing can change observed wall-clock delivery. |
| One shared account instance | Verified | Default service scope provides one account service per application context. There is no account identifier or per-request account instance. |
| Required balance-service interface | Verified | `service.BankAccountService` retains `processTransaction(Transaction)` and `double retrieveBalance()`, with an exact decimal accessor added. |
| Accurate calculations and concurrent enquiries | Verified | Exact decimal arithmetic and one synchronized account monitor; tests include pennies, negative balances, stable snapshots and 8,000 concurrent updates. |
| REST balance enquiry | Verified | `controller.BalanceController`: `GET /api/balance`, JSON numeric amount, GBP currency and `Cache-Control: no-store`. Real-server tests cover successful and unsupported requests. |
| Exactly 1,000 transactions per default submission | Verified | Collector and dispatcher enforce complete windows; tests cover 999, 1,000, 1,001, multiple windows and shutdown with an incomplete window. |
| One or more nonempty batches per submission | Verified | Packing retains actual transaction assignments in immutable batches; preservation tests cover repeated occurrences and large windows. |
| Absolute value per batch at most GBP 1,000,000 | Verified | Integer-penny placement plus independent model validation. Opposite signs consume capacity; tests cover an exact fit and one penny over. |
| Minimum number of batches | Open gap | Best-fit decreasing is heuristic. The default-window counterexample below proves that the current algorithm can miss the minimum. |
| Console submission summary | Verified | `audit.JsonAuditSubmissionHandler` prints one valid JSON line per complete submission, with numeric totals in pounds and transaction counts. Contract tests parse the document. |
| Demonstrate scaling to 100,000 transactions | Demonstrated locally | Functional tests and the opt-in performance scenario cover 100,000-item windows, including actual account-to-JSON processing. The performance report defines exclusions and measurement limits. |
| Generic domain content | Checked | Domain code and project prose use generic account concepts and contain no actual customer, employer or individual names. Standard dependency names and upstream notices remain intact. |
| Public source availability | Checked on review date | Repository metadata was readable without credentials and reported public visibility. This is an external property, not an application guarantee. |

## Open cost-minimisation finding

The production service is `BestFitDecreasingAuditBatchingService`. Consider a
1,000-transaction submission containing:

- 994 transactions of magnitude GBP 500,000.
- Six transactions of magnitudes GBP 440,000, GBP 250,000, GBP 200,000,
  GBP 390,000, GBP 340,000 and GBP 270,000.

All magnitudes are inside the normal generation range. Credit and debit signs
can alternate because audit capacity counts magnitudes.

The first 994 transactions fill 497 batches. The remaining six can be packed into
two batches with totals GBP 980,000 and GBP 910,000. That gives **499 batches**,
which reaches the total-volume lower bound and therefore proves the minimum.

The current heuristic instead places the six remainder transactions into three
batches, producing **500 batches** overall. Its diagnostic correctly reports
optimality as unproven, but that diagnostic does not repair the extra batch.

`AuditPackingQualityTests.exposesTheKnownCostGapInAValidOneThousandTransactionWindow`
constructs both assignments and checks this result. This test documents a known
limitation; it does not assert that the minimum-cost requirement has been met.

Resolving the requirement needs a stronger packing strategy and evidence of its
optimality or a clearly agreed optimization policy. A bounded local improvement
may reduce cost, but it must not be described as an exact global optimizer without
a proof. Large-window performance must be reassessed after any packing change.

## Operational assumptions

- All balances, partial windows, queued windows and failed work are in memory.
  Restarting resets them; there is no durable recovery.
- An incomplete window is retained until it fills and is not emitted at shutdown.
- A full audit queue rejects the completing transaction before committing its
  balance change. The existing partial window remains intact. Producer processing
  failures stop both generators.
- A failed audit handler retains its in-flight and queued windows in memory.
  New complete windows are rejected. There is no automatic downstream retry;
  subsequent transactions can enter a partial window before it next fills.
- Console delivery has no remote acknowledgement or rollback. A partial write
  cannot be undone; write errors stop the audit worker.
- Negative balances are allowed. Repeated IDs are processed as repeated calls,
  rather than used as deduplication keys.
- The generic model accepts nonzero whole-pence amounts outside the generator's
  range. An individual magnitude above the batch capacity fails later packing;
  normal generated transactions cannot trigger that case.
- The 100,000-item timing demonstration excludes rate-limited generation, HTTP
  traffic, terminal output of the JSON payload and sustained queue pressure.

These are visible design limits. They should not be presented as durability,
delivery, real-time scheduling or global-optimization guarantees.

## Validation record

The review branch adds a demonstration guide and a regression test for the known
cost gap. Checks completed on the review date:

- A Java 21 `./mvnw clean verify` completed successfully: 104 tests, no failures,
  errors or skips, and an executable JAR produced.
- The packaged JAR ran with normal producer and audit settings on a random local
  port. Two consecutive JSON submissions each contained exactly 1,000
  transactions, with every batch at or below the absolute-value limit. The
  observed batch counts were 250 and 247; random generation makes those counts
  illustrative rather than repeatable.
- HTTP checks returned 200 for a balance enquiry, 404 at the root, 405 for POST
  and 406 for a request accepting only HTML. The successful response used JSON,
  GBP currency and a no-store cache header.
- A fresh packaged instance with producers disabled returned GBP 0.00 across
  repeated enquiries and emitted no audit submissions.
- Both owned application processes shut down cleanly. The live instance emitted
  no additional partial submission during shutdown.
- Local documentation links and whitespace checks passed; the domain/prose
  content scan found no identifying customer or individual references.

The earlier performance results are retained unchanged because this branch does
not alter the production algorithm. See the [performance report](performance.md)
and [demonstration guide](demonstration-guide.md).
