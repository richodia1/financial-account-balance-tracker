# Financial Account Balance Tracker

A Java application for tracking an account balance and preparing value-limited
transaction audit batches.

## Current status

The application foundation builds, tests and starts as an HTTP service. Transaction
processing, balance enquiries, transaction producers and audit batching are the
next implementation stages. There are no application endpoints yet.

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
`target/`. The current context-loading test verifies that the application can
initialize its configuration without an external database.

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

The initial state will be held in memory. A persistence framework is unnecessary
for that design. Java's language features and standard library will provide the
domain model and concurrency primitives.

Reference documentation:

- [Framework system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Application testing](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Maven Wrapper](https://maven.apache.org/wrapper/)
