# Demonstration Guide

Use this sequence to build the application, observe its behaviour and explain the
design. The [requirements review](requirements-review.md) records acceptance
evidence, including minimum-count packing and the complete certification path.

## 1. Build with Java 21

From the repository root:

```sh
java -version
./mvnw --version
./mvnw clean verify
```

The first two commands should report Java 21. The build runs the correctness
suite and produces `target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar`.
The opt-in performance scenario is separate from the normal test run.

On macOS, select an installed JDK 21 for the current shell if needed:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

On Windows, use `mvnw.cmd` and the equivalent Java environment settings.

## 2. Start and observe the balance

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar
```

The default port is 8081. The startup log should report 25 credits and 25 debits
per second. In another terminal:

```sh
curl -i http://localhost:8081/api/balance
```

Expect HTTP 200, an application/json content type, `Cache-Control: no-store`, and
a response shaped like:

```json
{"balance":12345.67,"currency":"GBP"}
```

The amount is an illustration; generated values are random. Repeat the GET to
read another snapshot. The enquiry itself does not change the balance.

The root URL `/` returns 404 because the balance route is `/api/balance`. If port
8081 is occupied, start with `--server.port=8082` and use that port in the curl
command. Stop this application with Ctrl+C when finished.

To explore the same GET interactively, open
[Swagger UI](http://localhost:8081/swagger-ui/index.html). Expand **Account balance**,
select **GET /api/balance**, click **Try it out**, then **Execute**. The result
shows the live balance and HTTP response. The generated specification is available
at [`/v3/api-docs`](http://localhost:8081/v3/api-docs).

After adding the Swagger dependency for the first time, stop the old IDE run,
reload the Maven project and restart. That loads the new documentation library
onto the application classpath. No additional process is needed for Swagger UI.

## 3. Observe a complete audit submission

With the default producers running, wait approximately 20 seconds. The console
will print a JSON line containing `submission.batches`, followed by a diagnostic
with the transaction count, batch count, lower bound and optimality flag.

The individual batches' transaction counts add to 1,000. Every batch total is
positive and at most GBP 1,000,000. Amounts are numeric pounds, not pennies or
formatted currency strings. The number of batches varies with generated input.

Explain the capacity rule with two transactions: a GBP 500,000 credit and a
GBP 500,000 debit leave a net balance change of zero but fill GBP 1,000,000 of
audit capacity. Credits do not cancel debits for packing.

A successful default submission has `optimality proven: true`. This means its
batch count has either reached a mathematical lower bound or been established
by complete exact search. The reported bound is tightened to the proven minimum
when search rules out smaller counts.

## 4. Show a deterministic starting balance

Stop the previous application, then start a fresh instance with producers disabled:

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar --tracker.producer.enabled=false
```

The same balance GET now returns `{"balance":0.00,"currency":"GBP"}` and stays
unchanged. This also demonstrates that state starts fresh after a restart.

For deterministic credits and debits, debug `InMemoryBankAccountServiceTests` in
the IDE. One test applies `500000`, `-200.01`, `200.02`, `-500000` and verifies
the exact result `0.01`. The tests exercise updates through the service contract.

## 5. Walk through the code

All packages below sit under `com.example.financial_account_balance_tracker`.

| Start here | Explanation |
| --- | --- |
| `FinancialAccountBalanceTrackerApplication` | Starts the application and component scanning. |
| `config.ProducerConfiguration` and `config.AuditConfiguration` | Wire services and lifecycle-managed workers. The audit worker starts before producers and stops after them. |
| `config.OpenApiConfiguration` | Supplies documentation metadata. Springdoc scans the HTTP mappings and response annotations to generate OpenAPI and serve Swagger UI. |
| `model.Transaction` and `enums.TransactionType` | Immutable identity and signed amount; scale validation rejects fractional pennies. Type is derived from the sign. |
| `producer.RandomTransactionFactory` | Generates random IDs and signed whole-pence amounts within inclusive limits. |
| `producer.TransactionProducer` | Owns one dedicated fixed-rate scheduled executor per transaction type, with a 40 ms period. |
| `service.BankAccountService` and `service.impl.InMemoryBankAccountService` | Preserve the required interface and add an exact decimal accessor. One synchronized monitor protects balance updates and reads. |
| `controller.BalanceController` and `dto.BalanceResponse` | Expose a read-only balance snapshot over HTTP with response headers and a numeric GBP amount. |
| `service.impl.InMemoryTransactionAuditService` | Collects disjoint complete windows and clears a partial window only after successful hand-off. |
| `audit.AsyncAuditDispatcher` | Uses a bounded queue to move downstream work outside the account lock. It retains failed work in memory and rejects new complete windows. |
| `service.impl.BestFitDecreasingAuditBatchingService` | Sorts magnitudes largest first; a capacity-indexed tree finds the tightest existing fit. A deque preserves batches with equal remaining capacity. |
| `service.impl.OptimizingAuditBatchingService` | Coordinates fast packing, local improvements and complete minimum-count certification. |
| `service.impl.SmallSetExactBatchPacker` | Reconstructs an exact packing for at most 18 transactions using subset states and parent choices. |
| `service.impl.BoundedBatchRepacker` | Tries a one-batch reduction for up to 128 individual transactions, using backtracking with equal-load symmetry pruning and a fixed node budget. |
| `service.impl.ExactAuditBatchPacker` | Uses volume/cardinality bounds and complete iterative assignment search to establish the minimum. |
| `service.impl.LookaheadAuditBatchPacker` | Builds a deterministic pair-lookahead candidate before complete certification. |
| `model.AuditBatch` and `model.AuditBatchingResult` | Retain immutable assignments, validate capacity and expose the lower-bound result. |
| `audit.JsonAuditSubmissionHandler` and audit DTOs | Map batches to the output shape and serialize one JSON document per submission. |

Keep these explanations precise:

- **Money:** `BigDecimal` accumulates exact decimal amounts. Conversion to the
  required `double` happens only at that interface boundary. REST uses the exact
  accessor; no floating-point balance is accumulated.
- **Concurrency:** the balance update is a read-modify-write operation. The same
  monitor protects reads and writes, so concurrent updates do not overwrite one
  another. Default scope gives one account service per application context.
- **Hand-off:** queue `offer` does not wait for downstream work. Snapshot copying
  still costs O(N) at the window boundary while the account operation is active.
- **Packing:** integer-penny arithmetic enforces capacities. Best-fit decreasing
  gives an O(N log N) initial solution; small exact solutions, local repair and pair lookahead
  improve it. The complete stage tests candidate counts from a valid lower bound
  upward, pruning only mathematically excluded assignments. Its explicit arrays
  avoid transaction-depth recursion. Every successful result is minimum-count;
  exact search has exponential worst-case time and O(N + B) search state.
- **Failure:** a full queue rejects the completing transaction before the balance
  commit. A handler failure retains work in memory, with no automatic retry or
  durable recovery.

## 6. Demonstrate the larger window

Run the repeatable burst-input scenario:

```sh
./mvnw -Dtest=AuditPerformanceScenario '-DargLine=-Xms256m -Xmx512m' test
```

It exercises both 1,000- and 100,000-transaction submissions and writes timing,
quality and environment files to `target/`. Read the
[methodology and recorded results](performance.md) before discussing the numbers.
The pipeline uses the real account, collector, dispatcher, packer and JSON handler
with output captured in memory. It excludes the wait for rate-limited generation.
`clean` removes generated target files; rerun this command to regenerate them.

To observe normal generation with a larger configured window instead:

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar --tracker.audit.submission-size=100000
```

At a target rate of 50 transactions per second, filling that window takes roughly
33 minutes and 20 seconds. The setting changes window size, not producer rates.
Use the burst-input scenario for a short demonstration of the processing path.

## IDE run configuration

For error-handling practice, use the commands and failure-path tests in the
[error-handling guide](error-handling.md). Start with a POST to the read-only
balance route and trace its 405 response through `GlobalExceptionHandler`.

In IntelliJ, select Project SDK and language level 21. Import the project through
`pom.xml`, then open `FinancialAccountBalanceTrackerApplication` and use the Run
icon beside `main`.

If adding an Application configuration manually, use:

- Main class: `com.example.financial_account_balance_tracker.FinancialAccountBalanceTrackerApplication`.
- Module classpath: `financial-account-balance-tracker`.
- JRE: the project's Java 21 SDK.
- Working directory: the repository root.
- Before launch: Build.

Program arguments can contain `--server.port=8082` or
`--tracker.producer.enabled=false`. Clear them to return to the defaults.
If Maven import reports an injector error with the wrapper distribution, select
the IDE's bundled Maven 3 for importing and reimport with JDK 21. The command-line
Maven Wrapper remains the build path documented above.
