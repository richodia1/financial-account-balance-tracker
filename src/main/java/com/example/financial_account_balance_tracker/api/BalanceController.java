package com.example.financial_account_balance_tracker.api;

import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/balance")
public final class BalanceController {

    private final BankAccountService accountService;

    public BalanceController(BankAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BalanceResponse> retrieveBalance() {
        BalanceResponse response = new BalanceResponse(accountService.retrieveExactBalance(), "GBP");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
