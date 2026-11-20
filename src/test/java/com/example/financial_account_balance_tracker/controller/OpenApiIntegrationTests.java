package com.example.financial_account_balance_tracker.controller;

import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tracker.producer.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BankAccountService accountService;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void documentsTheReadOnlyBalanceContractWithoutChangingTheAccount() throws Exception {
        var before = accountService.retrieveExactBalance();

        HttpResponse<String> response = get("/v3/api-docs");

        assertEquals(200, response.statusCode());
        var specification = jsonMapper.readTree(response.body());
        assertTrue(specification.path("openapi").asString().startsWith("3."));
        assertEquals("Financial Account Balance Tracker API", specification.at("/info/title").asString());
        assertEquals(1, specification.path("paths").size());
        var path = specification.at("/paths/~1api~1balance");
        assertTrue(path.has("get"));
        assertFalse(path.has("post"));
        var responses = path.at("/get/responses");
        assertEquals("#/components/schemas/BalanceResponse",
                responses.at("/200/content/application~1json/schema/$ref").asString());
        assertTrue(responses.at("/406/content").has("application/problem+json"));
        assertTrue(responses.at("/500/content").has("application/problem+json"));
        // Audit errors are internal processing failures, not responses from this read-only GET.
        assertFalse(responses.has("400"));
        assertFalse(responses.has("503"));
        var schema = specification.at("/components/schemas/BalanceResponse");
        assertEquals("number", schema.at("/properties/balance/type").asString());
        assertEquals("string", schema.at("/properties/currency/type").asString());
        assertEquals("GBP", schema.at("/properties/currency/enum/0").asString());
        assertTrue(schema.path("required").toString().contains("balance"));
        assertTrue(schema.path("required").toString().contains("currency"));
        assertEquals(before, accountService.retrieveExactBalance());
    }

    @Test
    void servesSwaggerUiAssetsAndConnectsItToTheLocalSpecification() throws Exception {
        HttpResponse<String> entry = get("/swagger-ui.html");
        assertEquals(302, entry.statusCode());
        assertTrue(entry.headers().firstValue("Location").orElse("").endsWith("/swagger-ui/index.html"));

        HttpResponse<String> page = get("/swagger-ui/index.html");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(page.body().contains("swagger-ui-bundle.js"));
        assertTrue(page.body().contains("swagger-initializer.js"));
        assertEquals(200, get("/swagger-ui/swagger-ui-bundle.js").statusCode());
        assertEquals(200, get("/swagger-ui/swagger-ui.css").statusCode());

        HttpResponse<String> configuration = get("/v3/api-docs/swagger-config");
        assertEquals(200, configuration.statusCode());
        assertEquals("/v3/api-docs", jsonMapper.readTree(configuration.body()).path("url").asString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
