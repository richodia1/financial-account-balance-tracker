package com.example.financial_account_balance_tracker.controller;

import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BalanceControllerIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private BankAccountService accountService;

    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void returnsLiveExactBalanceWithoutModifyingTheAccount() throws Exception {
        assertBalance("0.00");

        process(1, "400.10");
        process(2, "-200.03");
        assertBalance("200.07");
        assertBalance("200.07");
        assertEquals(new BigDecimal("200.07"), accountService.retrieveExactBalance());

        process(3, "-500.08");
        assertBalance("-300.01");

        // This value would lose penny precision if converted through a double.
        process(4, "90071992547709.94");
        assertBalance("90071992547409.93");
        assertEquals(new BigDecimal("90071992547409.93"), accountService.retrieveExactBalance());
    }

    @Test
    void rejectsPostRequestsWithoutModifyingTheAccount() throws Exception {
        BigDecimal before = accountService.retrieveExactBalance();
        HttpRequest request = request()
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
        assertEquals(before, accountService.retrieveExactBalance());
    }

    @Test
    void rejectsRequestsThatOnlyAcceptHtml() throws Exception {
        HttpRequest request = request().header("Accept", "text/html").GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(406, response.statusCode());
    }

    private void assertBalance(String expected) throws Exception {
        HttpRequest request = request().header("Accept", "application/json").GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(jsonMapper.readTree(response.body()).get("balance").isNumber());

        // A separate test contract avoids coupling HTTP assertions to the response DTO.
        BalancePayload payload = jsonMapper.readValue(response.body(), BalancePayload.class);
        assertEquals(new BigDecimal(expected), payload.balance());
        assertEquals("GBP", payload.currency());
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/balance"))
                .timeout(Duration.ofSeconds(5));
    }

    private void process(long id, String amount) {
        accountService.processTransaction(new Transaction(new UUID(0, id), new BigDecimal(amount)));
    }

    private record BalancePayload(BigDecimal balance, String currency) {
    }
}
