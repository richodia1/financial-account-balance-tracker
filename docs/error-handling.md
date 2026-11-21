# Error Handling

Models own domain validation. HTTP advice maps request failures to responses.
Background workers retain and log failures outside a request. These boundaries
keep exception handling explicit without duplicating business rules in controllers.

## Exception categories

| Type | Meaning | HTTP mapping if it reaches a request |
| --- | --- | --- |
| `InvalidTransactionException` | Missing transaction data, zero amount or fractional pennies. | 400, `INVALID_TRANSACTION`. Rule messages do not echo input values. |
| `InvalidAuditSubmissionException` | Invalid internal audit window, result or batch capacity. | 500, `AUDIT_PROCESSING_FAILED`. Audit payloads are internal data rather than input to the balance GET. |
| `AuditUnavailableException` | The dispatcher is stopped, failed or full, or a publisher rejects the hand-off. | 503, `AUDIT_UNAVAILABLE`. No retry time is promised. |
| `AuditProcessingException` | Serialization, console delivery or background processing failed. | 500, `AUDIT_PROCESSING_FAILED`. |

Configuration and helper-method misuse retain standard exceptions where suitable.
A generic `IllegalArgumentException` or `NullPointerException` is not automatically
bad client input: unexpected request failures produce a safe 500 response.

The custom types extend the corresponding standard unchecked categories without
a custom base hierarchy. Audit unavailability remains a `RejectedExecutionException`,
preserving its backpressure meaning to callers. Translation retains original
causes where available.

## HTTP responses

`exception.GlobalExceptionHandler` uses `ProblemDetail` through
`ResponseEntityExceptionHandler`. The framework handles built-in web exceptions;
application methods supply the domain mappings above. See the
[framework error-response documentation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).

Responses use `application/problem+json`, the appropriate HTTP status and
`Cache-Control: no-store`. Standard problem fields are accompanied by an
application-specific `code`.
Server errors add an `errorId`, echoed in `X-Error-Id` and the server log.
Exception objects, stack traces and internal causes are not serialized into
responses. The request instance is its URL path, excluding query parameters.

An unexpected failure has this shape; the identifier varies:

```json
{
  "type": "urn:problem:internal-error",
  "title": "Internal server error",
  "status": 500,
  "detail": "An unexpected error occurred.",
  "instance": "/api/balance",
  "code": "INTERNAL_ERROR",
  "errorId": "00000000-0000-4000-8000-000000000000"
}
```

Unknown routes retain 404, unsupported methods retain 405 with `Allow`, and
incompatible Accept requests retain 406. Clients accepting successful balance
responses should include `application/json`; asking only for
`application/problem+json` does not match that endpoint's success media type.

## Background failures and state

Worker failures do not pass through HTTP advice. The dispatcher records them as
`AuditProcessingException`, retaining the original cause. Already typed processing
failures are not wrapped twice. Failed in-flight work and queued submissions stay
in memory, and new complete windows are rejected with `AuditUnavailableException`.

The collector translates publisher rejection without clearing the partial window
or advancing its sequence. Audit recording precedes the account's balance commit,
so rejected hand-off leaves the balance unchanged. Producer processing failures
still stop both generators. Balance reads remain available when only the audit
worker has failed; reads do not check audit health.

Serialization completes before printing begins. Serialization failure retains its
cause and emits no partial document. Console write failure is detected through
the stream's error flag. A partially written document cannot be rolled back.
There is no automatic downstream retry or durable recovery. Exact-packing
interruption raises `AuditProcessingException`, preserving the thread interrupt
status; no provisional packing is emitted.

## Try the HTTP error paths

With the application running on its default port:

```sh
curl -i -X POST http://localhost:8081/api/balance
curl -i http://localhost:8081/not-a-route
curl -i -H 'Accept: text/html' http://localhost:8081/api/balance
```

Expect 405, 404 and 406 respectively, with problem JSON and no-store headers.
Server-failure tests use a failing service in the MVC test context. Background
tests use rejected publishers, failed handlers and failed output streams. These
scenarios are reproducible without diagnostic endpoints.

`GlobalExceptionHandlerTests` checks mappings, safe details, identifiers and
headers. Real-server tests verify the framework error contracts. Domain and audit
tests check typed validation, preserved causes, rollback and failure retention.
Failure-path tests deliberately log errors; use the final build result and test
totals to assess verification.
