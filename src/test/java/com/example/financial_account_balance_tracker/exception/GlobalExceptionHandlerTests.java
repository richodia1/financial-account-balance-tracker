package com.example.financial_account_balance_tracker.exception;

import com.example.financial_account_balance_tracker.controller.BalanceController;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BalanceController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private BankAccountService accountService;

    @Test
    void mapsExplicitTransactionValidationToBadRequestWithoutSerializingTheCause() throws Exception {
        when(accountService.retrieveExactBalance()).thenThrow(new InvalidTransactionException(
                "Transaction amount must not be zero", new ArithmeticException("private-cause-marker")));

        String body = mvc.perform(get("/api/balance").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-transaction"))
                .andExpect(jsonPath("$.title").value("Invalid transaction"))
                .andExpect(jsonPath("$.detail").value("Transaction amount must not be zero"))
                .andExpect(jsonPath("$.instance").value("/api/balance"))
                .andExpect(jsonPath("$.code").value("INVALID_TRANSACTION"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("private-cause-marker", "ArithmeticException", "stackTrace");
    }

    @Test
    void mapsUnavailableAuditTo503WithASafeBodyAndErrorIdentifier() throws Exception {
        when(accountService.retrieveExactBalance()).thenThrow(new AuditUnavailableException("queue-private-marker"));

        var response = mvc.perform(get("/api/balance").accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Audit processing is currently unavailable."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();

        assertSafeServerFailure(response.getContentAsString(), response.getHeader("X-Error-Id"));
        assertThat(response.getContentAsString()).doesNotContain("queue-private-marker");
    }

    @ParameterizedTest
    @MethodSource("internalAuditFailures")
    void internalAuditDataAndProcessingFailuresAreServerErrors(RuntimeException failure) throws Exception {
        when(accountService.retrieveExactBalance()).thenThrow(failure);

        var response = mvc.perform(get("/api/balance"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIT_PROCESSING_FAILED"))
                .andExpect(jsonPath("$.detail").value("An audit operation could not be completed."))
                .andReturn().getResponse();

        assertSafeServerFailure(response.getContentAsString(), response.getHeader("X-Error-Id"));
        assertThat(response.getContentAsString()).doesNotContain("audit-private-marker");
    }

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void unexpectedBugsRemain500AndNeverExposeExceptionMessagesOrQueryParameters(RuntimeException failure) throws Exception {
        when(accountService.retrieveExactBalance()).thenThrow(failure);

        var response = mvc.perform(get("/api/balance").queryParam("token", "query-private-marker"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.instance").value("/api/balance"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();

        assertSafeServerFailure(response.getContentAsString(), response.getHeader("X-Error-Id"));
        assertThat(response.getContentAsString()).doesNotContain("unexpected-private-marker", "query-private-marker");
    }

    @Test
    void preservesMethodNotAllowedAndTheAllowHeader() throws Exception {
        mvc.perform(post("/api/balance"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(accountService);
    }

    @Test
    void preservesNotAcceptableInsteadOfConvertingItInto500() throws Exception {
        mvc.perform(get("/api/balance").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        verifyNoInteractions(accountService);
    }

    @Test
    void unknownRoutesReturnAProblemDetailWith404() throws Exception {
        mvc.perform(get("/not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(accountService);
    }

    private void assertSafeServerFailure(String body, String headerId) {
        var document = jsonMapper.readTree(body);
        String errorId = document.get("errorId").asString();
        assertThat(UUID.fromString(errorId).toString()).isEqualTo(errorId);
        assertThat(headerId).isEqualTo(errorId);
        assertThat(body).doesNotContain("stackTrace", "exception", "cause", "java.lang");
    }

    private static Stream<RuntimeException> internalAuditFailures() {
        return Stream.of(new AuditProcessingException("audit-private-marker"),
                new InvalidAuditSubmissionException("audit-private-marker"));
    }

    private static Stream<RuntimeException> unexpectedFailures() {
        return Stream.of(new NullPointerException("unexpected-private-marker"),
                new IllegalArgumentException("unexpected-private-marker"),
                new IllegalStateException("unexpected-private-marker"));
    }
}
