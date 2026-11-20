package com.example.financial_account_balance_tracker.controller;

import com.example.financial_account_balance_tracker.dto.BalanceResponse;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles balance enquiries and maps the service result to the HTTP response DTO.
 */
@RestController
@RequestMapping("/api/balance")
@Tag(name = "Account balance", description = "Read the shared account's current balance.")
public final class BalanceController {

    private final BankAccountService accountService;

    public BalanceController(BankAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retrieve the current account balance",
            description = "Returns an exact decimal snapshot in GBP without modifying the account. "
                    + "Negative balances are allowed. Responses use Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Current balance snapshot",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BalanceResponse.class)))
    @ApiResponse(responseCode = "406", description = "The requested response media type is not supported.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server failure; includes a safe code and errorId.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BalanceResponse> retrieveBalance() {
        BalanceResponse response = new BalanceResponse(accountService.retrieveExactBalance(), "GBP");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
