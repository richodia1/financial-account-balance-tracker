# Financial Account Balance Tracker

A Java application for tracking an account balance and preparing value-limited
transaction audit batches.

## Current status

The application builds, tests and starts as an HTTP service. An immutable
transaction model and a thread-safe, in-memory balance service are implemented.
The REST balance endpoint, transaction producers and audit batching are the next
implementation stages. There are no application endpoints yet, and processed
transactions are not yet retained for audit.

## Balance model

- `Transaction` contains a UUID and a signed GBP amount. Credits are positive;
  debits are negative. IDs and amounts must be present, and zero-value
  transactions are rejected.
- Amounts are normalized to two decimal places. Fractional pennies are rejected
  instead of rounded silently. Decimal amounts should be constructed from
  decimal strings or whole-pence values rather than binary floating-point input.
- `BankAccountService` retains `processTransaction(Transaction)` and
  `double retrieveBalance()`. Its additional `retrieveExactBalance()` method
  preserves decimal precision for callers, including the future REST endpoint.
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
credits, debits and enquiries. A context-loading test verifies that the application
can initialize its configuration without an external database.

## Run

```sh
java -jar target/financial-account-balance-tracker-0.0.1-SNAPSHOT.jar
```

The HTTP server listens on port 8080 by default. Stop it with Ctrl+C. Until the
balance endpoint is implemented, requests return HTTP 404.

Alternatively, run directly from source:

```sh
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw` and set `JAVA_HOME` to a JDK 21
installation.

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
