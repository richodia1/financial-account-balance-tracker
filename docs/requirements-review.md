# Requirements Review

Review date: 17 November 2026. Initial review baseline: `00509fc`. Updated here
for exact small-set solving, bounded backtracking and the associated checks.

The main processing, balance-enquiry and audit-output paths are implemented and
tested. **A guaranteed minimum batch count remains an open requirement.** The
default service repairs both documented 1,000-item cost gaps, but its bounded
search cannot guarantee a minimum for every possible input. Passing the test suite does not
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
| Minimum number of batches | Known gaps repaired; global guarantee open | Exact small-set solving and bounded local search fix both documented default-window counterexamples. Search limits can still leave opportunities unexplored. |
| Console submission summary | Verified | `audit.JsonAuditSubmissionHandler` prints one valid JSON line per complete submission, with numeric totals in pounds and transaction counts. Contract tests parse the document. |
| Demonstrate scaling to 100,000 transactions | Demonstrated locally | Functional tests and the opt-in performance scenario cover 100,000-item windows, including actual account-to-JSON processing. The performance report defines exclusions and measurement limits. |
| Generic domain content | Checked | Domain code and project prose use generic account concepts and contain no actual customer, employer or individual names. Standard dependency names and upstream notices remain intact. |
| Public source availability | Checked on review date | Repository metadata was readable without credentials and reported public visibility. This is an external property, not an application guarantee. |

## Cost-minimisation status

The production service is `OptimizingAuditBatchingService`, which starts with
best-fit decreasing and attempts bounded exact repair. Consider a
1,000-transaction submission containing:

- 994 transactions of magnitude GBP 500,000.
- Six transactions of magnitudes GBP 440,000, GBP 250,000, GBP 200,000,
  GBP 390,000, GBP 340,000 and GBP 270,000.

All magnitudes are inside the normal generation range. Credit and debit signs
can alternate because audit capacity counts magnitudes.

The first 994 transactions fill 497 batches. The remaining six can be packed into
two batches with totals GBP 980,000 and GBP 910,000. That gives **499 batches**,
which reaches the total-volume lower bound and therefore proves the minimum.

Best-fit decreasing alone placed the six remainder transactions into three
batches, producing **500 batches** overall. The default service now solves that
six-item repair exactly, producing **499 batches** and proving the minimum.

`AuditPackingQualityTests.repairsTheKnownCostGapInAValidOneThousandTransactionWindow`
checks the improvement, and a configuration test verifies the 499-batch JSON
output through the default collection and dispatch pipeline.

Whole submissions of at most 18 items are solved exactly when the initial bound
has not already proved optimality. Larger windows get at most two local passes,
each considering up to six underfilled batches and 128 transactions in total.
Sets of at most 18 use the exact subset solver; larger selected sets use
backtracking with a 100,000-node budget and at most five target bins.
An exact local solution does not tighten the global lower bound by itself.

The second repaired 1,000-item construction is:

- 974 items of magnitude GBP 500,000.
- The same six remainder amounts listed above.
- 20 items of magnitude GBP 200.

The first 974 items fill 487 batches. The remainder can fit into batches of
GBP 980,000 and GBP 914,000, giving an exact minimum of **489 batches**. The
earlier 18-item strategy emitted **490** because an underfilled batch had 21
transactions. The bounded repacker now finds a valid two-batch remainder
assignment and reaches **489**, proving optimality by the global volume bound.
Its 100,000-item version similarly improves from 49,990 to **49,989**.

Both outcomes are regression-tested through the default service; default
collection-to-JSON configuration tests also cover the two 1,000-item cases.
If the bounded search exhausts its budget, the original valid packing is retained
and the optimality flag stays conservative. A deterministic test forces this
fallback and verifies that no transaction occurrence is lost.

The seeded random 100,000-item case still has an unproven result: the current
packing has 24,970 batches against a lower bound of 24,969. No minimum is claimed
there. The item, group, pass and node limits prevent exhaustive global search.

The implementation now reduces the known cost gap without unbounded exponential
work. An exact global optimizer is still required to guarantee a minimum for
every arbitrary large input, with runtime and resource costs assessed separately.

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

The initial review exposed the original cost gap. Validation after adding exact
small-set solving and bounded backtracking:

- Java 21 clean verification and follow-up verification completed successfully:
  127 tests, no failures,
  errors or skips, and an executable JAR produced.
- Default configured pipeline regressions produced 499 and 489 batches for the
  two repaired 1,000-item cases, preserving each complete submission count.
- The rebuilt packaged JAR ran with normal producer and audit settings on a
  random local port. Its observed JSON submission contained exactly 1,000
  transactions in 255 batches, all within the absolute-value limit. The balance
  API returned HTTP 200 with GBP currency, and the owned process shut down cleanly.
  Random generation makes that observed batch count illustrative, not repeatable.
- All ten opt-in performance scenarios completed with a 512 MiB maximum heap,
  including repaired cases at 1,000 and 100,000 transactions. Current CSV results
  match the generated measurement artifacts.
- Real-server regression tests cover JSON balance responses, exact decimal
  values, no-store headers, read-only behavior, POST 405 and HTML-only Accept 406.
  Initial-review packaged checks additionally verified root 404, a fresh balance
  of GBP 0.00 with producers disabled, and no incomplete submission on shutdown.
- Local documentation links and whitespace checks passed; the domain/prose
  content scan found no identifying customer or individual references.

Performance was remeasured using the actual default service; earlier CSV results
remain available for context. See the [performance report](performance.md) and
[demonstration guide](demonstration-guide.md).
