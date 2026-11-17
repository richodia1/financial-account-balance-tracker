# Financial Account Balance Tracker

A Java application for tracking an account balance and preparing value-limited
transaction audit batches.

## Current status

The application builds, tests and starts as an HTTP service. It implements an
immutable transaction model, a thread-safe in-memory balance service and a REST
balance endpoint. Transaction producers and audit batching are the next
implementation stages; processed transactions are not yet retained for audit.

## Balance model

- `Transaction` contains a UUID and a signed GBP amount. Credits are positive;
  debits are negative. IDs and amounts must be present, and zero-value
  transactions are rejected.
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
- Debits may take the balance below zero. No overdraft restriction is applied.
  Each processing call applies its transaction; IDs are not deduplication keys.
- The producer will enforce the GBP 200 to GBP 500,000 generation range. The
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

## Balance API

Retrieve the current balance:

```sh
curl -i http://localhost:8081/api/balance
```

A fresh application returns HTTP 200 with `Content-Type: application/json`,
`Cache-Control: no-store` and this body:

```json
{"balance":0.00,"currency":"GBP"}
```

`balance` is a JSON number in pounds, serialized directly from the exact decimal
snapshot. Each request reads the shared account service without modifying it.
The no-store directive asks clients and intermediaries not to retain stale balance
responses. Concurrent transactions may change the balance after a snapshot is read.

POST requests return HTTP 405. A request that only accepts HTML returns HTTP 406.
The root path `/` has no application endpoint and returns HTTP 404.

The transaction producers are not implemented yet, so a fresh application stays
at zero until transactions are processed through the Java service. The HTTP
integration tests apply credits and debits directly to that service before
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
