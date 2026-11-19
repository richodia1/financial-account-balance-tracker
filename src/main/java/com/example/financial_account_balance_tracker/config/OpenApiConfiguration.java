package com.example.financial_account_balance_tracker.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/** Supplies the API description; springdoc derives paths and schemas from the web layer. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Financial Account Balance Tracker API",
        version = "0.0.1-SNAPSHOT",
        description = "Read-only GBP balance enquiries for the shared in-memory account. "
                + "Automatic credit and debit producers update the balance when enabled. "
                + "Transactions and audit submissions are processed internally, not submitted over HTTP."))
public class OpenApiConfiguration {
}
