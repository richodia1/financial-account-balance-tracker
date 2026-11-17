package com.example.financial_account_balance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "tracker.producer.enabled=false")
class FinancialAccountBalanceTrackerApplicationTests {

	@Test
	void contextLoads() {
	}

}
