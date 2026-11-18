package com.example.financial_account_balance_tracker.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/** Maps HTTP-request failures; background workers handle their own failures. */
@RestControllerAdvice
public final class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidTransactionException.class)
    ResponseEntity<Object> invalidTransaction(InvalidTransactionException exception, WebRequest request) {
        return problem(exception, HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", "Invalid transaction",
                exception.getMessage(), request);
    }

    @ExceptionHandler(AuditUnavailableException.class)
    ResponseEntity<Object> auditUnavailable(AuditUnavailableException exception, WebRequest request) {
        return problem(exception, HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE", "Audit unavailable",
                "Audit processing is currently unavailable.", request);
    }

    @ExceptionHandler({AuditProcessingException.class, InvalidAuditSubmissionException.class})
    ResponseEntity<Object> auditFailure(RuntimeException exception, WebRequest request) {
        // Audit payloads are internal. Their validation failures are server errors,
        // not invalid input supplied to the read-only balance endpoint.
        return problem(exception, HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_PROCESSING_FAILED", "Audit processing failed",
                "An audit operation could not be completed.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        // A generic IllegalArgumentException or NullPointerException may be a bug;
        // neither is automatically converted into a client validation error.
        return problem(exception, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error",
                "An unexpected error occurred.", request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail detail ? detail
                : ProblemDetail.forStatusAndDetail(status, "The request could not be completed.");
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", switch (status.value()) {
                case 400 -> "BAD_REQUEST";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 500 -> "INTERNAL_ERROR";
                default -> "HTTP_" + status.value();
            });
        }
        if (status.value() == 404) {
            problem.setDetail("The requested resource was not found.");
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setCacheControl("no-store");
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status.is5xxServerError()) {
            if (!(exception instanceof AuditUnavailableException) && !(exception instanceof AuditProcessingException)
                    && !(exception instanceof InvalidAuditSubmissionException)) {
                problem.setDetail("An unexpected error occurred.");
            }
            String errorId = UUID.randomUUID().toString();
            problem.setProperty("errorId", errorId);
            responseHeaders.set("X-Error-Id", errorId);
            LOG.error("Request failed with status {} [errorId={}]", status.value(), errorId, exception);
        }
        return super.handleExceptionInternal(exception, problem, responseHeaders, status, request);
    }

    private ResponseEntity<Object> problem(Exception exception, HttpStatus status, String code, String title,
            String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return handleExceptionInternal(exception, problem, HttpHeaders.EMPTY, status, request);
    }
}
