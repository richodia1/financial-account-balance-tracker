# Financial Account Balance Tracker

A Java application for tracking an account balance and preparing value-limited
transaction audit batches.

## Current status

The application builds, tests and starts as an HTTP service. It implements an
immutable transaction model, a thread-safe in-memory balance service and a REST
balance endpoint. Dedicated credit and debit producers run automatically. Accepted
transactions are collected into complete audit windows and handed to a dedicated
worker. The worker packs those transactions into batches capped at GBP 1,000,000
of absolute value and prints a JSON summary. Packing uses best-fit decreasing,
with a lower-bound diagnostic that distinguishes proven optimal results from
heuristic results. An opt-in performance demonstration measures packing and the
account-to-JSON pipeline for 1,000- and 100,000-transaction submissions. See the
[performance and packing-quality report](docs/performance.md) for results and
remaining limits.

See the [requirements review](docs/requirements-review.md) for implementation
evidence and the open minimum-batch-count requirement. The
[demonstration guide](docs/demonstration-guide.md) provides a build/run sequence,
IDE settings and a code walkthrough.

## Package organization

All application packages sit beneath `com.example.financial_account_balance_tracker`:

```text
FinancialAccountBalanceTrackerApplication.java
audit/
    AsyncAuditDispatcher.java
    AuditSubmissionHandler.java
    JsonAuditSubmissionHandler.java
config/
    AuditConfiguration.java
    ProducerConfiguration.java
controller/
    BalanceController.java
dto/
    AuditBatchSummary.java
    AuditSubmissionOutput.java
    AuditSubmissionSummary.java
    BalanceResponse.java
enums/
    TransactionType.java
model/
    AuditBatch.java
    AuditBatchingResult.java
    AuditSubmission.java
    Transaction.java
producer/
    RandomTransactionFactory.java
    TransactionProducer.java
service/
    AuditBatchingService.java
    BankAccountService.java
    TransactionAuditService.java
    impl/
        BestFitDecreasingAuditBatchingService.java
        InMemoryBankAccountService.java
        InMemoryTransactionAuditService.java
```

| Package | Responsibility |
| --- | --- |
| `audit` | Bounded hand-off, background processing and console JSON output. |
| `config` | Component wiring, producer activation and audit window/queue configuration. |
| `controller` | HTTP routing, response status, headers and response assembly. |
| `dto` | Data transferred across the HTTP and console output boundaries. |
| `enums` | Named business classifications such as `CREDIT` and `DEBIT`. |
| `model` | Internal business data and its invariants, including valid signed amounts. |
| `producer` | Random transaction creation, scheduling and worker lifecycle. |
| `service` | Contracts for account processing, audit collection and batch packing. |
| `service.impl` | In-memory balance/collection and the batch-packing algorithm. |

Tests mirror these packages under `src/test/java`. See the
[design walkthrough](docs/design.md) for the request flow, transaction flow and
the reasoning behind the boundaries.

## Balance model

- `Transaction` contains a UUID and a signed GBP amount. Credits are positive;
  debits are negative. IDs and amounts must be present, and zero-value
  transactions are rejected.
- `Transaction.type()` derives `TransactionType.CREDIT` or `TransactionType.DEBIT`
  from the amount's sign. The type is not stored separately from the amount.
- Amounts are normalized to two decimal places. Fractional pennies are rejected
  instead of rounded silently. Decimal amounts should be constructed from
  decimal strings or whole-pence values rather than binary floating-point input.
- `BankAccountService` retains `processTransaction(Transaction)` and
  `double retrieveBalance()`. Its additional `retrieveExactBalance()` method
  preserves decimal precision for callers, including the REST endpoint.
- `InMemoryBankAccountService` starts at GBP 0.00 and uses exact `BigDecimal`
  addition. The required `double` method converts only the returned snapshot;
  floating-point values are never used to accumulate the balance.
- Balance updates and exact reads synchronize on the same service instance.
  Each operation takes effect atomically, with updates visible to subsequent
  readers. The framework creates one service instance by default.
- Processing prepares the next balance, records the transaction for audit, then
  commits that balance. A rejected completed-window hand-off leaves the balance
  and the existing partial audit window unchanged.
- Debits may take the balance below zero. No overdraft restriction is applied.
  Each processing call applies its transaction; IDs are not deduplication keys.
- The producer enforces the GBP 200 to GBP 500,000 generation range. The
  domain model and service accept any nonzero amount representable in whole
  pence, making the calculation logic independent of the generator's limits.
- State is held in memory and is lost on application restart.

## Prerequisites

- JDK 21, with `JAVA_HOME` pointing to that installation.
- An internet connection for the first build to download Maven and dependencies.

The checked-in Maven Wrapper selects Maven 3.10.1. A separate Maven installation
is not required.

Check the build environment:

```sh
java -version
./mvnw --version
```

Both commands should report Java 21. On macOS, a terminal using a different JDK
can select an installed JDK 21 for the current session:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build and test

From the repository root:

```sh
./mvnw clean verify
```

This runs the automated tests and creates the executable application JAR in
`target/`. Tests cover transaction validation, signed amounts, exact penny
calculations, negative balances, immutable balance snapshots and concurrent
credits, debits and enquiries. HTTP integration tests start a real server on a
random available port and exercise the endpoint against the real account service.
They verify JSON decimal precision, live balance updates, read-only enquiries,
cache-control headers and unsupported requests. A context-loading test verifies
that the application can initialize its configuration without an external database.
Producer tests cover inclusive amount boundaries, signed generation, the 40 ms
scheduling period, dedicated worker threads, shutdown and failure handling.
Configuration tests cover enabled and disabled generation. Balance API tests
disable automatic generation so their account state stays deterministic.
Audit tests cover the 999/1000/1001 boundaries, immutable snapshots, ordered
windows, rejected hand-offs, bounded queues and worker failure retention. The
concurrent account test verifies that all 8,000 accepted transactions appear
exactly once across eight submissions. A functional test also collects a
100,000-transaction window. A configured integration test verifies that a slow
audit handler does not block account processing or balance enquiries.
Packing tests verify absolute-value limits, exact-capacity and one-penny-over
boundaries, repeated transaction occurrences, deterministic ordering, lower
bounds and known heuristic limitations. Seeded 1,000- and 100,000-transaction
inputs verify that each transaction and the exact absolute total are preserved.
Output tests parse the JSON document and exercise console-write failure handling;
a configuration test runs the default collection-to-JSON pipeline.
Additional quality tests compare small inputs against an independent exact
subset solver, checking lower bounds and optimality claims.

### Run the performance demonstration

With JDK 21 selected, run this separately from the normal test suite:

```sh
./mvnw -Dtest=AuditPerformanceScenario '-DargLine=-Xms256m -Xmx512m' test
```

It uses three warmup iterations and seven measured iterations per scenario and
size. Timing results are written to `target/audit-performance.csv`; environment
details are written to `target/audit-performance-environment.txt`. You can also
run the scenario's test method directly in the IDE.

The scenario class intentionally does not match the default test naming pattern,
so ordinary `./mvnw clean verify` runs correctness tests without collecting timing
samples. No latency threshold is enforced. See the
[methodology and recorded results](docs/performance.md) before interpreting the
measurements; JSON payload output to a terminal and producer pacing are excluded.

## Run

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar
```

The HTTP server listens on port 8081 by default. Stop it with Ctrl+C.

If that port is occupied, choose another port with the `SERVER_PORT` environment
variable or a command-line argument, for example:

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar --server.port=8082
```

Alternatively, run directly from source:

```sh
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw` and set `JAVA_HOME` to a JDK 21
installation.

## Transaction generation

Generation starts automatically with the application:

- `credit-producer` is a dedicated platform thread producing positive amounts.
- `debit-producer` is a separate dedicated platform thread producing negative amounts.
- Each has its own single-thread scheduled executor with a fixed-rate period of
  40 ms: a target of 25 transactions per second per producer, 50 in total.
- Amount magnitudes are random whole-pence values from GBP 200.00 through
  GBP 500,000.00 inclusive. Transaction IDs are random UUIDs.
- Each generated transaction goes through `BankAccountService.processTransaction`.

Fixed-rate scheduling provides the target cadence; it is not a hard real-time
guarantee under operating-system pauses or slow processing. A producer does not
overlap its own executions. The two producers may call the account service
concurrently.

The startup log reports the configured credit and debit rates. If generation or
processing throws a runtime exception, both producers stop and the error is
logged. On normal application shutdown, future runs are cancelled and the
application waits for in-flight processing, with a bounded shutdown timeout.

To disable generation for manual checks:

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar --tracker.producer.enabled=false
```

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `8081` | HTTP listener port. |
| `tracker.producer.enabled` | `true` | Automatically create and start both producers. |
| `tracker.audit.submission-size` | `1000` | Transactions per complete audit window; use `100000` for the larger-window scenario. |
| `tracker.audit.queue-capacity` | `4` | Maximum completed submissions waiting for the audit worker. |

## Audit collection

Every 1,000 accepted transactions form one immutable `AuditSubmission`. Windows
are disjoint and preserve processing order. At 999 transactions there is no
complete submission; transaction 1,000 completes the first window, and transaction
1,001 starts the next one. An incomplete window is never emitted on shutdown.

`InMemoryTransactionAuditService` captures a complete window and hands it to
`AsyncAuditDispatcher`. The hand-off uses a bounded queue's `offer` operation:
it does not wait for queue capacity or downstream processing. Snapshot creation
still takes O(N) time at the window boundary. The audit handler runs on the
separate `audit-dispatcher` thread, outside the account's balance lock.

If the queue is full, the completing transaction is rejected before its balance
change is committed, and the partial window is preserved for a possible retry.
The producers' processing-failure policy then stops both generators. A handler
failure retains its in-flight submission and the queued submissions in memory;
new complete windows are rejected, with no automatic downstream retry.

The dispatcher starts before the producers and stops after them. Normal shutdown
drains completed submissions. Its memory use is bounded by the partial window,
four queued windows by default, and one in-flight window. All of this state is
in memory and is lost on application restart.

## Audit batches and console output

`JsonAuditSubmissionHandler` processes each complete window on the audit worker.
Every transaction goes into exactly one batch within its own submission.
Transactions may be reordered for packing; the source window remains unchanged.
The total of **absolute** amounts in each batch cannot exceed GBP 1,000,000.00.
For example, GBP 500,000 credit plus GBP 500,000 debit fills a batch; it does not
have zero audit value. The immutable `AuditBatch` model also enforces the limit.
An individual amount above the capacity cannot be packed and causes a retained
handler failure. The normal producers never generate such an amount.

At the target generation rate, the first JSON line appears after roughly
20 seconds, with another after each complete 1,000-transaction window. Each line
is one valid JSON document with numeric totals in pounds and transaction counts.
For illustration, 1,000 transactions of magnitude GBP 2,000 produce:

```json
{"submission":{"batches":[{"totalValueOfAllTransactions":1000000.00,"countOfTransactions":500},{"totalValueOfAllTransactions":1000000.00,"countOfTransactions":500}]}}
```

Normal application logs accompany the JSON lines. A separate diagnostic records
the submission sequence, transaction count, batch count, lower bound and whether
optimality has been proven. It does not add fields to the JSON contract.
A console-write error fails the handler and retains the submission; a partial
console write cannot be rolled back and there is no automatic retry.

### Batch-count minimization

`BestFitDecreasingAuditBatchingService` sorts by descending absolute amount, then
puts each transaction into the tightest existing batch that can hold it. It opens
a new batch only when none fits. A `TreeMap` indexes remaining capacities and a
deque retains every batch when several have the same capacity. Stable sorting and
FIFO tie-breaking give repeatable results for the same input.

Amounts are converted to exact integer pennies for capacity decisions. A balanced
tree avoids scanning every batch for each item: packing takes O(N log N) time and
O(N) additional memory, including the sorted view and retained assignments.

This is a scalable minimization **heuristic**, not an exact optimizer. Its
`AuditBatchingResult` reports the greater of two valid lower bounds:

- `ceil(sum(abs(amount)) / 1,000,000)`.
- The number of transactions individually larger than half the batch capacity,
  because no two of those transactions can share a batch.

When the resulting batch count equals that bound, the result is provably optimal.
A larger count means optimality is unproven; the bound itself can be weak.
For example, magnitudes `[440000, 250000, 200000, 390000, 340000, 270000]`
produce three batches with this heuristic, although two are possible:
`[440000, 340000, 200000]` and `[390000, 270000, 250000]`. A regression test keeps
this limitation explicit rather than claiming a guaranteed minimum. Five amounts
of GBP 340,000 also produce three batches against a volume bound of two, but
three really are necessary in that case.

The algorithm targets fast, low-cost packing for large windows. If a guaranteed
minimum for every input is mandatory, this implementation needs an exact
optimization strategy with separately assessed runtime and dependency costs.
See the [optimization formulation](https://www.scipopt.org/scip/doc/html/BINPACKING_PROBLEM.php)
and the [heuristic/optimization discussion](https://scipbook.readthedocs.io/en/latest/bpp.html).

## Balance API

Retrieve the current balance:

```sh
curl -i http://localhost:8081/api/balance
```

A successful enquiry returns HTTP 200 with `Content-Type: application/json`,
`Cache-Control: no-store` and a body such as:

```json
{"balance":12345.67,"currency":"GBP"}
```

`balance` is a JSON number in pounds, serialized directly from the exact decimal
snapshot. Each request reads the shared account service without modifying it.
The no-store directive asks clients and intermediaries not to retain stale balance
responses. Concurrent transactions may change the balance after a snapshot is read.

POST requests return HTTP 405. A request that only accepts HTML returns HTTP 406.
The root path `/` has no application endpoint and returns HTTP 404.

With generation enabled, repeat the request to observe the balance as generated
transactions arrive. With generation disabled, a fresh application returns
`{"balance":0.00,"currency":"GBP"}`. The HTTP integration tests disable the
producers and apply known credits and debits directly to the service before
verifying the resulting responses.

## Dependency choices

Spring Boot 4.1.2 manages the dependency versions in the Maven parent, keeping the
framework and its supporting libraries aligned.

| Dependency | Purpose and assessment |
| --- | --- |
| `spring-boot-starter-webmvc` | Provides HTTP request handling, JSON support and an embedded servlet server. It is an official framework starter with maintained documentation and managed transitive dependencies. |
| `spring-boot-starter-webmvc-test` | Provides the framework's application and MVC testing support, including the managed unit-test libraries. It supports testing application configuration and later HTTP behavior. |

State is held in memory. A persistence framework is unnecessary for that design.
Java records, `BigDecimal` and intrinsic synchronization provide the domain model,
decimal arithmetic and concurrency controls without additional libraries.

Reference documentation:

- [Framework system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Application testing](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Maven Wrapper](https://maven.apache.org/wrapper/)
- [Decimal arithmetic](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)
- [Synchronization and memory visibility](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4.5)
- [Periodic scheduling](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html)
- [Application lifecycle](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html)
- [Bounded queue hand-off](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/BlockingQueue.html)
